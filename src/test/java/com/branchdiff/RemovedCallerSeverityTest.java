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
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A caller that ours left untouched and theirs deleted disappears in a whole-branch merge, so it is LOW. */
class RemovedCallerSeverityTest {
    static final String UTIL = "src/main/java/a/util/Util.java";
    static final String SVC = "src/main/java/a/svc/Svc.java";

    static final Snapshot BASE = snap("base",
            UTIL, "package a.util; public class Util { public static int old() { return 1; } }",
            SVC, """
                    package a.svc;
                    import a.util.Util;
                    public class Svc {
                        public int useOld() { return Util.old(); }
                        public int keep() { return 2; }
                    }
                    """);

    @Test
    void callerRemovedByTheirsAndUntouchedByOursIsLow() {
        Snapshot theirs = with(BASE, "theirs",
                UTIL, "package a.util; public class Util { public static int fresh() { return 1; } }",
                SVC, """
                        package a.svc;
                        public class Svc {
                            public int keep() { return 2; }
                        }
                        """);
        Report r = analyze(BASE, BASE, theirs);
        List<Finding> sig = of(r, Category.SIGNATURE_IMPACT);
        assertFalse(sig.isEmpty(), dump(r));
        for (Finding f : sig) assertEquals(Finding.Severity.LOW, f.severity, dump(r));
    }
}
