package com.branchdiff.analysis;

import com.branchdiff.git.SiblingModules;
import com.branchdiff.model.Snapshot;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A throw-away copy of one snapshot's files in a temp directory (NOT a git worktree; the user's repo is untouched),
 * used for JavaParserTypeSolver and for {@code mvn dependency:build-classpath}.
 */
public final class Workspace implements AutoCloseable {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** entries per cache (parsed files, parsed directories, found types) of each source root's type solver */
    static final long SOLVER_CACHE_SIZE = Long.getLong("faultline.solverCacheSize", 64);

    public final Snapshot snapshot;
    public final Path root;
    public final List<Path> jars = new ArrayList<>();
    public String classpathStatus = "not attempted";
    public boolean classpathOk = false;
    private final boolean keep;

    public Workspace(Snapshot snapshot, Path parent, boolean keep) {
        this.snapshot = snapshot;
        this.keep = keep;
        try {
            this.root = Files.createDirectories(parent.resolve(snapshot.label()));
            Map<String, String> all = new LinkedHashMap<>(snapshot.siblingFiles());
            all.putAll(snapshot.files());
            for (var e : all.entrySet()) {
                Path p = root.resolve(e.getKey()).normalize();
                if (!p.startsWith(root)) continue;
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path serviceRoot() {
        return snapshot.serviceDir().isEmpty() ? root : root.resolve(snapshot.serviceDir());
    }

    /** Source roots derived from the package declarations (works for any layout, multi-module too). */
    public Set<Path> sourceRoots(boolean includeTests) {
        Set<Path> roots = new LinkedHashSet<>();
        // service roots first: on a FQN clash the CombinedTypeSolver then prefers the service's own class
        List<Map.Entry<String, String>> entries = new ArrayList<>(snapshot.files().entrySet());
        entries.addAll(snapshot.siblingFiles().entrySet());
        for (var e : entries) {
            String path = e.getKey();
            if (!path.endsWith(".java")) continue;
            if (!includeTests && (path.contains("/src/test/") || path.startsWith("src/test/"))) continue;
            Matcher m = PACKAGE.matcher(e.getValue());
            String pkgPath = m.find() ? m.group(1).replace('.', '/') : "";
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            if (pkgPath.isEmpty()) roots.add(root.resolve(dir));
            else if (dir.endsWith(pkgPath)) roots.add(root.resolve(dir.substring(0, dir.length() - pkgPath.length())));
        }
        return roots;
    }

    /** Runs {@code mvn dependency:build-classpath}; failures are recorded, never thrown. */
    public void resolveClasspath(String mvnCmd, int timeoutSeconds, boolean verbose) {
        Path svc = serviceRoot();
        if (!Files.exists(svc.resolve("pom.xml"))) {
            classpathStatus = "no pom.xml in service dir";
            return;
        }
        stripSiblingDependencies(svc.resolve("pom.xml"));
        String outName = "target/.branchdiff-classpath.txt";
        List<String> cmd = new ArrayList<>(List.of(mvnCmd, "-B", "-q", "-fae", "dependency:build-classpath",
                "-Dmdep.outputFile=" + outName, "-Dmdep.includeScope=test"));
        try {
            Path log = Files.createTempFile("branchdiff-mvn-" + snapshot.label(), ".log");
            Process p = new ProcessBuilder(cmd).directory(svc.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                classpathStatus = "mvn timed out after " + timeoutSeconds + "s";
            }
            Set<Path> found = new LinkedHashSet<>();
            try (Stream<Path> s = Files.walk(svc)) {
                s.filter(f -> f.getFileName().toString().equals(".branchdiff-classpath.txt")).forEach(f -> {
                    try {
                        for (String entry : Files.readString(f).trim().split(java.io.File.pathSeparator)) {
                            if (!entry.isBlank() && entry.endsWith(".jar") && Files.exists(Path.of(entry))) found.add(Path.of(entry));
                        }
                    } catch (IOException ignored) {
                        // skip unreadable file
                    }
                });
            }
            jars.addAll(found);
            if (done && p.exitValue() == 0) {
                Files.deleteIfExists(log);
                classpathOk = true;
                classpathStatus = "ok (" + jars.size() + " jars)";
            } else if (done) {
                String tail = tail(log, 15);
                classpathStatus = "mvn exit " + p.exitValue() + (jars.isEmpty() ? "" : ", partial: " + jars.size() + " jars")
                        + (verbose ? "\n" + tail : "") + "; log: " + log;
                classpathOk = !jars.isEmpty();
            }
        } catch (IOException e) {
            classpathStatus = "cannot run '" + mvnCmd + "': " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            classpathStatus = "interrupted";
        }
    }

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>\\s*", Pattern.DOTALL);
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");

    /**
     * Sibling modules are usually not installed in the local Maven repository, so a dependency on them would make
     * {@code dependency:build-classpath} fail as a whole. Their sources are already given to the symbol solver, so
     * the dependency is removed from the TEMPORARY copy of the service pom (the repository is never touched).
     * Third-party dependencies that only the sibling declares are not added (see README limitations).
     */
    void stripSiblingDependencies(Path pom) {
        Map<String, String> sibling = new LinkedHashMap<>(); // artifactId -> groupId
        for (var e : snapshot.siblingFiles().entrySet()) {
            if (!e.getKey().endsWith("pom.xml")) continue;
            SiblingModules.Pom p = SiblingModules.parse(e.getKey(), e.getValue());
            if (p != null) sibling.put(p.artifactId(), p.groupId());
        }
        if (sibling.isEmpty()) return;
        try {
            String xml = Files.readString(pom);
            StringBuilder out = new StringBuilder();
            Matcher m = DEPENDENCY.matcher(xml);
            int removed = 0;
            while (m.find()) {
                Matcher a = ARTIFACT.matcher(m.group(1));
                Matcher g = GROUP.matcher(m.group(1));
                String art = a.find() ? a.group(1) : null;
                String grp = g.find() ? g.group(1) : null;
                boolean isSibling = art != null && sibling.containsKey(art)
                        && (grp == null || grp.contains("${") || grp.equals(sibling.get(art)) || sibling.get(art).equals("?"));
                m.appendReplacement(out, isSibling ? "" : Matcher.quoteReplacement(m.group()));
                if (isSibling) removed++;
            }
            m.appendTail(out);
            if (removed > 0) Files.writeString(pom, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // keep the pom as is
        }
    }

    private static String tail(Path log, int n) {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    public TypeSolver typeSolver(boolean includeTests, List<String> warnings) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(true));
        // bounded caches: by default every parsed file (with its tokens) and every parsed package directory stays in
        // memory until the solver is dropped, which on a large project is as large as the project's ASTs themselves
        for (Path r : sourceRoots(includeTests)) {
            solver.add(new JavaParserTypeSolver(r, new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE), SOLVER_CACHE_SIZE));
        }
        for (Path jar : jars) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (Exception | Error e) {
                warnings.add("[" + snapshot.label() + "] skipped jar " + jar.getFileName() + ": " + e.getMessage());
            }
        }
        return solver;
    }

    @Override
    public void close() {
        if (keep) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
