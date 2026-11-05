package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.BlameRun;
import com.branchdiff.conflict.ConflictModel.ChecklistItem;
import com.branchdiff.conflict.ConflictModel.CommitNeed;
import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Member;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.conflict.ConflictModel.Orphan;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.Finding;
import com.branchdiff.report.MarkdownRenderer;
import com.branchdiff.report.Report;

import java.util.List;

/** Markdown rendering of the conflict analysis, followed by the regular findings report of the picked change. */
public final class ConflictMarkdown {
    private ConflictMarkdown() {
    }

    public static String render(Report r) {
        ConflictModel c = r.conflicts;
        StringBuilder sb = new StringBuilder();
        sb.append("# faultline conflict report\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| Mode | ").append(c.mode).append(" |\n");
        sb.append("| Repository | `").append(c.repo).append("` |\n");
        sb.append("| Ours (tenant) | ").append(commit(c.ours)).append(" |\n");
        for (int i = 0; i < c.picks.size(); i++) sb.append("| Pick ").append(i + 1).append(" | ").append(commit(c.picks.get(i))).append(" |\n");
        sb.append("| Base (pick parent) | ").append(commit(c.base)).append(" |\n");
        sb.append("| Fork point | ").append(commit(c.fork)).append(c.mainline != null ? " (merge-base with `" + c.mainline + "`)" : "").append(" |\n");
        sb.append("| Window | ").append(c.mainlineCommitsInWindow).append(" mainline commits fork..base, ").append(c.tenantCommitsSinceFork)
                .append(" tenant commits fork..ours |\n\n");
        sb.append("**").append(c.summary.get("conflictHunks")).append(" conflict hunk(s) in ").append(c.files.size()).append(" file(s)**, ")
                .append(c.cleanFiles.size()).append(" file(s) of the pick merged cleanly; ").append(c.summary.get("symbolsToBringOrAdapt"))
                .append(" symbol(s) to bring over or adapt; ").append(c.bringAlong.size()).append(" mainline commit(s) to consider.\n\n");
        for (String m : c.messages) sb.append("> ").append(m).append("\n");
        if (!c.pending.isEmpty()) sb.append("> still to apply after this one: ").append(String.join("; ", c.pending)).append("\n");
        sb.append("\n## Migration checklist\n\n");
        for (ChecklistItem i : c.checklist) {
            sb.append(i.step()).append(". **").append(i.action()).append("** ").append(esc(i.target())).append(" - ").append(esc(i.why()));
            if (!i.hunks().isEmpty()) sb.append(" _(hunks: ").append(String.join(", ", i.hunks())).append(")_");
            sb.append("\n");
        }
        if (!c.bringAlong.isEmpty()) {
            sb.append("\n### Mainline commits the pick depends on (oldest first)\n\n| Commit | Date | Subject | Provides | Needed by |\n|---|---|---|---|---|\n");
            for (CommitNeed n : c.bringAlong) {
                sb.append("| `").append(n.commit().shortSha()).append("` | ").append(day(n.commit().date())).append(" | ").append(esc(n.commit().subject()))
                        .append(" | ").append(esc(String.join(", ", n.provides()))).append(" | ").append(esc(String.join(", ", n.neededBy()))).append(" |\n");
            }
        }
        sb.append("\n## Conflict hunks\n");
        for (ConflictFile f : c.files) {
            sb.append("\n### ").append(f.path).append(" (").append(f.kind).append(c.picks.size() > 1 ? ", step " + f.step : "").append(")\n\n");
            if (f.explanation != null) sb.append(f.explanation).append("\n\n");
            for (Hunk h : f.hunks) hunk(sb, h, r);
            if (f.merged != null) {
                sb.append("#### Lines git merged automatically in this file\n\n").append(esc(f.merged.summary)).append("\n\n");
                needs(sb, f.merged.needs);
            }
        }
        sb.append("\n## Files of the pick that merged cleanly\n\n");
        for (String p : c.cleanFiles) {
            sb.append("- ").append(p).append("\n");
            ConflictModel.TakeTheirs tt = c.cleanNeeds.get(p);
            if (tt != null) {
                sb.append("\n  ").append(esc(tt.summary)).append("\n\n");
                StringBuilder t = new StringBuilder();
                needs(t, tt.needs);
                for (String line : t.toString().split("\n")) if (!line.isEmpty()) sb.append("  ").append(line).append("\n");
                sb.append("\n");
            }
        }
        if (!c.cleanFileFindings.isEmpty()) {
            sb.append("\nFindings outside the conflict hunks (the picked code there may silently depend on things the tenant lacks): ");
            for (Integer id : c.cleanFileFindings) sb.append("#").append(id).append(' ');
            sb.append("- see the full findings report below.\n");
        }
        sb.append("\n## Limitations\n\n");
        for (String l : c.limitations) sb.append("- ").append(l).append("\n");
        sb.append("\n---\n\n");
        sb.append(MarkdownRenderer.render(r).replaceFirst("^# ", "# Semantic findings of the pick: "));
        return sb.toString();
    }

