package com.branchdiff.report;

import com.branchdiff.report.Finding.Category;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Renders a {@link Report} as Markdown. */
public final class MarkdownRenderer {
    private MarkdownRenderer() {
    }

    public static String render(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Branch merge gap report\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| repo | `").append(r.repo).append("` |\n");
        sb.append("| service dir | `").append(r.serviceDir).append("` |\n");
        r.refs.forEach((k, v) -> sb.append("| ").append(k).append(" | `").append(v).append("` |\n"));
        sb.append("| generated | ").append(r.generatedAt).append(" |\n\n");
        sb.append("Legend: **origin** `THEIRS_NEW` = theirs introduced the symbol (port it together with the code that needs it; "
                + "a full merge brings it only if its file merges cleanly) · `OURS_REMOVED`/`OURS_CHANGED` = the merge-base had it and ours "
                + "removed/changed it (semantic conflict git will NOT report) · `THEIRS_CHANGED` = theirs changed an API ours still uses.\n\n");

        // 1. summary
        sb.append("## 1. Summary\n\n");
        sb.append("| Category | Certain | Uncertain |\n|---|---:|---:|\n");
        for (Category c : Category.values()) {
            long cert = r.findings.stream().filter(f -> f.category == c && !f.uncertain()).count();
            long unc = r.findings.stream().filter(f -> f.category == c && f.uncertain()).count();
            sb.append("| ").append(c).append(" | ").append(cert).append(" | ").append(unc).append(" |\n");
        }
        sb.append("| **total** | ").append(r.findings.stream().filter(f -> !f.uncertain()).count()).append(" | ")
                .append(r.findings.stream().filter(Finding::uncertain).count()).append(" |\n\n");
        sb.append("- Method changes base→theirs: ").append(r.summary.get("theirsMethodChanges")).append('\n');
        sb.append("- Method changes base→ours: ").append(r.summary.get("oursMethodChanges")).append('\n');
        sb.append("- HIGH severity findings: **").append(r.summary.get("highSeverity")).append("**\n");
        sb.append("- Methods changed identically on both sides (not flagged): ").append(r.summary.get("identicalChangesOnBothSides")).append('\n');
        sb.append("- Files git would report as textual conflicts (`git merge-file`): ");
        sb.append(r.predictedTextualConflicts.isEmpty() ? "none" : String.join(", ", r.predictedTextualConflicts.stream().map(s -> "`" + s + "`").toList()));
        sb.append("\n");
        sb.append("- Analysis: ");
        List<String> notes = new ArrayList<>();
        r.analysisNotes.forEach((k, v) -> {
            if (!(v instanceof Map) && !(v instanceof List)) notes.add(k + "=" + firstLine(String.valueOf(v)));
        });
        sb.append(String.join("; ", notes)).append("\n");
        if (r.analysisNotes.get("siblingModules") instanceof List<?> sibs && !sibs.isEmpty()) {
            sb.append("- Sibling modules indexed for lookups (their own code is never reported): ");
            List<String> names = new ArrayList<>();
            for (Object o : sibs) names.add("`" + o + "`");
            sb.append(String.join(", ", names));
            if (r.analysisNotes.get("siblingJavaFiles") instanceof Map<?, ?> m) {
                sb.append(" (java files base/ours/theirs: ").append(m.get("base")).append('/').append(m.get("ours"))
                        .append('/').append(m.get("theirs")).append(')');
            }
            sb.append('\n');
        }
        sb.append('\n');
        if (!r.warnings.isEmpty()) {
            sb.append("<details><summary>Warnings (").append(r.warnings.size()).append(")</summary>\n\n");
            for (String w : r.warnings) sb.append("- ").append(esc(firstLine(w))).append('\n');
            sb.append("\n</details>\n\n");
        }

        // 2. missing deps
        sb.append("## 2. Missing / inconsistent dependencies\n\n");
        sb.append("Symbols that methods added/modified by theirs need, checked against ours (certain only; see §6 for name-matched guesses).\n\n");
        table(sb, r, f -> !f.uncertain() && (f.category == Category.MISSING_DEPENDENCY || f.category == Category.MAVEN_DEPENDENCY), true);

        // 3. spring
        sb.append("## 3. Spring config / bean gaps\n\n");
        Set<Category> spring = EnumSet.of(Category.SPRING_BEAN, Category.SPRING_CONFIG, Category.INTERFACE_IMPL, Category.MYBATIS);
        for (Category c : spring) {
            sb.append("### ").append(switch (c) {
                case SPRING_BEAN -> "3.1 Injected beans without a definition in ours";
                case SPRING_CONFIG -> "3.2 Config keys (@Value / Environment / @ConfigurationProperties) missing from ours' application*.yml|properties";
                case INTERFACE_IMPL -> "3.3 Abstract/interface methods added by theirs that ours' implementations lack";
                case MYBATIS -> "3.4 MyBatis mapper methods vs XML statement ids";
                default -> c.name();
            }).append("\n\n");
            table(sb, r, f -> !f.uncertain() && f.category == c && !isValueChange(f), false);
        }

        // 4. impact
        sb.append("## 4. Signature-change impact (places in ours still using an old signature, a deleted type, or a deleted/renamed field)\n\n");
        table(sb, r, f -> !f.uncertain() && f.category == Category.SIGNATURE_IMPACT, false);

        // 4b. value changes
        sb.append("## 4b. Values changed by theirs (constant / field initializers, MyBatis XML statement bodies)\n\n");
        sb.append("LOW = only theirs changed it (a whole-branch merge carries it; a backport/cherry-pick must port it) · "
                + "HIGH = ours and theirs changed it differently.\n\n");
        List<Finding> values = r.findings.stream().filter(f -> !f.uncertain() && isValueChange(f)).toList();
        table(sb, r, f -> !f.uncertain() && isValueChange(f), true);
        for (Finding f : values) diffBlocks(sb, f, "`" + f.symbol + "` (" + f.status + ")");

        // 5. both modified
        sb.append("## 5. Methods modified on both sides (manual review required)\n\n");
        List<Finding> both = r.findings.stream().filter(f -> f.category == Category.BOTH_MODIFIED).toList();
        if (both.isEmpty()) sb.append("_none_\n\n");
        for (Finding f : both) {
            sb.append("### `").append(f.symbol).append("` — ").append(f.kind).append("\n\n");
            sb.append("- ").append(f.message).append('\n');
            sb.append("- ours: `").append(nz(f.oursLocation)).append("` · theirs: `").append(nz(f.theirsLocation))
                    .append("` · base: `").append(nz(f.details.get("base"))).append("`\n");
            sb.append("- git textual conflict: **").append(nz(f.details.get("gitTextualConflict"))).append("**\n\n");
            diffBlocks(sb, f, null);
        }

        // 6. uncertain
        sb.append("## 6. Uncertain items (name-only matches or unconfirmed origin; verify manually)\n\n");
        table(sb, r, Finding::uncertain, true);

        // 7. chains
        chains(sb, r);

        // appendix
        sb.append("## Appendix: method-level change sets\n\n");
        for (var e : r.changeSets.entrySet()) {
            sb.append("<details><summary>base → ").append(e.getKey()).append(" (").append(e.getValue().size()).append(" changes)</summary>\n\n");
            sb.append("| Kind | Method | Location |\n|---|---|---|\n");
            for (Map<String, String> m : e.getValue()) {
                sb.append("| ").append(m.get("kind")).append(" | `").append(esc(m.get("method"))).append("` | `")
                        .append(m.get("location")).append("` |\n");
            }
            sb.append("\n</details>\n\n");
        }
        return sb.toString();
    }

