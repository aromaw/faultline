package com.branchdiff;

import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Report;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.branchdiff.TestSupport.analyze;
import static com.branchdiff.TestSupport.dump;
import static com.branchdiff.TestSupport.of;
import static com.branchdiff.TestSupport.snap;
import static com.branchdiff.TestSupport.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for false positives observed on a real multi-module project (RuoYi-Vue branches). */
class RealProjectRegressionTest {
    static final String MAP = "src/main/java/a/util/MyMap.java";
    static final String LIBX = "src/main/java/a/util/Strs.java";
    static final String SVC = "src/main/java/a/svc/Svc.java";

    // ---------------------------------------------------------------- methods inherited from library supertypes

    static final Snapshot INHERIT_BASE = snap("base",
            MAP, "package a.util; public class MyMap extends java.util.HashMap<String, String> { }",
            LIBX, "package a.util; public class Strs extends org.lib.LibStrings { }",
            SVC, """
                    package a.svc;
                    import a.util.MyMap;
                    import a.util.Strs;
                    public class Svc {
                        public boolean has(MyMap m) { return false; }
                        public boolean eq(String a, String b) { return false; }
                    }
                    """);

    @Test
    void methodRedeclaredByTheirsButInheritedFromLibrarySupertypeInOursIsNotReported() {
        // theirs redeclares containsKey/same in the project class; ours still inherits containsKey from HashMap
        Snapshot theirs = with(INHERIT_BASE, "theirs",
                MAP, """
                        package a.util;
                        public class MyMap extends java.util.HashMap<String, String> {
                            @Override public boolean containsKey(Object k) { return super.containsKey(k); }
                        }
                        """,
                SVC, """
                        package a.svc;
                        import a.util.MyMap;
                        import a.util.Strs;
                        public class Svc {
                            public boolean has(MyMap m) { return m.containsKey("x"); }
                            public boolean eq(String a, String b) { return false; }
                        }
                        """);
        Report r = analyze(INHERIT_BASE, INHERIT_BASE, theirs);
        assertTrue(of(r, Category.MISSING_DEPENDENCY).isEmpty(), dump(r));
    }

    @Test
    void methodPossiblyInheritedFromUnresolvableLibrarySupertypeIsUncertain() {
        Snapshot theirs = with(INHERIT_BASE, "theirs",
                LIBX, """
                        package a.util;
                        public class Strs extends org.lib.LibStrings {
                            public static boolean same(CharSequence a, CharSequence b) { return a == null ? b == null : a.equals(b); }
                        }
                        """,
                SVC, """
                        package a.svc;
                        import a.util.MyMap;
                        import a.util.Strs;
                        public class Svc {
                            public boolean has(MyMap m) { return false; }
                            public boolean eq(String a, String b) { return Strs.same(a, b); }
                        }
                        """);
        Report r = analyze(INHERIT_BASE, INHERIT_BASE, theirs);
        List<Finding> fs = of(r, Category.MISSING_DEPENDENCY);
        assertEquals(1, fs.size(), dump(r));
        assertEquals(Finding.Confidence.UNCERTAIN, fs.get(0).confidence);
        assertTrue(fs.get(0).symbol.contains("Strs#same"), dump(r));
        assertTrue(String.valueOf(fs.get(0).details.get("inheritance")).contains("org.lib.LibStrings"), dump(r));
    }

    // ---------------------------------------------------------------- @Value references removed by ours

    static final String CFG = "src/main/java/a/svc/DocConfig.java";
    static final String YML = "src/main/resources/application.yml";
    static final String CFG_WITH_VALUE = """
            package a.svc;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class DocConfig {
                @Value("${doc.enabled}")
                private boolean enabled;
                public boolean on() { return enabled; }
            }
            """;

