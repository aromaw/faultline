package com.branchdiff;

import com.branchdiff.analysis.DependencyAnalyzer;
import com.branchdiff.diff.ChangeSet;
import com.branchdiff.index.CodeIndex;
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

class AnalyzersTest {
    static final String UTIL = "src/main/java/a/util/Util.java";
    static final String CONST = "src/main/java/a/util/Consts.java";
    static final String SVC = "src/main/java/a/svc/Svc.java";

    static final Snapshot BASE = snap("base",
            UTIL, """
                    package a.util;
                    public final class Util {
                        public static int twice(int x) { return 2 * x; }
                        public static String gen(Long id) { return "ORD-" + String.format("%010d", id) + "/" + id.hashCode(); }
                    }
                    """,
            CONST, """
                    package a.util;
                    public final class Consts {
                        public static final String CURRENCY = "CNY";
                        public static final int MAX = 5;
                    }
                    """,
            SVC, """
                    package a.svc;
                    import a.util.Util;
                    public class Svc {
                        public int run(int x) { return Util.twice(x); }
                    }
                    """);

    private static boolean has(Report r, Category c, String symbolPart, String origin) {
        return r.findings.stream().anyMatch(f -> f.category == c && f.symbol.contains(symbolPart)
                && (origin == null || origin.equals(f.origin)));
    }

    @Test
    void dependencyClosure() {
        Snapshot theirs = with(BASE, "theirs",
                UTIL, """
                        package a.util;
                        public final class Util {
                            public static int twice(int x) { return 2 * x; }
                            public static int thrice(int x) { return 3 * x; }
                            public static String gen(Long id) { return "ORD-" + String.format("%010d", id) + "/" + id.hashCode(); }
                        }
                        """,
                SVC, """
                        package a.svc;
                        import a.util.Consts;
                        import a.util.Util;
                        public class Svc {
                            public int run(int x) { return Util.twice(x); }
                            public String added(int x) {
                                return Util.thrice(x) + Consts.CURRENCY + Consts.MAX + Util.twice(1);
                            }
                        }
                        """);
        Snapshot ours = with(BASE, "ours",
                CONST, """
                        package a.util;
                        public final class Consts {
                            public static final int MAX = 5;
                        }
                        """);
        Report r = analyze(BASE, ours, theirs);
        List<Finding> deps = of(r, Category.MISSING_DEPENDENCY);
        assertTrue(has(r, Category.MISSING_DEPENDENCY, "Util#thrice(int)", "THEIRS_NEW"), dump(r));
        assertTrue(has(r, Category.MISSING_DEPENDENCY, "Consts.CURRENCY", "OURS_REMOVED"), dump(r));
        assertEquals(2, deps.size(), "twice()/MAX exist in ours and must not be reported:\n" + dump(r));
        assertTrue(deps.stream().allMatch(f -> f.confidence == Finding.Confidence.CERTAIN), dump(r));
        Finding thrice = deps.stream().filter(f -> f.symbol.contains("thrice")).findFirst().orElseThrow();
        assertEquals("src/main/java/a/svc/Svc.java:7", thrice.neededBy.get(0).location());
    }

    @Test
    void inconsistentSignatureIsReported() {
        Snapshot theirs = with(BASE, "theirs",
                SVC, """
                        package a.svc;
                        import a.util.Util;
                        public class Svc {
                            public int run(int x) { return Util.twice(x); }
                            public int added() { return Util.twice(1); }
                        }
                        """);
        Snapshot ours = with(BASE, "ours",
                UTIL, """
                        package a.util;
                        public final class Util {
                            public static long twice(long x) { return 2 * x; }
                            public static String gen(Long id) { return "ORD-" + String.format("%010d", id) + "/" + id.hashCode(); }
                        }
                        """);
        Report r = analyze(BASE, ours, theirs);
        Finding f = of(r, Category.MISSING_DEPENDENCY).stream().filter(x -> x.symbol.contains("twice")).findFirst().orElseThrow();
        assertEquals("INCONSISTENT", f.status);
        assertEquals("OURS_CHANGED", f.origin);
    }

