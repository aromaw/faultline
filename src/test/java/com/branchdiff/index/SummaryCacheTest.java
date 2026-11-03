package com.branchdiff.index;

import com.branchdiff.Main;
import com.branchdiff.Options;
import com.branchdiff.analysis.Analyzer;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Report;
import com.branchdiff.util.Fingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The summary cache must never change a result: cold, warm, disabled, damaged and concurrently written alike. */
class SummaryCacheTest {
    @TempDir
    Path tmp;

    @AfterEach
    void restore() {
        SummaryCache.setDefault(null);
        AstCache.clear();
    }

    /** this project's own sources (real code, ~100 files) plus files with every kind of summarized fact */
    static Snapshot corpus() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                files.put(p.toString().replace('\\', '/'), Files.readString(p));
            }
        }
        files.put("svc/src/main/java/x/Cfg.java", """
                package x;
                import org.springframework.beans.factory.annotation.*;
                import org.springframework.context.annotation.*;
                import java.util.*;
                @Configuration
                @MapperScan({"x.mapper", "x.other"})
                @ConfigurationProperties(prefix = "x.cfg")
                public class Cfg {
                    @Value("${x.a:1} ${x.b}") private String a;
                    @Autowired(required = false) @Qualifier("q1") private Svc svc;
                    @Resource(name = "r1") private Svc r;
                    private final Environment env;
                    public Cfg(Environment env, @Value("${x.c}") int c) { this.env = env; }
                    @Bean({"one", "two"}) public Svc svc(@Qualifier("q2") Svc other) { return other; }
                    @Bean public List<Svc> list() { return List.of(); }
                    String key() { return env.getProperty("x.d", "dflt") + env.getRequiredProperty("x.e"); }
                    @Autowired void setIt(Optional<Svc> s) { }
                }
                """);
        files.put("svc/src/main/java/x/Kinds.java", """
                package x;
                import lombok.RequiredArgsConstructor;
                @org.springframework.stereotype.Service("named")
                @RequiredArgsConstructor
                public class Kinds<T extends Comparable<T>> extends Base<T> implements Api, java.io.Serializable {
                    private final Svc svc;
                    static final String C = "a" + "b";
                    int x = 1, y;
                    public enum E implements Api { A(1, "x") { void f() { } }, B(2, "y"); E(int i, String s) { } }
                    public record R<U>(U value, int n) implements Api { public R { } int n() { return n; } }
                    public @interface Ann { String value() default ""; }
                    interface Inner { int CONST = 3; void m(String... xs); default int d() { return 1; } }
                    <V> V generic(java.util.Map<String, ? extends V> m, int[] a) { return null; }
                    void overload(java.util.List<String> a) { } void overload(java.util.List<Integer> b) { int q; }
                    Kinds(Svc svc) { this.svc = svc; }
                    void anon() { new Runnable() { public void run() { } }; class Local { } }
                }
                """);
        files.put("svc/src/main/java/x/Broken.java", "package x; public class Broken { void f( { } }");
        files.put("svc/src/main/java/x/Copy.java", files.get("svc/src/main/java/x/Kinds.java")); // same blob twice
        files.put("svc/src/main/java/x/Uni.java", "package x;\n/** 中文 \u00e9 */ public class Uni { String s = \"\u4e2d\u6587\"; }");
        files.put("svc/src/main/resources/application.yml", "x:\n  a: 1\n");
        return new Snapshot("ours", "ours", "sha", "", files);
    }

    /** every fact of an index, member texts included (printed from the AST, checked against their fingerprints) */
    static String dump(CodeIndex idx) {
        StringBuilder sb = new StringBuilder();
        sb.append("files ").append(idx.javaFiles()).append('\n');
        sb.append("roots ").append(idx.sourceRoots).append('\n');
        sb.append("errors ").append(idx.parseErrors).append('\n');
        sb.append("shadowed ").append(idx.shadowedSiblingClasses).append('\n');
        for (ClassInfo c : idx.classes.values()) {
            sb.append("class ").append(c.fqn).append(' ').append(c.simpleName).append(' ').append(c.pkg).append(' ')
                    .append(c.kind).append(' ').append(c.isAbstract).append(' ').append(c.location()).append(' ')
                    .append(c.outerFqn).append(' ').append(c.typeIndex).append(' ').append(c.imports).append(' ')
                    .append(c.annotations).append(' ').append(c.superRaw).append(' ').append(c.supertypes).append(' ')
                    .append(c.typeParams).append(' ').append(c.inScope).append('\n');
            sb.append("  header ").append(c.normalizedHeader()).append('\n');
            for (FieldInfo f : c.fields.values()) {
                sb.append("  field ").append(f.name).append(' ').append(f.typeRaw).append(' ').append(f.type).append(' ')
                        .append(f.isStatic).append(f.isFinal).append(f.hasInitializer).append(f.enumConstant).append(' ')
                        .append(f.annotations).append(' ').append(f.line).append(' ').append(f.initializer).append('\n');
            }
            for (MethodInfo m : c.methods.values()) {
                String decl = m.normalizedDecl(), body = m.normalizedBody();
                assertTrue(m.declPrint.matches(decl), "declaration fingerprint of " + m.id());
                assertTrue(m.bodyPrint.matches(body), "body fingerprint of " + m.id());
                sb.append("  method ").append(m.id()).append(' ').append(m.returnType).append(' ').append(m.constructor)
                        .append(m.isStatic).append(m.isAbstract).append(m.isDefault).append(' ').append(m.annotations)
                        .append(' ').append(m.line).append('-').append(m.endLine).append(' ').append(m.memberIndex)
                        .append(' ').append(m.declPrint).append('\n').append(decl).append('\n');
            }
        }
        sb.append("beans ").append(idx.beans).append('\n');
        sb.append("injection ").append(idx.injectionPoints).append('\n');
        sb.append("values ").append(idx.valueRefs).append('\n');
        sb.append("props ").append(idx.configPropsClasses).append('\n');
        sb.append("scan ").append(idx.mapperScanPackages).append('\n');
        for (ClassInfo c : idx.classes.values()) {
            for (String a : c.annotationNames()) sb.append("ann ").append(c.fqn).append(' ').append(c.annotation(a)).append('\n');
        }
        return sb.toString();
    }

    /** assertEquals of two dumps, reporting the first different line */
    static void same(String expected, String actual) {
        if (expected.equals(actual)) return;
        String[] a = expected.split("\n", -1), b = actual.split("\n", -1);
        int i = 0;
        while (i < a.length && i < b.length && a[i].equals(b[i])) i++;
        throw new AssertionError("dumps differ at line " + (i + 1) + ":\n- " + (i < a.length ? a[i] : "<end>") + "\n+ "
                + (i < b.length ? b[i] : "<end>"));
    }

    static long javaCount(Snapshot s) {
        return s.files().keySet().stream().filter(p -> p.endsWith(".java")).count();
    }

    @Test
    void coldWarmAndDisabledGiveTheSameIndex() throws IOException {
        Snapshot s = corpus();
        String disabled = dump(CodeIndex.build(s, null, false, SummaryCache.disabled()));
        SummaryCache cold = SummaryCache.at(tmp, 1L << 30);
        String coldDump = dump(CodeIndex.build(s, null, false, cold));
        same(disabled, coldDump);
        assertTrue(cold.writes() > 50, cold.stats());
        assertEquals(javaCount(s) - 1, cold.writes(), "the duplicated blob is parsed once: " + cold.stats());
        assertEquals(1, cold.hits(), cold.stats());

        SummaryCache warm = SummaryCache.at(tmp, 1L << 30);
        same(disabled, dump(CodeIndex.build(s, null, false, warm)));
        assertEquals(javaCount(s), warm.hits(), warm.stats());
        assertEquals(0, warm.misses() + warm.writes() + warm.rejected(), warm.stats());
        assertTrue(disabled.contains("errors [svc/src/main/java/x/Broken.java"), disabled.substring(0, 300));
    }

    @Test
    void disabledCacheWritesNothing() throws IOException {
        SummaryCache.setDefault(SummaryCache.disabled());
        CodeIndex.build(corpus(), null, false);
        assertFalse(SummaryCache.getDefault().enabled());
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void damagedEntriesAreIgnoredAndWrittenAgain() throws IOException {
        Snapshot s = corpus();
        String expected = dump(CodeIndex.build(s, null, false, SummaryCache.disabled()));
        SummaryCache c = SummaryCache.at(tmp, 1L << 30);
        CodeIndex.build(s, null, false, c);
        List<String> paths = new ArrayList<>(s.files().keySet().stream().filter(p -> p.endsWith(".java")).toList());
        java.util.function.Function<Integer, Path> entry = i -> c.entryPath(SummaryCache.blobId(s.files().get(paths.get(i))));
        byte[] id0 = SummaryCache.blobId(s.files().get(paths.get(0)));
        // truncated (a crash in the middle of a write), empty, garbage, one flipped payload byte, another blob's entry,
        // and a well-framed entry whose payload does not decode
        Path p0 = entry.apply(0);
        byte[] b0 = Files.readAllBytes(p0);
        Files.write(p0, java.util.Arrays.copyOf(b0, b0.length / 2));
        Files.write(entry.apply(1), new byte[0]);
        Files.writeString(entry.apply(2), "not a summary at all, but long enough to have a header and a checksum");
        Path p3 = entry.apply(3);
        byte[] b3 = Files.readAllBytes(p3);
        b3[b3.length / 2] ^= 0x40;
        Files.write(p3, b3);
        Files.copy(entry.apply(5), entry.apply(4), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] id6 = SummaryCache.blobId(s.files().get(paths.get(6)));
        Files.write(entry.apply(6), SummaryCache.wrap(id6, new byte[]{1, 7, 7, 7, 7}));
        assertNull(SummaryCache.unwrap(java.util.Arrays.copyOf(b0, b0.length / 2), id0));

        SummaryCache again = SummaryCache.at(tmp, 1L << 30);
        same(expected, dump(CodeIndex.build(s, null, false, again)));
        // entries 0-4 and 6 (entry 5 is intact; its copy at 4 is not)
        assertEquals(6, again.rejected(), again.stats());
        assertEquals(6, again.writes(), again.stats());

        SummaryCache third = SummaryCache.at(tmp, 1L << 30);
        same(expected, dump(CodeIndex.build(s, null, false, third)));
        assertEquals(0, third.rejected() + third.misses(), "damaged entries were rewritten: " + third.stats());
    }

    @Test
    void concurrentRunsShareOneCacheSafely() throws Exception {
        Snapshot s = corpus();
        String expected = dump(CodeIndex.build(s, null, false, SummaryCache.disabled()));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) runs.add(() -> dump(CodeIndex.build(s, null, false, SummaryCache.at(tmp, 1L << 30))));
            for (Future<String> f : pool.invokeAll(runs)) same(expected, f.get());
        } finally {
            pool.shutdown();
        }
        try (Stream<Path> files = Files.walk(tmp)) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().contains(".tmp-")), "no temp file left");
        }
        SummaryCache warm = SummaryCache.at(tmp, 1L << 30);
        same(expected, dump(CodeIndex.build(s, null, false, warm)));
        assertEquals(0, warm.rejected() + warm.misses(), warm.stats());
    }

    @Test
    void pruneKeepsTheCacheUnderItsLimitAndClearEmptiesIt() throws IOException {
        Snapshot s = corpus();
        CodeIndex.build(s, null, false, SummaryCache.at(tmp, 1L << 30));
        long entries = javaCount(s) - 1;
        Path stale = tmp.resolve("summaries").resolve(SummaryCache.buildKey()).resolve("ab").resolve("cd.tmp-1-2");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[10]);
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L));
        Path other = tmp.resolve("summaries").resolve("f0-old").resolve("00").resolve("11");
        Files.createDirectories(other.getParent());
        Files.write(other, new byte[100]);
        Files.setLastModifiedTime(other, FileTime.fromMillis(0)); // least recently used: goes first

        SummaryCache.PruneResult unbounded = SummaryCache.at(tmp, 1L << 30).prune();
        assertEquals(entries + 1, unbounded.entries());
        assertFalse(Files.exists(stale), "stale temp file removed");

        long limit = 40 * 4096;
        SummaryCache.PruneResult r = SummaryCache.at(tmp, limit).prune();
        assertTrue(r.bytes() <= limit, r.toString());
        assertFalse(Files.exists(other), "other builds' entries go first");
        assertTrue(r.removed() > 0);
        // pruned entries are parsed again, same index
        String expected = dump(CodeIndex.build(s, null, false, SummaryCache.disabled()));
        same(expected, dump(CodeIndex.build(s, null, false, SummaryCache.at(tmp, 1L << 30))));

        assertTrue(SummaryCache.clear(tmp) > 0);
        assertFalse(Files.exists(tmp.resolve("summaries")));
        assertTrue(SummaryCache.info(tmp).isEmpty());
    }

    @Test
    void blobIdIsGitsBlobId() {
        // git hash-object of "hello\n"
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                java.util.HexFormat.of().formatHex(SummaryCache.blobId("hello\n")));
        Fingerprint f = Fingerprint.of("abc");
        assertTrue(f.matches("abc"));
        assertFalse(f.matches("abd"));
        assertEquals(3, f.length());
    }

    @Test
    void entryFrameRoundTrips() {
        byte[] id = SummaryCache.blobId("x");
        byte[] payload = {1, 2, 3};
        byte[] framed = SummaryCache.wrap(id, payload);
        assertNotNull(SummaryCache.unwrap(framed, id));
        assertNull(SummaryCache.unwrap(framed, SummaryCache.blobId("y")));
        ByteBuffer.wrap(framed).putInt(24, 4); // wrong length
        assertNull(SummaryCache.unwrap(framed, id));
    }

    @Test
    void buildKeyIsFormatVersionAndExtractionCodeNotBuildTime() {
        String k = SummaryCache.buildKey();
        assertTrue(k.startsWith("f" + FileSummaries.FORMAT + "-"), k);
        String code = SummaryCache.codeHash();
        assertNotNull(code, "extraction classes readable from target/classes");
        assertTrue(code.matches("[0-9a-f]{12}"), code);
        assertTrue(k.endsWith("-" + code), k);
        assertEquals(code, SummaryCache.codeHash(), "stable");
    }

    @Test
    void cacheCanBeDisabledAndMovedBySystemProperties() {
        String on = System.getProperty("faultline.cache"), dir = System.getProperty("faultline.cacheDir");
        try {
            System.setProperty("faultline.cacheDir", tmp.resolve("elsewhere").toString());
            assertEquals(tmp.resolve("elsewhere").toAbsolutePath(), SummaryCache.fromEnvironment().root());
            for (String off : List.of("off", "0", "false", "no", "OFF")) {
                System.setProperty("faultline.cache", off);
                assertFalse(SummaryCache.fromEnvironment().enabled(), off);
            }
            System.setProperty("faultline.cache", "on");
            assertTrue(SummaryCache.fromEnvironment().enabled());
        } finally {
            if (on == null) System.clearProperty("faultline.cache"); else System.setProperty("faultline.cache", on);
            if (dir == null) System.clearProperty("faultline.cacheDir"); else System.setProperty("faultline.cacheDir", dir);
        }
    }

    @Test
    void cacheCommandShowsClearsAndPrunes() throws IOException {
        String dir = System.getProperty("faultline.cacheDir");
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            System.setProperty("faultline.cacheDir", tmp.toString());
            CodeIndex.build(corpus(), null, false, SummaryCache.at(tmp, 1L << 30));
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(0, Main.cacheCommand(new String[]{"cache"}));
            assertEquals(0, Main.cacheCommand(new String[]{"cache", "prune"}));
            assertEquals(0, Main.cacheCommand(new String[]{"cache", "clear"}));
            assertEquals(0, Main.cacheCommand(new String[]{"cache", "info"}));
            assertEquals(64, Main.cacheCommand(new String[]{"cache", "bogus"}));
        } finally {
            System.setOut(out);
            if (dir == null) System.clearProperty("faultline.cacheDir"); else System.setProperty("faultline.cacheDir", dir);
        }
        String text = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains(SummaryCache.buildKey() + " (this build): "), text);
        assertTrue(text.contains("deleted "), text);
        assertTrue(text.trim().endsWith("empty"), "info after clear: " + text);
        assertFalse(Files.exists(tmp.resolve("summaries")));
    }

    /** whole reports (sample repo, branch mode) with the default cache cold, warm and disabled */
    @Test
    void reportsAreTheSameColdWarmAndDisabled() throws Exception {
        Path script = Path.of("scripts/create-sample.sh").toAbsolutePath();
        assumeTrue(script.toFile().exists());
        Path repo = tmp.resolve("sample");
        Process p = new ProcessBuilder("bash", script.toString(), repo.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assumeTrue(p.waitFor() == 0, "sample creation failed: " + out);
        Path dir = tmp.resolve("cache");
        List<String> reports = new ArrayList<>();
        for (SummaryCache c : List.of(SummaryCache.disabled(), SummaryCache.at(dir, 1L << 30), SummaryCache.at(dir, 1L << 30))) {
            SummaryCache.setDefault(c);
            AstCache.clear();
            Options o = new Options();
            o.repo = repo;
            o.ours = "ours";
            o.theirs = "theirs";
            o.serviceDir = "order-service";
            o.useMaven = false;
            Report r = new Analyzer(o).runFromGit();
            r.generatedAt = null;
            reports.add(Main.toJson(r));
        }
        assertTrue(reports.get(0).contains("MISSING_DEPENDENCY"));
        assertEquals(reports.get(0), reports.get(1), "cold cache");
        assertEquals(reports.get(0), reports.get(2), "warm cache");
    }
}