    @Test
    void keyRemovedByOursTogetherWithItsReferenceIsNotReported() {
        Snapshot base = snap("base", CFG, CFG_WITH_VALUE, YML, "doc:\n  enabled: true\n");
        // ours rewrote the class without the @Value field and dropped the key; theirs did not touch either
        Snapshot ours = with(base, "ours",
                CFG, """
                        package a.svc;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        public class DocConfig {
                            public boolean on() { return true; }
                        }
                        """,
                YML, "other: 1\n");
        Report r = analyze(base, ours, base);
        assertTrue(of(r, Category.SPRING_CONFIG).isEmpty(), dump(r));
    }

    @Test
    void keyRemovedByOursWhileOursStillReferencesItIsStillReported() {
        Snapshot base = snap("base", CFG, CFG_WITH_VALUE, YML, "doc:\n  enabled: true\n");
        Snapshot ours = with(base, "ours", YML, "other: 1\n");
        Report r = analyze(base, ours, base);
        List<Finding> fs = of(r, Category.SPRING_CONFIG);
        assertEquals(1, fs.size(), dump(r));
        assertEquals("OURS_REMOVED", fs.get(0).origin);
    }

    @Test
    void sameNewReferenceOnBothSidesWithoutConfigOnEitherSideIsNotReported() {
        Snapshot base = snap("base", YML, "x: 1\n");
        Snapshot both = with(base, "ours", CFG, CFG_WITH_VALUE);
        Report r = analyze(base, both, with(both, "theirs"));
        assertTrue(of(r, Category.SPRING_CONFIG).isEmpty(), dump(r));
    }

    // ---------------------------------------------------------------- same declaration change on both sides

    static final String XL = "src/main/java/a/util/Xl.java";

    @Test
    void returnTypeChangedIdenticallyOnBothSidesIsNotReportedAsOldSignatureUse() {
        Snapshot base = snap("base", XL, """
                package a.util;
                import java.util.Collection;
                import java.util.List;
                public class Xl {
                    public Collection<?> count(Object o) { return List.of(o); }
                    public boolean any(Object o) { return count(o).size() > 0; }
                }
                """);
        // ours: same return type change as theirs, plus its own unrelated edit of the caller
        Snapshot ours = with(base, "ours", XL, """
                package a.util;
                public class Xl {
                    public int count(Object o) { return o == null ? 0 : 1; }
                    public boolean any(Object o) { return o != null && count(o) > 0; }
                }
                """);
        Snapshot theirs = with(base, "theirs", XL, """
                package a.util;
                public class Xl {
                    public int count(Object o) { return o == null ? 0 : 1; }
                    public boolean any(Object o) { return count(o) > 0; }
                }
                """);
        Report r = analyze(base, ours, theirs);
        assertTrue(of(r, Category.SIGNATURE_IMPACT).isEmpty(), dump(r));
    }

    @Test
    void returnTypeChangedOnlyByTheirsIsStillReported() {
        Snapshot base = snap("base", XL, """
                package a.util;
                import java.util.Collection;
                import java.util.List;
                public class Xl {
                    public Collection<?> count(Object o) { return List.of(o); }
                    public boolean any(Object o) { return count(o).size() > 0; }
                }
                """);
        Snapshot ours = with(base, "ours", XL, """
                package a.util;
                import java.util.Collection;
                import java.util.List;
                public class Xl {
                    public Collection<?> count(Object o) { return List.of(o); }
                    public boolean any(Object o) { return o != null && count(o).size() > 0; }
                }
                """);
        Snapshot theirs = with(base, "theirs", XL, """
                package a.util;
                public class Xl {
                    public int count(Object o) { return o == null ? 0 : 1; }
                    public boolean any(Object o) { return count(o) > 0; }
                }
                """);
        Report r = analyze(base, ours, theirs);
        List<Finding> fs = of(r, Category.SIGNATURE_IMPACT);
        assertEquals(1, fs.size(), dump(r));
        assertEquals(Finding.Severity.HIGH, fs.get(0).severity);
    }
}