    @Test
    void fallbackWithoutSymbolSolverIsUncertainNotCrash() {
        Snapshot theirs = with(BASE, "theirs",
                UTIL, """
                        package a.util;
                        public final class Util {
                            public static int twice(int x) { return 2 * x; }
                            public static int thrice(int x) { return 3 * x; }
                            public static String gen(Long id) { return "ORD-" + String.format("%010d", id) + "/" + id.hashCode(); }
                        }
                        """,
                SVC, """
                        package a.svc;
                        import a.util.Util;
                        public class Svc {
                            public int run(int x) { return Util.twice(x) + Util.thrice(x) + unknownLib().foo(); }
                        }
                        """);
        CodeIndex b = CodeIndex.build(BASE, null, false);
        CodeIndex o = CodeIndex.build(BASE, null, false);
        CodeIndex t = CodeIndex.build(theirs, null, false);
        DependencyAnalyzer dep = new DependencyAnalyzer(b, o, t);
        List<Finding> fs = dep.analyze(ChangeSet.compute("theirs", b, t));
        assertEquals(1, fs.size(), fs.toString());
        assertEquals(Finding.Confidence.UNCERTAIN, fs.get(0).confidence);
        assertTrue(fs.get(0).symbol.contains("thrice"));
        assertTrue(dep.stats.callsFallback > 0);
    }

    // ---------------------------------------------------------------- spring

    static final String APP_YML = "src/main/resources/application.yml";
    static final String IFACE = "src/main/java/a/svc/Api.java";
    static final String IMPL = "src/main/java/a/svc/ApiImpl.java";

    static final Snapshot SPRING_BASE = snap("base",
            IFACE, "package a.svc; public interface Api { String a(); }",
            IMPL, """
                    package a.svc;
                    import org.springframework.stereotype.Service;
                    @Service("apiImpl")
                    public class ApiImpl implements Api { public String a() { return "a"; } }
                    """,
            APP_YML, "app:\n  name: x\n",
            "src/main/resources/application-dev.yml", "app:\n  dev-only: 1\n");

    @Test
    void springGaps() {
        Snapshot theirs = with(SPRING_BASE, "theirs",
                IFACE, "package a.svc; public interface Api { String a(); String b(int x); default String c() { return \"\"; } }",
                IMPL, """
                        package a.svc;
                        import org.springframework.stereotype.Service;
                        @Service("apiImpl")
                        public class ApiImpl implements Api { public String a() { return "a"; } public String b(int x) { return "b"; } }
                        """,
                "src/main/java/a/svc/NewBean.java", """
                        package a.svc;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class NewBean { }
                        """,
                "src/main/java/a/svc/User.java", """
                        package a.svc;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.beans.factory.annotation.Value;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class User {
                            @Autowired private NewBean newBean;
                            @Autowired @Qualifier("apiImpl") private Api existing;
                            @Autowired @Qualifier("ghost") private Api ghost;
                            @Value("${app.new-key}") private String newKey;
                            @Value("${app.with-default:42}") private int withDefault;
                            @Value("${app.dev-only}") private int devOnly;
                            @Value("${app.name}") private String name;
                        }
                        """,
                APP_YML, "app:\n  name: x\n  new-key: v\n");
        Snapshot ours = with(SPRING_BASE, "ours",
                "src/main/java/a/svc/OursOnlyImpl.java", """
                        package a.svc;
                        public class OursOnlyImpl implements Api { public String a() { return "o"; } }
                        """);
        Report r = analyze(SPRING_BASE, ours, theirs);
        String d = dump(r);
        assertTrue(has(r, Category.SPRING_BEAN, "a.svc.NewBean", null), d);
        assertTrue(r.findings.stream().anyMatch(f -> f.category == Category.SPRING_BEAN && f.symbol.contains("ghost")
                && f.confidence == Finding.Confidence.UNCERTAIN), d);
        assertTrue(r.findings.stream().noneMatch(f -> f.symbol.contains("apiImpl")), d);
        assertTrue(has(r, Category.SPRING_CONFIG, "app.new-key", "THEIRS_NEW"), d);
        assertTrue(r.findings.stream().noneMatch(f -> f.symbol.contains("with-default") || f.symbol.contains("dev-only")
                || f.symbol.equals("app.name")), d);
        Finding oursOnly = r.findings.stream().filter(f -> f.category == Category.INTERFACE_IMPL && f.symbol.contains("OursOnlyImpl")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.HIGH, oursOnly.severity);
        assertTrue(r.findings.stream().noneMatch(f -> f.category == Category.INTERFACE_IMPL && f.symbol.contains("#c()")), "default method: " + d);
        assertTrue(r.findings.stream().anyMatch(f -> f.category == Category.INTERFACE_IMPL && f.symbol.startsWith("a.svc.ApiImpl ")
                && f.severity == Finding.Severity.LOW), d);
    }

