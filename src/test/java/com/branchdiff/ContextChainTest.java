package com.branchdiff;

import com.branchdiff.analysis.ContextBuilder;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.ContextModel.Chain;
import com.branchdiff.report.ContextModel.TreeNode;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Report;
import com.branchdiff.report.html.HtmlRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.branchdiff.TestSupport.analyze;
import static com.branchdiff.TestSupport.snap;
import static com.branchdiff.TestSupport.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dependency chains, reverse impact and port checklists of the context view. */
class ContextChainTest {
    static final String CTRL = "src/main/java/a/web/RoleController.java";
    static final String SVC = "src/main/java/a/svc/TokenService.java";
    static final String HELP = "src/main/java/a/svc/PermHelper.java";
    static final String CONST = "src/main/java/a/svc/Keys.java";
    static final String UTIL = "src/main/java/a/util/Util.java";

    static String ctrl(String body) {
        return "package a.web;\nimport a.svc.TokenService;\npublic class RoleController {\n    private TokenService tokenService;\n"
                + "    public String edit(Long id) {\n        " + body + "\n        return \"ok\";\n    }\n}\n";
    }

    static final Snapshot BASE = snap("base",
            CTRL, ctrl("id.hashCode();"),
            SVC, "package a.svc;\npublic class TokenService {\n    public void refresh() { }\n}\n",
            HELP, "package a.svc;\npublic class PermHelper {\n    public static int size() { return 1; }\n}\n",
            CONST, "package a.svc;\npublic final class Keys {\n    public static final String OLD = \"o\";\n}\n");

    /** theirs: edit -> TokenService.refreshByRole (new) -> PermHelper.collect (new) -> Keys.ROLE_KEY (new) */
    static Snapshot theirsChain() {
        return with(BASE, "theirs",
                CTRL, ctrl("tokenService.refreshByRole(id);"),
                SVC, "package a.svc;\npublic class TokenService {\n    public void refresh() { }\n\n"
                        + "    /** refresh the permissions of every user of a role */\n"
                        + "    public void refreshByRole(Long roleId) {\n        PermHelper.collect(roleId);\n        refresh();\n    }\n}\n",
                HELP, "package a.svc;\npublic class PermHelper {\n    public static int size() { return 1; }\n"
                        + "    public static String collect(Long id) {\n        return Keys.ROLE_KEY + id + size();\n    }\n}\n",
                CONST, "package a.svc;\npublic final class Keys {\n    public static final String OLD = \"o\";\n"
                        + "    public static final String ROLE_KEY = \"role:\";\n}\n");
    }