    private static void hunk(StringBuilder sb, Hunk h, Report r) {
        sb.append("#### Hunk ").append(h.id).append("\n\n");
        sb.append("| side | lines | members |\n|---|---|---|\n");
        for (String side : List.of("ours", "base", "theirs")) {
            ConflictModel.Range rg = side.equals("ours") ? h.ours : side.equals("base") ? h.base : h.theirs;
            sb.append("| ").append(side).append(" | ").append(rg).append(" | ").append(esc(members(h.members.get(side)))).append(" |\n");
        }
        suggestion(sb, h);
        sb.append("\n**Why:** ").append(esc(h.why)).append("\n\n");
        if (!h.mainline.isEmpty()) {
            sb.append("Mainline (fork..base):\n\n");
            for (Event e : h.mainline) sb.append("- ").append(event(e)).append("\n");
            sb.append("\n");
        }
        if (!h.tenant.isEmpty()) {
            sb.append("Tenant (fork..ours):\n\n");
            for (Event e : h.tenant) sb.append("- ").append(event(e)).append("\n");
            sb.append("\n");
        }
        if (!h.baseBlame.isEmpty()) {
            sb.append("Base lines by commit: ");
            for (BlameRun b : h.baseBlame) {
                sb.append(b.start()).append(b.end() > b.start() ? "-" + b.end() : "").append(" ")
                        .append(b.boundary() ? "(before fork)" : "`" + b.commit().shortSha() + "`").append("; ");
            }
            sb.append("\n\n");
        }
        sb.append("**Take theirs:** ").append(esc(h.takeTheirs.summary)).append("\n\n");
        needs(sb, h.takeTheirs.needs);
        sb.append("**Take ours:** ").append(esc(h.takeOurs.summary)).append("\n\n");
        for (String l : h.takeOurs.lost) sb.append("- loses: ").append(esc(l)).append("\n");
        for (EntryPoint e : h.takeOurs.entryPoints) {
            sb.append("- entry point ").append(e.type).append(" `").append(esc(e.route)).append("`")
                    .append(e.permission != null ? " (" + esc(e.permission) + ")" : "").append(" at ").append(e.location)
                    .append(e.path.size() > 1 ? " - call path: " : " (the member itself is the entry point)");
            if (e.path.size() > 1) {
                for (int i = 0; i < e.path.size(); i++) sb.append(i > 0 ? " -> " : "").append(label(r, e.path.get(i)));
            }
            sb.append("\n");
        }
        for (Orphan o : h.takeOurs.orphans) {
            sb.append("- left dangling: ").append(label(r, o.usedBy())).append(" (").append(o.location()).append(") uses ")
                    .append(label(r, o.node())).append(" - ").append(esc(o.note())).append("\n");
        }
        if (!h.findings.isEmpty()) {
            sb.append("- related findings:");
            for (Integer id : h.findings) {
                for (Finding f : r.findings) if (f.id == id) sb.append(" #").append(id).append(" ").append(esc(f.symbol)).append(";");
            }
            sb.append("\n");
        }
        for (String n : h.notes) sb.append("- note: ").append(esc(n)).append("\n");
        sb.append("\n");
    }