    /** Findings grouped by dependency chain, with each chain's ordered checklist (the HTML report shows the trees). */
    private static void chains(StringBuilder sb, Report r) {
        if (r.context == null) return;
        List<ContextModel.Chain> multi = new ArrayList<>();
        for (ContextModel.Chain c : r.context.chains) if (c.findings.size() > 1) multi.add(c);
        if (multi.isEmpty()) return;
        sb.append("## 7. Dependency chains (findings that belong together; port / update in this order)\n\n");
        for (ContextModel.Chain c : multi) {
            ContextModel.Node root = r.context.nodes.get(c.root);
            sb.append("### ").append(c.id).append(" (").append(c.direction).append(", findings ");
            for (int i = 0; i < c.findings.size(); i++) sb.append(i > 0 ? ", " : "").append('#').append(c.findings.get(i));
            sb.append(") rooted at `").append(esc(root != null ? root.symbol : c.root)).append("`\n\n");
            for (ContextModel.ChecklistItem it : c.checklist) {
                ContextModel.Node n = r.context.nodes.get(it.node());
                ContextModel.Loc loc = n.loc.getOrDefault("theirs", n.loc.get("ours"));
                sb.append(it.step()).append(". ").append(it.action()).append(": `").append(esc(n.symbol)).append("` (")
                        .append(n.ours).append(" in ours").append(loc != null ? ", " + loc.location() : "").append(")")
                        .append(it.why() == null || it.why().isEmpty() ? "" : " - " + it.why()).append('\n');
            }
            sb.append('\n');
        }
    }