    static Finding finding(Report r, String symbolPart) {
        return r.findings.stream().filter(f -> f.symbol.contains(symbolPart)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding " + symbolPart + " in\n" + TestSupport.dump(r)));
    }

    static TreeNode child(TreeNode t, String keyPart) {
        for (TreeNode c : t.children) if (c.node.contains(keyPart)) return c;
        throw new AssertionError("no child " + keyPart + " under " + t.node + ": "
                + t.children.stream().map(c -> c.node).collect(Collectors.toList()));
    }

    @Test
    void transitiveChainIsOneGroupWithDependenciesFirst() {
        Report r = analyze(BASE, BASE, theirsChain());
        ContextModel ctx = r.context;
        assertNotNull(ctx, String.valueOf(r.warnings));
        Finding refresh = finding(r, "TokenService#refreshByRole");
        Finding collect = finding(r, "PermHelper#collect");
        Finding key = finding(r, "Keys.ROLE_KEY");

        // one chain for the three findings, rooted at the theirs caller
        String chain = ctx.findings.get(refresh.id).chain;
        assertEquals(chain, ctx.findings.get(collect.id).chain);
        assertEquals(chain, ctx.findings.get(key.id).chain);
        Chain c = ctx.chains.stream().filter(x -> x.id.equals(chain)).findFirst().orElseThrow();
        assertEquals("M:a.web.RoleController#edit(Long)", c.root);
        assertEquals("port", c.direction);
        assertEquals(Set.of(refresh.id, collect.id, key.id), Set.copyOf(c.findings));

        // edit -> refreshByRole -> collect -> ROLE_KEY, each missing in ours, with the "used at" line of the parent
        TreeNode root = ctx.findings.get(refresh.id).dependencies.get(0);
        assertEquals("M:a.web.RoleController#edit(Long)", root.node);
        TreeNode l1 = child(root, "TokenService#refreshByRole(Long)");
        assertEquals(CTRL + ":6", l1.at);
        TreeNode l2 = child(l1, "PermHelper#collect(Long)");
        TreeNode l3 = child(l2, "F:a.svc.Keys.ROLE_KEY");
        assertEquals(ContextModel.MISSING, ctx.nodes.get(l1.node).ours);
        assertEquals(ContextModel.MISSING, ctx.nodes.get(l2.node).ours);
        assertEquals(ContextModel.MISSING, ctx.nodes.get(l3.node).ours);
        assertEquals(ContextModel.CONSTANT, ctx.nodes.get(l3.node).kind);
        assertEquals(ContextModel.DIFFERS, ctx.nodes.get(root.node).ours, "theirs changed the caller");
        // present dependencies are shown but not expanded
        TreeNode size = child(l2, "PermHelper#size()");
        assertEquals(ContextModel.PRESENT, ctx.nodes.get(size.node).ours);
        assertTrue(size.children.isEmpty());

        // checklist: dependencies before the code that needs them, the root last
        List<String> order = c.checklist.stream().map(ContextModel.ChecklistItem::node).collect(Collectors.toList());
        assertTrue(order.indexOf(l3.node) < order.indexOf(l2.node), order.toString());
        assertTrue(order.indexOf(l2.node) < order.indexOf(l1.node), order.toString());
        assertEquals(root.node, order.get(order.size() - 1));
        assertFalse(order.contains(size.node), "present nodes are not ported");
        assertTrue(c.checklist.get(0).action().startsWith("Add"), c.checklist.get(0).action());

        // node ranges: the theirs method including its javadoc
        ContextModel.Loc loc = ctx.nodes.get(l1.node).loc.get("theirs");
        assertEquals(SVC, loc.path());
        assertEquals(5, loc.start());
        assertEquals(6, loc.line());
        assertEquals(9, loc.end());
        assertFalse(ctx.nodes.get(l1.node).loc.containsKey("ours"));
    }

    @Test
    void cyclesAreCutAndPortedOnce() {
        Snapshot theirs = with(BASE, "theirs",
                CTRL, ctrl("a.svc.TokenService.ping(3);"),
                SVC, "package a.svc;\npublic class TokenService {\n    public void refresh() { }\n"
                        + "    public static int ping(int n) { return n <= 0 ? 0 : PermHelper.pong(n - 1); }\n}\n",
                HELP, "package a.svc;\npublic class PermHelper {\n    public static int size() { return 1; }\n"
                        + "    public static int pong(int n) { return TokenService.ping(n); }\n}\n");
        Report r = analyze(BASE, BASE, theirs);
        ContextModel ctx = r.context;
        Finding ping = finding(r, "TokenService#ping");
        Finding pong = finding(r, "PermHelper#pong");
        assertEquals(ctx.findings.get(ping.id).chain, ctx.findings.get(pong.id).chain);
        TreeNode root = ctx.findings.get(ping.id).dependencies.get(0);
        TreeNode p1 = child(root, "TokenService#ping(int)");
        TreeNode p2 = child(p1, "PermHelper#pong(int)");
        TreeNode back = child(p2, "TokenService#ping(int)");
        assertTrue(back.cycle, "ping is already on the path");
        assertTrue(back.children.isEmpty());
        Chain c = ctx.chains.stream().filter(x -> x.id.equals(ctx.findings.get(ping.id).chain)).findFirst().orElseThrow();
        List<String> order = c.checklist.stream().map(ContextModel.ChecklistItem::node).collect(Collectors.toList());
        assertEquals(order.size(), Set.copyOf(order).size(), "each node once: " + order);
        assertTrue(order.contains(p1.node) && order.contains(p2.node));
    }

    @Test
    void depthIsBounded() {
        // edit -> m0 -> m1 -> ... -> m6, all new in theirs
        StringBuilder svc = new StringBuilder("package a.svc;\npublic class TokenService {\n    public void refresh() { }\n");
        for (int i = 0; i < 7; i++) {
            svc.append("    public static int m").append(i).append("() { return ").append(i < 6 ? "m" + (i + 1) + "()" : "0").append("; }\n");
        }
        svc.append("}\n");
        Snapshot theirs = with(BASE, "theirs", CTRL, ctrl("a.svc.TokenService.m0();"), SVC, svc.toString());
        Report r = analyze(BASE, BASE, theirs);
        TreeNode t = r.context.findings.get(finding(r, "TokenService#m0").id).dependencies.get(0);
        int depth = 0;
        while (!t.children.isEmpty()) {
            t = t.children.get(0);
            depth++;
        }
        assertEquals(ContextBuilder.MAX_DEPTH, depth);
        assertTrue(t.truncated, "the last expanded level says it was cut: " + t.node);
    }

    @Test
    void reverseImpactGroupsOursUsagesOfRemovedMethods() {
        Snapshot base = with(BASE, "base", UTIL, "package a.util;\npublic class Util {\n"
                + "    public static int one() { return 1; }\n    public static int two() { return 2; }\n}\n");
        String user = "src/main/java/a/web/Report.java";
        // ours added a class that uses both methods; theirs removed them
        Snapshot ours = with(base, "ours", user, "package a.web;\nimport a.util.Util;\npublic class Report {\n"
                + "    public int total() {\n        return Util.one()\n            + Util.two();\n    }\n"
                + "    public int twice() { return total() * 2; }\n}\n");
        Snapshot theirs = with(base, "theirs", UTIL, "package a.util;\npublic class Util {\n}\n");
        Report r = analyze(base, ours, theirs);
        ContextModel ctx = r.context;
        Finding one = finding(r, "Util#one");
        Finding two = finding(r, "Util#two");
        assertEquals(Finding.Category.SIGNATURE_IMPACT, one.category);
        String chain = ctx.findings.get(one.id).chain;
        assertEquals(chain, ctx.findings.get(two.id).chain, "both are used by Report#total");
        Chain c = ctx.chains.stream().filter(x -> x.id.equals(chain)).findFirst().orElseThrow();
        assertEquals("impact", c.direction);
        assertEquals("M:a.web.Report#total()", c.root);

        // who depends on Util#two in ours: total() at line 6, and transitively twice()
        TreeNode impact = ctx.findings.get(two.id).impact;
        assertEquals("M:a.util.Util#two()", impact.node);
        TreeNode total = child(impact, "Report#total()");
        assertEquals(user + ":6", total.at);
        TreeNode twice = child(total, "Report#twice()");
        assertEquals(user + ":8", twice.at);
        assertEquals("theirs removed", ctx.nodes.get(impact.node).change);
        // the update checklist names the removed methods first, then the ours caller
        List<String> order = c.checklist.stream().map(ContextModel.ChecklistItem::node).collect(Collectors.toList());
        assertEquals("M:a.web.Report#total()", order.get(order.size() - 1), order.toString());
    }

    @Test
    void unrelatedFindingsStayInSeparateChains() {
        Snapshot theirs = with(theirsChain(), "theirs",
                UTIL, "package a.util;\npublic class Util {\n    public static int fresh() { return 1; }\n}\n",
                "src/main/java/a/web/Other.java", "package a.web;\npublic class Other {\n    public int run() { return a.util.Util.fresh(); }\n}\n");
        Snapshot base = with(BASE, "base", UTIL, "package a.util;\npublic class Util {\n}\n",
                "src/main/java/a/web/Other.java", "package a.web;\npublic class Other {\n    public int run() { return 0; }\n}\n");
        Report r = analyze(base, base, theirs);
        String a = r.context.findings.get(finding(r, "TokenService#refreshByRole").id).chain;
        String b = r.context.findings.get(finding(r, "Util#fresh").id).chain;
        assertFalse(a.equals(b), "Util#fresh is not part of the RoleController chain");
    }

    @Test
    void aSharedDependencyThatOnlyDiffersDoesNotMergeChains() {
        String shared = "src/main/java/a/svc/Shared.java";
        String other = "src/main/java/a/web/Other.java";
        Snapshot base = with(BASE, "base", shared, "package a.svc;\npublic class Shared {\n    public static int calc() { return 1; }\n}\n",
                UTIL, "package a.util;\npublic class Util {\n}\n",
                other, "package a.web;\npublic class Other {\n    public int run() { return 0; }\n}\n");
        Snapshot ours = with(base, "ours", shared, "package a.svc;\npublic class Shared {\n    public static int calc() { return 2; }\n}\n");
        // theirs: two unrelated features (RoleController chain, Other -> Util.fresh) both call Shared.calc, which both sides changed
        Snapshot theirs = with(theirsChain(), "theirs",
                shared, "package a.svc;\npublic class Shared {\n    public static int calc() { return 3; }\n}\n",
                HELP, "package a.svc;\npublic class PermHelper {\n    public static int size() { return Shared.calc(); }\n"
                        + "    public static String collect(Long id) {\n        return Keys.ROLE_KEY + id + size() + Shared.calc();\n    }\n}\n",
                UTIL, "package a.util;\npublic class Util {\n    public static int fresh() { return a.svc.Shared.calc(); }\n}\n",
                other, "package a.web;\npublic class Other {\n    public int run() { return a.util.Util.fresh(); }\n}\n");
        Report r = analyze(base, ours, theirs);
        String role = r.context.findings.get(finding(r, "TokenService#refreshByRole").id).chain;
        String util = r.context.findings.get(finding(r, "Util#fresh").id).chain;
        assertEquals(ContextModel.DIFFERS, r.context.nodes.get("M:a.svc.Shared#calc()").ours);
        assertTrue(contains(r.context.findings.get(finding(r, "Util#fresh").id).dependencies, "M:a.svc.Shared#calc()"),
                "the shared dependency is still shown in the tree");
        assertFalse(role.equals(util), "a dependency ours already has (only its content differs) must not merge chains");
    }

    static boolean contains(List<TreeNode> ts, String key) {
        for (TreeNode t : ts) if (t.node.equals(key) || contains(t.children, key)) return true;
        return false;
    }

    @Test
    void htmlAndJsonCarryTheContext() throws Exception {
        Snapshot theirs = theirsChain();
        Report r = analyze(BASE, BASE, theirs);
        String html = HtmlRenderer.render(r, HtmlRenderer.source(BASE, BASE, theirs, null));
        for (String id : List.of("id=\"tabs\"", "data-tab=\"deps\"", "data-tab=\"impact\"", "data-tab=\"check\"", "data-tab=\"overview\"",
                "id=\"ctx\"", "id=\"groupSeg\"")) {
            assertTrue(html.contains(id), id);
        }
        JsonNode d = HtmlRendererTest.embedded(html);
        JsonNode ctx = d.get("context");
        assertNotNull(ctx);
        assertTrue(ctx.get("chains").size() >= 1);
        Finding refresh = finding(r, "TokenService#refreshByRole");
        JsonNode fc = ctx.get("findings").get(String.valueOf(refresh.id));
        assertEquals("M:a.svc.TokenService#refreshByRole(Long)", fc.get("node").asText());
        assertEquals("M:a.web.RoleController#edit(Long)", fc.get("dependencies").get(0).get("node").asText());
        // member pseudo-file: only the declaration, with its line offset; ours lacks it
        JsonNode member = d.get("files").get("@M:a.svc.TokenService#refreshByRole(Long)");
        assertNotNull(member, d.get("files").fieldNames().toString());
        assertTrue(member.get("member").asBoolean());
        assertTrue(member.get("ours").isNull());
        assertEquals(4, member.get("offset").get("theirs").asInt());
        assertTrue(member.get("theirs").asText().startsWith("    /** refresh"), member.get("theirs").asText());
        assertTrue(member.get("theirs").asText().trim().endsWith("}"));
        // the caller exists in all three versions: 3-way hunks of the member, theirs-only change
        JsonNode caller = d.get("files").get("@M:a.web.RoleController#edit(Long)");
        assertEquals("theirs", caller.get("hunks3").get(0).get("type").asText());

        // JSON report output carries the same context
        JsonNode json = new ObjectMapper().readTree(Main.toJson(r));
        assertEquals(refresh.id, json.get("findings").get(refresh.id - 1).get("id").asInt());
        assertTrue(json.get("context").get("nodes").has("F:a.svc.Keys.ROLE_KEY"));
        List<String> steps = new ArrayList<>();
        for (JsonNode c : json.get("context").get("chains")) for (JsonNode s : c.get("checklist")) steps.add(s.get("node").asText());
        assertTrue(steps.contains("M:a.svc.PermHelper#collect(Long)"), steps.toString());

        // Markdown lists the multi-finding chain with its ordered checklist
        String md = com.branchdiff.report.MarkdownRenderer.render(r);
        assertTrue(md.contains("## 7. Dependency chains"), md);
        assertTrue(md.contains("`a.svc.PermHelper#collect(Long)`") || md.contains("PermHelper#collect"), md);
    }

    @Test
    void templateDeclaresEachTopLevelVariableOnce() throws Exception {
        // the report script is one function scope: a second "var X" silently replaces the first (this once broke folding)
        String tpl;
        try (var in = HtmlRenderer.class.getResourceAsStream("/html/report-template.html")) {
            tpl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^  var ([A-Za-z_$][\\w$]*)").matcher(tpl);
        Set<String> seen = new java.util.HashSet<>();
        while (m.find()) assertTrue(seen.add(m.group(1)), "declared twice: " + m.group(1));
        assertTrue(seen.contains("CONTEXT_LINES") && seen.contains("CTX"), seen.toString());
    }
}
