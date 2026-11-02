package com.branchdiff;

import com.branchdiff.analysis.Analyzer;
import com.branchdiff.git.SiblingModules;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static com.branchdiff.TestSupport.dump;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Builds the sample repo with scripts/create-sample.sh (real git branches) and checks every planted gap is
 * reported and the planted non-gaps are not. Uses --no-maven so it runs offline.
 */
class SampleRepoEndToEndTest {
    @TempDir
    static Path tmp;
    static Report report;
    /** same sample with --include-siblings none / all */
    static Report noSiblings;
    static Report allSiblings;

    @BeforeAll
    static void build() throws Exception {
        Path script = Path.of("scripts/create-sample.sh").toAbsolutePath();
        assumeTrue(script.toFile().exists());
        Path repo = tmp.resolve("sample");
        Process p = new ProcessBuilder("bash", script.toString(), repo.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assumeTrue(p.waitFor() == 0, "sample creation failed (needs bash, git, python3): " + out);
        Options o = new Options();
        o.repo = repo;
        o.ours = "ours";
        o.theirs = "theirs";
        o.serviceDir = "order-service";
        o.useMaven = false;
        report = new Analyzer(o).runFromGit();
        o.siblings = SiblingModules.Mode.NONE;
        noSiblings = new Analyzer(o).runFromGit();
        o.siblings = SiblingModules.Mode.ALL;
        allSiblings = new Analyzer(o).runFromGit();
    }

    static void expect(String what, Predicate<Finding> p) {
        expect(report, what, p);
    }

    static void expect(Report r, String what, Predicate<Finding> p) {
        assertTrue(r.findings.stream().anyMatch(p), "missing expected finding: " + what + "\n" + dump(r));
    }

    static void expectNone(String what, Predicate<Finding> p) {
        expectNone(report, what, p);
    }

    static void expectNone(Report r, String what, Predicate<Finding> p) {
        List<Finding> bad = r.findings.stream().filter(p).toList();
        assertTrue(bad.isEmpty(), "false positive: " + what + " -> " + bad.stream().map(f -> f.symbol).toList());
    }

    static Predicate<Finding> fieldImpact(String symbol, String kind, Finding.Severity sev, String oursFile) {
        return x -> x.category == Category.SIGNATURE_IMPACT && x.status.equals("STILL_USES_REMOVED_FIELD")
                && x.symbol.equals(symbol) && x.kind.equals(kind) && x.severity == sev && x.oursLocation.contains(oursFile);
    }

    static final Predicate<Finding> G10A = f(Category.MISSING_DEPENDENCY, "com.example.common.text.TextUtils#maskPhone(String)")
            .and(x -> x.origin.equals("THEIRS_NEW") && x.theirsLocation.startsWith("common/"));
    static final Predicate<Finding> G10B = fieldImpact("com.example.common.CommonConstants.LEGACY_TENANT", "CONSTANT_REMOVED",
            Finding.Severity.HIGH, "OrderExportService");

    static Predicate<Finding> f(Category c, String symbolPart) {
        return x -> x.category == c && x.symbol.contains(symbolPart);
    }

    @Test
    void plantedGapsAreReported() {
        expect("G1a helper B only in theirs", f(Category.MISSING_DEPENDENCY, "MoneyUtils#roundHalfUp(BigDecimal)").and(x -> x.origin.equals("THEIRS_NEW")));
        expect("G1b new constant", f(Category.MISSING_DEPENDENCY, "OrderConstants.MAX_DISCOUNT_PERCENT"));
        expect("G1c constant removed by ours", f(Category.MISSING_DEPENDENCY, "OrderConstants.DEFAULT_CURRENCY")
                .and(x -> x.origin.equals("OURS_REMOVED") && x.severity == Finding.Severity.HIGH));
        expect("G2a new @Autowired bean", f(Category.SPRING_BEAN, "com.example.order.coupon.CouponService"));
        expect("G2c @Qualifier bean name", f(Category.SPRING_BEAN, "smsNotifier"));
        expect("G3a @Value key", f(Category.SPRING_CONFIG, "order.coupon.expire-days"));
        expect("G3b @ConfigurationProperties", f(Category.SPRING_CONFIG, "order.payment"));
        expect("G4 interface method missing in ours-only impl", f(Category.INTERFACE_IMPL, "CachedOrderService must implement")
                .and(x -> x.severity == Finding.Severity.HIGH));
        expect("G5 mybatis method + xml id", f(Category.MYBATIS, "OrderMapper#selectByCustomerId"));
        expect("G5 mapper method as dependency", f(Category.MISSING_DEPENDENCY, "OrderMapper#selectByCustomerId(Long)"));
        expect("G6a renamed method still called by ours' new code", f(Category.SIGNATURE_IMPACT, "OrderNumberGenerator#generate(Long)")
                .and(x -> x.severity == Finding.Severity.HIGH && x.oursLocation.contains("OrderExportService")));
        expect("G6b changed signature still called by ours' new code", f(Category.SIGNATURE_IMPACT, "PricingService#applyDiscount(BigDecimal,int)")
                .and(x -> x.oursLocation.contains("OrderExportService")));
        expect("G7a both modified (textual conflict)", f(Category.BOTH_MODIFIED, "OrderServiceImpl#cancelOrder(Long)"));
        expect("G7b both modified (clean merge)", f(Category.BOTH_MODIFIED, "PricingService#calculatePrice(Order)")
                .and(x -> String.valueOf(x.details.get("gitTextualConflict")).startsWith("NO")));
        expect("G8 maven dependency", f(Category.MAVEN_DEPENDENCY, "org.projectlombok:lombok"));
        expect("G9a renamed constant used by ours' new method", fieldImpact("com.example.order.constants.OrderConstants.EXPORT_SEPARATOR",
                "CONSTANT_RENAMED", Finding.Severity.HIGH, "OrderExportService.java"));
        expect("G9a renamed constant used by an ours-only field initializer", fieldImpact(
                "com.example.order.constants.OrderConstants.EXPORT_SEPARATOR", "CONSTANT_RENAMED", Finding.Severity.HIGH, "ExportLimits.java"));
        expect("G9b removed enum constant used by ours-only class", fieldImpact("com.example.order.model.OrderStatus.CLOSED",
                "ENUM_CONSTANT_REMOVED", Finding.Severity.HIGH, "CachedOrderService.java"));
        expect("G9c renamed instance field used by ours' new method", fieldImpact("com.example.order.service.ShippingService.freeThreshold",
                "FIELD_RENAMED", Finding.Severity.HIGH, "ShippingService.java:18"));
        expect("G9c same field in a method theirs updated -> LOW", fieldImpact("com.example.order.service.ShippingService.freeThreshold",
                "FIELD_RENAMED", Finding.Severity.LOW, "ShippingService.java:11"));
        expect("G10a missing method in sibling module", G10A);
        expect("G10b constant removed in sibling module, used by ours' new code", G10B);
    }

    @Test
    void siblingFlagModes() {
        assertEquals(List.of("common"), report.analysisNotes.get("siblingModules"));
        expectNone(noSiblings, "G10a without siblings (common is an external library)", G10A);
        expectNone(noSiblings, "G10b without siblings", G10B);
        assertEquals(report.findings.size() - 2, noSiblings.findings.size(), dump(noSiblings));
        assertEquals(List.of("common", "other-service"), allSiblings.analysisNotes.get("siblingModules"));
        expect(allSiblings, "G10a with all siblings", G10A);
        expect(allSiblings, "G10b with all siblings", G10B);
        expectNone(allSiblings, "N10 other service dir (indexed as sibling, never reported)", x -> x.symbol.contains("com.example.other"));
        assertEquals(report.findings.size(), allSiblings.findings.size(), dump(allSiblings));
    }

    @Test
    void plantedNonGapsAreNotReported() {
        expectNone("N1 comment-only change", x -> x.symbol.contains("OrderController"));
        expectNone("N2 existing helper", x -> x.symbol.contains("calculatePrice") && x.category != Category.BOTH_MODIFIED);
        expectNone("N3 @Value with default", x -> x.symbol.contains("order.audit.enabled"));
        expectNone("N4 key only in a profile file", x -> x.symbol.contains("order.notify.channel"));
        expectNone("N5 existing bean via lombok constructor", x -> x.category == Category.SPRING_BEAN && x.symbol.contains("PricingService"));
        expectNone("N6 ours-only @Value", x -> x.symbol.contains("order.export.batch-size"));
        expectNone("N7 library calls", x -> x.symbol.startsWith("java.") || x.symbol.startsWith("org.springframework"));
        expectNone("N9 identical change on both sides", x -> x.symbol.contains("getOrder"));
        expectNone("N10 other service dir", x -> x.symbol.contains("com.example.other"));
        expectNone("N11 annotated mapper method", x -> x.symbol.contains("countAll"));
        expectNone("N12 default interface method", x -> x.symbol.contains("describe"));
        expectNone("retryCount has a Java default", x -> String.valueOf(x.details.get("missingKeys")).contains("retry-count"));
        expectNone("emailNotifier exists", x -> x.symbol.contains("emailNotifier"));
        expectNone("N13 constant removed by theirs that ours never uses", x -> x.symbol.contains("LEGACY_CHANNEL"));
        expectNone("N14 removed constant whose name ours only uses for its own constant", x -> x.symbol.contains("MAX_ITEMS"));
        expectNone("N15 identical sibling class / existing sibling method", x -> x.symbol.contains("IdUtils") || x.symbol.contains("trimToEmpty"));
        expectNone("N16a sibling-internal dependency of a sibling change", x -> x.symbol.contains("maskEmail") || x.symbol.contains("MaskingSupport")
                || x.neededBy.stream().anyMatch(u -> u.by().startsWith("com.example.common")));
        expectNone("N16b both-modified method inside the sibling module", x -> x.symbol.contains("DateUtils"));
        expectNone("unused new constant of theirs", x -> x.symbol.contains("CSV_SEPARATOR"));
        assertTrue(report.changeSets.get("theirs").stream().noneMatch(m -> m.get("method").contains("com.example.common")),
                "sibling changes must not appear in the reported change sets");
        assertEquals(0, report.findings.stream().filter(Finding::uncertain).count(), dump(report));
        assertEquals(1, report.summary.get("identicalChangesOnBothSides"));
    }
}