    /** Value-level findings (field initializers, MyBatis element bodies) rendered in section 4b. */
    static boolean isValueChange(Finding f) {
        return f.category == Category.CONSTANT_VALUE
                || (f.category == Category.MYBATIS && f.status.endsWith("_STATEMENT") && f.kind.startsWith("MAPPER_"));
    }

    private static void diffBlocks(StringBuilder sb, Finding f, String label) {
        for (String k : List.of("diffBaseToOurs", "diffBaseToTheirs", "diffOursToTheirs")) {
            Object d = f.details.get(k);
            if (!(d instanceof List<?> lines)) continue;
            sb.append("<details><summary>").append(label == null ? "" : label + " ").append(k).append("</summary>\n\n```diff\n");
            for (Object l : lines) sb.append(l).append('\n');
            sb.append("```\n</details>\n\n");
        }
    }

    private static void table(StringBuilder sb, Report r, Predicate<Finding> p, boolean withCategory) {
        List<Finding> fs = r.findings.stream().filter(p).toList();
        if (fs.isEmpty()) {
            sb.append("_none_\n\n");
            return;
        }
        sb.append("| # | Sev | ").append(withCategory ? "Category | " : "").append("Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |\n");
        sb.append("|---|---|").append(withCategory ? "---|" : "").append("---|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (Finding f : fs) {
            StringBuilder needed = new StringBuilder();
            for (Finding.Usage u : f.neededBy) {
                if (needed.length() > 0) needed.append("<br>");
                needed.append('`').append(esc(u.by())).append("` @ `").append(u.location()).append('`');
            }
            StringBuilder notes = new StringBuilder(esc(f.message));
            f.details.forEach((k, v) -> {
                if (k.startsWith("diff")) return;
                notes.append("<br>").append(k).append(": ").append(esc(String.valueOf(v)));
            });
            sb.append("| ").append(i++).append(" | ").append(f.severity).append(" | ");
            if (withCategory) sb.append(f.category).append(" | ");
            sb.append(f.status).append(" | ").append(f.kind).append(" | `").append(esc(f.symbol)).append("` | ")
                    .append(nz(f.origin)).append(" | ").append(needed).append(" | ")
                    .append(f.theirsLocation == null ? "" : "`" + f.theirsLocation + "`").append(" | ")
                    .append(f.oursLocation == null ? "" : "`" + f.oursLocation + "`").append(" | ")
                    .append(notes).append(" |\n");
        }
        sb.append('\n');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