    @Test
    void mybatisMapperMethodNeedsXmlStatement() {
        String mapper = "src/main/java/a/mapper/M.java";
        String xml = "src/main/resources/mapper/M.xml";
        Snapshot base = snap("base",
                mapper, "package a.mapper; import org.apache.ibatis.annotations.Mapper; @Mapper public interface M { int one(); }",
                xml, "<mapper namespace=\"a.mapper.M\"><select id=\"one\">select 1</select></mapper>");
        Snapshot theirs = with(base, "theirs",
                mapper, """
                        package a.mapper;
                        import org.apache.ibatis.annotations.Mapper;
                        import org.apache.ibatis.annotations.Select;
                        @Mapper public interface M {
                            int one();
                            int two();
                            @Select("select 3") int three();
                        }
                        """,
                xml, "<mapper namespace=\"a.mapper.M\"><select id=\"one\">select 1</select><select id=\"two\">select 2</select></mapper>");
        Report r = analyze(base, base, theirs);
        List<Finding> mb = of(r, Category.MYBATIS);
        assertEquals(1, mb.size(), dump(r));
        assertEquals("a.mapper.M#two", mb.get(0).symbol);
        assertEquals(Finding.Confidence.CERTAIN, mb.get(0).confidence);
    }

    // ---------------------------------------------------------------- impact & both-modified

    @Test
    void renamedMethodStillCalledByOursNewCode() {
        Snapshot theirs = with(BASE, "theirs",
                UTIL, """
                        package a.util;
                        public final class Util {
                            public static int twice(int x) { return 2 * x; }
                            public static String nextNo(Long id) { return "ORD-" + String.format("%010d", id) + "/" + id.hashCode(); }
                        }
                        """);
        Snapshot ours = with(BASE, "ours",
                "src/main/java/a/svc/Export.java", """
                        package a.svc;
                        import a.util.Util;
                        public class Export {
                            public String line(Long id) { return Util.gen(id); }
                        }
                        """);
        Report r = analyze(BASE, ours, theirs);
        List<Finding> imp = of(r, Category.SIGNATURE_IMPACT);
        assertEquals(1, imp.size(), dump(r));
        assertEquals("RENAMED", imp.get(0).kind);
        assertEquals(Finding.Severity.HIGH, imp.get(0).severity);
        assertEquals("src/main/java/a/svc/Export.java:4", imp.get(0).oursLocation);
    }

    @Test
    void bothModifiedButIdenticalChangesAreNotFlagged() {
        String a = """
                package a.svc;
                public class P {
                    public int price(int x) {
                        int v = x;
                        if (v < 0) {
                            return 0;
                        }
                        return v;
                    }
                    public int same(int x) { return x; }
                }
                """;
        Snapshot base = snap("base", "src/main/java/a/svc/P.java", a);
        Snapshot ours = with(base, "ours", "src/main/java/a/svc/P.java",
                a.replace("int v = x;", "int v = x * 2;").replace("return x; }", "return x + 1; }"));
        Snapshot theirs = with(base, "theirs", "src/main/java/a/svc/P.java",
                a.replace("return v;", "return v * 2;").replace("return x; }", "return x + 1; }"));
        Report r = analyze(base, ours, theirs);
        List<Finding> both = of(r, Category.BOTH_MODIFIED);
        assertEquals(1, both.size(), dump(r));
        assertTrue(both.get(0).symbol.endsWith("price(int)"));
        assertTrue(String.valueOf(both.get(0).details.get("gitTextualConflict")).startsWith("NO"), both.get(0).details.toString());
        assertEquals(1, r.summary.get("identicalChangesOnBothSides"));
    }
}
