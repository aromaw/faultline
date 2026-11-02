package com.branchdiff;

import com.branchdiff.git.SiblingModules;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.report.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.branchdiff.TestSupport.analyze;
import static com.branchdiff.TestSupport.dump;
import static com.branchdiff.TestSupport.of;
import static com.branchdiff.TestSupport.snap;
import static com.branchdiff.TestSupport.with;
import static com.branchdiff.TestSupport.withSiblings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldImpactAndSiblingsTest {
    static final String CONST = "src/main/java/a/c/Consts.java";
    static final String COLOR = "src/main/java/a/c/Color.java";
    static final String SVC = "src/main/java/a/s/Svc.java";
    static final String NEWSVC = "src/main/java/a/s/NewSvc.java";

    static final Snapshot BASE = snap("base",
            CONST, """
                    package a.c;
                    public final class Consts {
                        public static final String SEP = ",";
                        public static final String OLD = "x";
                        public static final int UNUSED = 1;
                    }
                    """,
            COLOR, """
                    package a.c;
                    public enum Color { RED, GREEN, BLUE }
                    """,
            SVC, """
                    package a.s;
                    import a.c.Consts;
                    public class Svc {
                        private final int limit = 10;
                        public String line(String a, String b) { return a + Consts.SEP + b; }
                        public int lim() { return limit; }
                    }
                    """);

    static final Snapshot THEIRS = with(BASE, "theirs",
            CONST, """
                    package a.c;
                    public final class Consts {
                        public static final String SEPARATOR = ",";
                    }
                    """,
            COLOR, """
                    package a.c;
                    public enum Color { RED, GREEN }
                    """,
            SVC, """
                    package a.s;
                    import a.c.Consts;
                    public class Svc {
                        private final int maxLimit = 10;
                        public String line(String a, String b) { return a + Consts.SEPARATOR + b; }
                        public int lim() { return maxLimit; }
                    }
                    """);

    static List<Finding> fieldImpact(Report r) {
        return of(r, Category.SIGNATURE_IMPACT).stream().filter(f -> f.status.equals("STILL_USES_REMOVED_FIELD")).toList();
    }

    @Test
    void removedAndRenamedFieldsAreTracedBackToOursReferences() {
        Snapshot ours = with(BASE, "ours",
                NEWSVC, """
                        package a.s;
                        import a.c.Color;
                        import a.c.Consts;
                        import static a.c.Consts.OLD;
                        public class NewSvc {
                            static final String PREFIX = Consts.SEP + "p";
                            public boolean blue(Color c) { return c == Color.BLUE; }
                            public String old() { return OLD; }
                        }
                        """,
                SVC, """
                        package a.s;
                        import a.c.Consts;
                        public class Svc {
                            private final int limit = 10;
                            public String line(String a, String b) { return a + Consts.SEP + b; }
                            public int lim() { return limit; }
                            public int twice() { return 2 * limit; }
                        }
                        """);
        Report r = analyze(BASE, ours, THEIRS);
        List<Finding> fs = fieldImpact(r);
        String d = dump(r);
        // ours-new code -> HIGH
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.c.Consts.SEP") && f.kind.equals("CONSTANT_RENAMED")
                && f.severity == Severity.HIGH && f.oursLocation.equals(NEWSVC + ":6") && f.message.contains("-> SEPARATOR")), d);
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.c.Color.BLUE") && f.kind.equals("ENUM_CONSTANT_REMOVED")
                && f.severity == Severity.HIGH && f.oursLocation.equals(NEWSVC + ":7")), d);
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.c.Consts.OLD") && f.kind.equals("CONSTANT_REMOVED")
                && f.severity == Severity.HIGH && f.oursLocation.equals(NEWSVC + ":8")), d);
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.s.Svc.limit") && f.kind.equals("FIELD_RENAMED")
                && f.severity == Severity.HIGH && f.oursLocation.equals(SVC + ":7")), d);
        // unchanged in ours, theirs updated it -> LOW
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.c.Consts.SEP") && f.severity == Severity.LOW
                && f.oursLocation.equals(SVC + ":5")), d);
        assertTrue(fs.stream().anyMatch(f -> f.symbol.equals("a.s.Svc.limit") && f.severity == Severity.LOW
                && f.oursLocation.equals(SVC + ":6")), d);
        // removed but never referenced by ours
        assertTrue(fs.stream().noneMatch(f -> f.symbol.contains("UNUSED")), d);
        assertTrue(fs.stream().noneMatch(Finding::uncertain), d);
    }

    @Test
    void sameNameFromAnotherDeclarationOrDroppedByOursIsNotReported() {
        Snapshot ours = with(BASE, "ours",
                CONST, """
                        package a.c;
                        public final class Consts {
                            public static final String SEP = ",";
                        }
                        """,
                NEWSVC, """
                        package a.s;
                        public class NewSvc {
                            static final String OLD = "mine";
                            public String a() { String UNUSED = "local"; return OLD + UNUSED; }
                        }
                        """);
        Report r = analyze(BASE, ours, THEIRS);
        List<Finding> fs = fieldImpact(r);
        // OLD / UNUSED were removed by ours too, and NewSvc's names are its own declarations
        assertTrue(fs.stream().noneMatch(f -> f.symbol.contains("OLD") || f.symbol.contains("UNUSED")), dump(r));
    }

    // ------------------------------------------------------------------ siblings

    static final String COMMON = "common/src/main/java/x/common/Text.java";
    static final String USE = "svc/src/main/java/a/Use.java";

    @Test
    void siblingModuleMethodsAreCheckedButNeverReportedThemselves() {
        Snapshot base0 = new Snapshot("base", "base", "b", "svc", Map.of(USE, """
                package a;
                public class Use { public String x(String s) { return s; } }
                """));
        Snapshot base = withSiblings(base0, "base", COMMON, """
                package x.common;
                public final class Text { public static String trim(String s) { return s.trim(); } }
                """);
        Snapshot theirs = withSiblings(with(base, "theirs", USE, """
                package a;
                import x.common.Text;
                public class Use {
                    public String x(String s) { return Text.mask(Text.trim(s)); }
                }
                """), "theirs", COMMON, """
                package x.common;
                public final class Text {
                    public static String trim(String s) { return s.trim(); }
                    public static String mask(String s) { return Text.stars() + s; }
                    public static String stars() { return "***"; }
                }
                """);
        Snapshot ours = with(base, "ours");
        Report r = analyze(base, ours, theirs);
        List<Finding> md = of(r, Category.MISSING_DEPENDENCY);
        assertTrue(md.stream().anyMatch(f -> f.symbol.equals("x.common.Text#mask(String)") && f.origin.equals("THEIRS_NEW")
                && f.theirsLocation.startsWith(COMMON)), dump(r));
        assertTrue(md.stream().noneMatch(f -> f.symbol.contains("trim") || f.symbol.contains("stars")), dump(r));
        // sibling changes are not part of the reported change sets
        assertTrue(r.changeSets.get("theirs").stream().noneMatch(m -> m.get("method").contains("x.common")), String.valueOf(r.changeSets));

        // the same repo without siblings: Text is an external library, nothing to report
        Snapshot noSib = new Snapshot("theirs", "theirs", "t", "svc", theirs.files());
        Report r2 = analyze(base0, new Snapshot("ours", "ours", "o", "svc", base0.files()), noSib);
        assertTrue(of(r2, Category.MISSING_DEPENDENCY).isEmpty(), dump(r2));
    }

    @Test
    void serviceClassWinsOverSiblingClassWithSameFqn() {
        Snapshot base = withSiblings(snap("base", "src/main/java/a/Dup.java", """
                package a;
                public class Dup { public int v() { return 1; } }
                """), "base", "common/src/main/java/a/Dup.java", """
                package a;
                public class Dup { public int w() { return 2; } }
                """);
        Report r = analyze(base, with(base, "ours"), with(base, "theirs"));
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("sibling class ignored") && w.contains("a.Dup")), r.warnings.toString());
        assertTrue(r.findings.isEmpty(), dump(r));
    }

    @Test
    void siblingSelection() {
        Set<String> paths = Set.of(
                "svc/src/main/java/a/A.java", "svc/sub/src/main/java/a/B.java",
                "common/src/main/java/c/C.java", "core/src/main/java/k/K.java",
                "other/src/main/java/o/O.java", "loose/src/main/java/l/L.java", "pom.xml");
        Map<String, String> poms = Map.of(
                "svc/pom.xml", pom("g", "svc", "<dependency><groupId>${project.groupId}</groupId><artifactId>common</artifactId></dependency>"
                        + "<dependency><groupId>g</groupId><artifactId>tests</artifactId><scope>test</scope></dependency>"),
                "common/pom.xml", pom("g", "common", "<dependency><groupId>g</groupId><artifactId>core</artifactId></dependency>"),
                "core/pom.xml", pom("g", "core", ""),
                "other/pom.xml", pom("g", "other", ""),
                "tests/pom.xml", pom("g", "tests", ""));
        assertEquals(Set.of("common", "core"), SiblingModules.select(paths, poms, "svc", SiblingModules.Mode.DEPS));
        assertEquals(Set.of("common", "core", "other", "loose"), SiblingModules.select(paths, poms, "svc", SiblingModules.Mode.ALL));
        assertEquals(SiblingModules.Mode.ALL, SiblingModules.Mode.parse("all"));
        assertThrows(IllegalArgumentException.class, () -> SiblingModules.Mode.parse("bogus"));
    }

    @Test
    void cliFlag() {
        assertEquals(SiblingModules.Mode.DEPS, Main.parse(new String[]{"--theirs", "t"}).siblings);
        assertEquals(SiblingModules.Mode.NONE, Main.parse(new String[]{"--theirs", "t", "--no-siblings"}).siblings);
        assertEquals(SiblingModules.Mode.ALL, Main.parse(new String[]{"--include-siblings", "all", "--theirs", "t"}).siblings);
        assertEquals(SiblingModules.Mode.NONE, Main.parse(new String[]{"--include-siblings=none", "--theirs", "t"}).siblings);
        assertEquals(SiblingModules.Mode.DEPS, Main.parse(new String[]{"--include-siblings", "--theirs", "t"}).siblings);
        assertNull(Main.parse(new String[]{"-h"}));
    }

    private static String pom(String g, String a, String deps) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>1</version><dependencies>" + deps + "</dependencies></project>";
    }
}