    private static void suggestion(StringBuilder sb, Hunk h) {
        ConflictModel.Suggestion s = h.suggestion;
        if (s == null) return;
        sb.append("\n**Suggestion: ").append(Suggestions.label(s.choice)).append("** (").append(s.confidence.toLowerCase())
                .append(" confidence) - ").append(esc(s.headline)).append("\n\n");
        for (String x : s.reasons) sb.append("- ").append(esc(x)).append("\n");
        for (String x : s.followUps) sb.append("- then: ").append(esc(x)).append("\n");
        if (s.resolution != null) {
            String fence = "```";
            for (String l : s.resolution) while (l.contains(fence)) fence += "`";
            sb.append("\nResolved lines (in place of the conflict markers):\n\n").append(fence).append(lang(h.path)).append("\n");
            for (String l : s.resolution) sb.append(l).append("\n");
            sb.append(fence).append("\n");
        }
    }

    private static String lang(String path) {
        String l = path.toLowerCase();
        int dot = l.lastIndexOf('.');
        String ext = dot < 0 ? "" : l.substring(dot + 1);
        return switch (ext) {
            case "yml" -> "yaml";
            case "java", "xml", "yaml", "properties", "sql", "json", "kt", "js", "ts" -> ext;
            default -> "";
        };
    }

    private static void needs(StringBuilder sb, List<Need> needs) {
        boolean any = false;
        for (Need n : needs) {
            if (n.ours.equals(ContextModel.PRESENT)) continue;
            if (!any) sb.append("| symbol | line | in tenant | origin | tenant has | introduced by | detail |\n|---|---|---|---|---|---|---|\n");
            any = true;
            sb.append("| `").append(esc(n.symbol)).append("` | ").append(n.line > 0 ? n.line : "").append(" | ").append(n.ours)
                    .append(n.uncertain ? " (?)" : "").append(" | ").append(n.origin).append(" | ")
                    .append(n.tenantName == null ? "" : "`" + esc(n.tenantName) + "`" + (n.tenantLocation != null ? " " + esc(n.tenantLocation) : ""))
                    .append(" | ");
            for (var cm : n.introducedBy) sb.append("`").append(cm.shortSha()).append("` ").append(esc(cm.subject())).append("<br>");
            sb.append(" | ").append(esc(n.detail)).append(" |\n");
        }
        if (any) sb.append("\n");
    }

    private static String label(Report r, String key) {
        if (r.context != null) {
            ContextModel.Node n = r.context.nodes.get(key);
            if (n != null && n.label != null) return "`" + esc(n.label) + "`";
        }
        return "`" + esc(key) + "`";
    }

    static String event(Event e) {
        return "`" + e.commit.shortSha() + "` " + day(e.commit.date()) + " " + e.kind + (e.from != null || e.to != null ? " "
                + esc(e.from == null ? "" : e.from) + (e.from != null && e.to != null && !e.from.equals(e.to) ? " -> " + esc(e.to)
                : e.from == null ? esc(e.to) : "") : "") + (e.detail != null ? " (" + esc(e.detail) + ")" : "") + " - " + esc(e.commit.subject());
    }

    private static String members(List<Member> ms) {
        if (ms == null) return "(file absent)";
        StringBuilder sb = new StringBuilder();
        for (Member m : ms) sb.append(sb.length() > 0 ? ", " : "").append(m.kind()).append(" ").append(m.label());
        return sb.toString();
    }

    private static String commit(ConflictModel.Commit c) {
        return c == null ? "" : "`" + c.shortSha() + "` " + day(c.date()) + " " + esc(c.subject());
    }

    static String day(String iso) {
        return iso == null || iso.length() < 10 ? String.valueOf(iso) : iso.substring(0, 10);
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

}
