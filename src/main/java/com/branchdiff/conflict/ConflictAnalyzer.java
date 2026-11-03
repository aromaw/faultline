package com.branchdiff.conflict;

import com.branchdiff.analysis.Analyzer;
import com.branchdiff.analysis.ContextBuilder;
import com.branchdiff.analysis.DependencyAnalyzer;
import com.branchdiff.analysis.DependencyAnalyzer.Dep;
import com.branchdiff.analysis.DependencyAnalyzer.DepKind;
import com.branchdiff.conflict.ConflictModel.BlameRun;
import com.branchdiff.conflict.ConflictModel.ChecklistItem;
import com.branchdiff.conflict.ConflictModel.Commit;
import com.branchdiff.conflict.ConflictModel.CommitNeed;
import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Member;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.conflict.ConflictModel.Orphan;
import com.branchdiff.conflict.ConflictModel.Range;
import com.branchdiff.conflict.ConflictModel.TakeTheirs;
import com.branchdiff.conflict.MemberDiff.Change;
import com.branchdiff.conflict.Members.M;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.git.GitRunner;
import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.index.MethodInfo;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.ContextModel.TreeNode;
import com.branchdiff.report.Finding;
import com.branchdiff.report.html.LineHunks;
import com.branchdiff.util.Ast;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains every conflict hunk of a cherry-pick (or merge): where it is (members on each side), why it conflicts
 * (mainline / tenant history of that code since the fork), what taking theirs needs (and whether the tenant has it,
 * under which name, and which mainline commits introduced it) and what keeping ours breaks (tenant callers up to
 * entry points; code of the pick left referencing symbols only theirs provides), plus a migration checklist.
 */
public final class ConflictAnalyzer {
    private final GitRunner git;
    private final ConflictSession session;
    private final Analyzer.Context ctx;
    private final CodeIndex fork;
    private final String forkSha;
    private final History history;
    private final ContextBuilder cb;
    private final DependencyAnalyzer deps;
    private final ConflictModel model = new ConflictModel();
    private final Map<String, List<M>> membersByContent = new HashMap<>();
    /** new member id (at the feature base) -> change from the fork (renames, moves, signature changes on mainline) */
    private Map<String, Change> mainlineRenames;
    /** fork member id -> change to the tenant (renames by the tenant) */
    private Map<String, Change> tenantRenames;
    private final List<String> pickDeclaredSymbols = new ArrayList<>();

    public ConflictAnalyzer(GitRunner git, ConflictSession session, String mainlineRef, Analyzer.Context ctx,
                            Snapshot forkSnapshot, CodeIndex forkIndex) {
        this.git = git;
        this.session = session;
        this.ctx = ctx;
        this.fork = forkIndex;
        this.forkSha = forkSnapshot.commit();
        this.history = new History(git);
        this.cb = ctx.context();
        this.deps = new DependencyAnalyzer(ctx.base(), ctx.ours(), ctx.theirs());
        model.mainline = mainlineRef;
    }

    /** merge-base of the tenant and the mainline (or the feature base when no mainline ref is given) */
    public static String forkPoint(GitRunner git, ConflictSession s, String mainlineRef) {
        String other = mainlineRef != null ? git.resolveCommit(mainlineRef) : s.featureBase;
        return git.mergeBase(s.tenant, other);
    }

    public ConflictModel analyze() {
        model.mode = session.mode;
        model.repo = git.repo().toString();
        model.ours = history.commit(session.tenant);
        model.base = history.commit(session.featureBase);
        model.theirs = history.commit(session.featureTip);
        model.fork = history.commit(forkSha);
        for (String p : session.picks) model.picks.add(history.commit(p));
        model.pending.addAll(session.pending);
        model.messages.addAll(session.messages);
        model.mainlineCommitsInWindow = history.count(forkSha, session.featureBase);
        model.tenantCommitsSinceFork = history.count(forkSha, session.tenant);
        if (ctx.report().context == null) ctx.report().context = cb.model();

        for (int si = 0; si < session.steps.size(); si++) {
            ConflictSession.Step st = session.steps.get(si);
            for (Map.Entry<String, ConflictSession.Unmerged> e : st.unmerged.entrySet()) {
                try {
                    model.files.add(file(si + 1, st, e.getKey(), e.getValue()));
                } catch (RuntimeException | StackOverflowError ex) {
                    model.messages.add("analysis of " + e.getKey() + " failed: " + ex);
                }
            }
            for (String p : st.changed) if (!st.unmerged.containsKey(p) && !model.cleanFiles.contains(p)) model.cleanFiles.add(p);
        }
        cleanNeeds();
        cleanFileFindings();
        checklist();
        limitations();
        summary();
        return model;
    }

    // ------------------------------------------------------------------ files

    private ConflictFile file(int stepNo, ConflictSession.Step st, String path, ConflictSession.Unmerged u) {
        ConflictFile f = new ConflictFile();
        f.path = path;
        f.step = stepNo;
        f.kind = u.kind();
        u.sha.forEach((k, v) -> f.stages.put(String.valueOf(k), v));
        u.path.forEach((k, v) -> {
            if (!v.equals(path)) f.paths.put(k == 1 ? "base" : k == 2 ? "ours" : "theirs", v);
        });
        f.viewKey = "~conflict/" + stepNo + "/" + path;
        String b = u.sha.containsKey(1) ? git.blob(u.sha.get(1)) : null;
        String o = u.sha.containsKey(2) ? git.blob(u.sha.get(2)) : null;
        String t = u.sha.containsKey(3) ? git.blob(u.sha.get(3)) : null;
        contents.put(f.viewKey, new String[] {o, b, t});
        f.fileHistory.addAll(history.fileEvents(forkSha, st.base, path));
        f.explanation = explainFile(f, st, path, b, o, t);
        String eol = eolNote(o, b, t);
        if (eol != null) {
            f.eol = eol;
            f.explanation = append(f.explanation, eol);
            // hunks are computed on the content without CRs, so a line-ending change does not turn the file into one hunk
            o = stripCr(o);
            b = stripCr(b);
            t = stripCr(t);
        }
        List<MergeHunks.Region> regions;
        switch (f.kind) {
            case "DELETED_BY_US", "ADDED_BY_THEM" -> regions = MergeHunks.pickChanges(b, t, 2);
            case "DELETED_BY_THEM", "ADDED_BY_US" -> {
                regions = new ArrayList<>();
                for (MergeHunks.Region r : MergeHunks.pickChanges(b, o, 2)) regions.add(new MergeHunks.Region(r.theirs(), r.base(), new Range(1, 0)));
            }
            default -> regions = MergeHunks.regions(b, o, t);
        }
        int i = 0;
        for (MergeHunks.Region r : regions) {
            Hunk h = new Hunk();
            h.index = ++i;
            h.step = stepNo;
            h.path = path;
            h.id = (session.steps.size() > 1 ? "s" + stepNo + ":" : "") + path + "#" + i;
            h.ours = r.ours();
            h.base = r.base();
            h.theirs = r.theirs();
            h.oursText = slice(o, h.ours);
            h.baseText = slice(b, h.base);
            h.theirsText = slice(t, h.theirs);
            try {
                hunk(h, st, f, o, b, t);
            } catch (RuntimeException | StackOverflowError ex) {
                h.notes.add("analysis failed: " + ex);
            }
            f.hunks.add(h);
        }
        if (b != null && t != null && f.kind.equals("CONTENT")) {
            try {
                f.merged = mergedNeeds(st, path, b, t, f.hunks);
            } catch (RuntimeException | StackOverflowError ex) {
                model.messages.add("analysis of the merged part of " + path + " failed: " + ex);
            }
        }
        return f;
    }

    /**
     * What the pick's lines outside every conflict hunk need. git applies them without asking, so a symbol they use that
     * the tenant lacks (a mainline-only API, a library package) breaks the build after the conflicts are resolved.
     */
    private TakeTheirs mergedNeeds(ConflictSession.Step st, String path, String b, String t, List<Hunk> conflicts) {
        TakeTheirs res = new TakeTheirs();
        List<M> mt = members(path, t);
        Map<String, Need> all = new LinkedHashMap<>();
        int i = 0;
        for (MergeHunks.Region r : MergeHunks.pickChanges(b, t, 0)) {
            if (r.theirs().empty()) continue;
            boolean inConflict = false;
            for (Hunk h : conflicts) if (touches(h.base, r.base()) || touches(h.theirs, r.theirs())) inConflict = true;
            if (inConflict) continue;
            Hunk p = new Hunk();
            p.id = path + "#merged" + (++i);
            p.path = path;
            p.base = r.base();
            p.theirs = r.theirs();
            p.ours = new Range(1, 0);
            p.members.put("theirs", Members.locate(mt, r.theirs()));
            takeTheirs(p, st, t);
            for (Need n : p.takeTheirs.needs) {
                if (n.origin == null || n.origin.equals("IN_HUNK") || n.origin.equals("IN_PICK")) continue;
                if (!n.ours.equals(ContextModel.MISSING) && !n.ours.equals(ContextModel.CHANGED)) continue;
                all.putIfAbsent(n.node, n);
            }
            for (TreeNode tr : p.takeTheirs.trees) if (res.trees.size() < 8 && hasPort(tr)) res.trees.add(tr);
        }
        res.needs.addAll(all.values());
        res.summary = res.needs.isEmpty() ? "The lines git merged automatically use nothing the tenant lacks."
                : res.needs.size() + " symbol(s) used by lines git merged automatically are missing or different in the tenant: "
                + "the file will not compile (or behave) as merged even after every hunk is resolved.";
        return res;
    }

    /** whether two ranges overlap or are adjacent (empty ranges are insertion points) */
    static boolean touches(Range a, Range b) {
        int ae = Math.max(a.end(), a.start() - 1), be = Math.max(b.end(), b.start() - 1);
        return a.start() <= be + 1 && b.start() <= ae + 1;
    }

    private void cleanNeeds() {
        for (int si = 0; si < session.steps.size(); si++) {
            ConflictSession.Step st = session.steps.get(si);
            for (String path : st.changed) {
                if (st.unmerged.containsKey(path) || model.cleanNeeds.containsKey(path)) continue;
                String lower = path.toLowerCase();
                if (!lower.endsWith(".java") && !lower.endsWith(".xml")) continue;
                String b = git.show(st.base, path), t = git.show(st.pick, path);
                if (t == null) continue;
                try {
                    TakeTheirs tt = mergedNeeds(st, path, b == null ? "" : stripCr(b), stripCr(t), List.of());
                    if (!tt.needs.isEmpty()) model.cleanNeeds.put(path, tt);
                } catch (RuntimeException | StackOverflowError ex) {
                    model.messages.add("analysis of clean file " + path + " failed: " + ex);
                }
            }
        }
    }

    /** viewKey -> [ours, base, theirs] contents, used by the HTML renderer */
    public final Map<String, String[]> contents = new LinkedHashMap<>();

    private String explainFile(ConflictFile f, ConflictSession.Step st, String path, String b, String o, String t) {
        String atFork = git.show(forkSha, path);
        Event added = null, renamed = null;
        for (Event e : f.fileHistory) {
            if (e.kind.equals("FILE_ADDED") && added == null) added = e;
            if (e.kind.equals("FILE_RENAMED")) renamed = e;
        }
        switch (f.kind) {
            case "DELETED_BY_US": {
                if (atFork == null && renamed != null) {
                    boolean tenantHasOld = git.show(st.ours, renamed.from) != null;
                    return "The tenant does not have this file. Mainline renamed " + renamed.from + " to " + path + " in "
                            + c(renamed.commit) + (tenantHasOld ? "; the tenant still has the old path " + renamed.from
                            + " (git could not pair the two because the content diverged too much)" : "") + ".";
                }
                if (atFork == null && added != null) {
                    return "The tenant does not have this file: it was added on mainline after the fork by " + c(added.commit)
                            + ". The pick modifies it, so git reports modify/delete. Taking theirs adds the whole file as of the pick, "
                            + "including everything mainline put into it between " + short10(added.commit.sha()) + " and the pick.";
                }
                if (atFork != null) {
                    String del = tenantDeletion(st.ours, path);
                    return "The file existed at the fork; the tenant deleted or moved it" + (del == null ? "" : " in " + del)
                            + ". The pick modifies it.";
                }
                return "The tenant does not have this file; the pick modifies it.";
            }
            case "DELETED_BY_THEM":
                return "The pick deletes this file, but the tenant's version differs from the pick's parent (tenant or mainline "
                        + "changes the pick did not expect).";
            case "ADDED_BY_BOTH":
                return "Both the tenant and the pick add this path with different contents.";
            default: {
                if (atFork == null && added != null) {
                    return "The file was added on mainline after the fork by " + c(added.commit) + " and the tenant has a "
                            + "different version of it.";
                }
                int mainline = 0;
                for (Event e : f.fileHistory) if (!e.kind.equals("FILE_ADDED")) mainline++;
                return mainline == 0 ? "Mainline did not touch this file between the fork and the pick's parent; the conflict comes "
                        + "from tenant changes." : mainline + " mainline commit(s) changed this file between the fork and the pick's parent.";
            }
        }
    }

    static String eolStyle(String s) {
        if (s == null || s.isEmpty()) return null;
        int crlf = 0, lf = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                if (i > 0 && s.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        return crlf > 0 && lf > 0 ? "mixed" : crlf > 0 ? "CRLF" : "LF";
    }

    /** null when every present version uses the same line endings */
    static String eolNote(String o, String b, String t) {
        String eo = eolStyle(o), eb = eolStyle(b), et = eolStyle(t);
        Set<String> styles = new LinkedHashSet<>();
        for (String e : new String[] {eo, eb, et}) if (e != null) styles.add(e);
        if (styles.size() <= 1) return null;
        return "Line endings differ (ours " + nz(eo) + ", base " + nz(eb) + ", theirs " + nz(et) + "): git compares lines with their "
                + "CR, so every line may conflict. The hunks below ignore CRs; re-running the pick with `-X ignore-cr-at-eol` "
                + "(or `-X renormalize` with a .gitattributes eol rule) avoids the whole-file conflict.";
    }

    static String stripCr(String s) {
        return s == null ? null : s.replace("\r\n", "\n");
    }

    private String tenantDeletion(String tenant, String path) {
        GitRunner.Result r = git.exec("log", "-M", "--diff-filter=DR", "--format=%H", forkSha + ".." + tenant, "--", path);
        if (r.exit() != 0 || r.out().isBlank()) return null;
        return c(history.commit(r.out().split("\n")[0].trim()));
    }

    // ------------------------------------------------------------------ hunks

    private void hunk(Hunk h, ConflictSession.Step st, ConflictFile f, String o, String b, String t) {
        String path = h.path;
        List<M> mo = members(path, o), mb = members(path, b), mt = members(path, t);
        if (o != null) h.members.put("ours", Members.locate(mo, h.ours));
        if (b != null) h.members.put("base", Members.locate(mb, h.base));
        if (t != null) h.members.put("theirs", Members.locate(mt, h.theirs));
        why(h, st, f, mo, mb);
        takeTheirs(h, st, t);
        takeOurs(h, st, mo, mt, o, t);
        relatedFindings(h);
    }

    private List<M> members(String path, String content) {
        if (content == null) return List.of();
        return membersByContent.computeIfAbsent(path + "\u0000" + content, k -> Members.of(path, content));
    }

    // ------------------------------------------------------------------ why

    private void why(Hunk h, ConflictSession.Step st, ConflictFile f, List<M> mo, List<M> mb) {
        String basePath = f.paths.getOrDefault("base", h.path);
        String oursPath = f.paths.getOrDefault("ours", h.path);
        Set<String> seenEvents = new HashSet<>();
        Set<String> baseIds = new LinkedHashSet<>();
        for (Member m : h.members.getOrDefault("base", List.of())) baseIds.add(m.id());
        // mainline: lineage of the base-side members (what mainline did to them since the fork)
        if (!f.kind.equals("ADDED_BY_BOTH") && !f.kind.equals("ADDED_BY_US")) {
            for (Member m : h.members.getOrDefault("base", List.of())) {
                if (m.kind().equals("class")) continue;
                for (Event e : history.lineage(forkSha, st.base, basePath, m.id())) add(h.mainline, e, seenEvents);
            }
            // members ours has (from before the fork) that the base no longer has: renamed / moved / deleted on mainline
            Map<String, M> baseById = Members.byId(mb);
            for (Member m : h.members.getOrDefault("ours", List.of())) {
                if (baseById.containsKey(m.id()) || m.kind().equals("class")) continue;
                for (Event e : history.forward(forkSha, st.base, basePath, m.id())) add(h.mainline, e, seenEvents);
            }
            h.baseBlame.addAll(history.blame(forkSha, st.base, basePath, h.base.start(), h.base.end()));
            for (BlameRun r : h.baseBlame) {
                if (r.boundary()) continue;
                Event e = new Event(r.commit(), "LINES", null, null, null, "wrote base lines " + r.start() + (r.end() > r.start() ? "-" + r.end() : ""));
                add(h.mainline, e, seenEvents);
            }
            if (f.kind.equals("DELETED_BY_US")) {
                for (Event e : f.fileHistory) if (e.kind.equals("FILE_ADDED") || e.kind.equals("FILE_RENAMED")) add(h.mainline, e, seenEvents);
            }
        }
        // tenant: what the tenant did to the ours-side code since the fork
        if (st.ours != null && !f.kind.equals("DELETED_BY_US")) {
            Set<String> seenT = new HashSet<>();
            for (Member m : h.members.getOrDefault("ours", List.of())) {
                if (m.kind().equals("class")) continue;
                for (Event e : history.lineage(forkSha, st.ours, oursPath, m.id())) add(h.tenant, e, seenT);
            }
            h.oursBlame.addAll(history.blame(forkSha, st.ours, oursPath, h.ours.start(), h.ours.end()));
            for (BlameRun r : h.oursBlame) {
                if (r.boundary()) continue;
                add(h.tenant, new Event(r.commit(), "LINES", null, null, null, "wrote ours lines " + r.start()
                        + (r.end() > r.start() ? "-" + r.end() : "")), seenT);
            }
        }
        h.mainline.sort(Comparator.comparing(e -> e.commit.date()));
        h.tenant.sort(Comparator.comparing(e -> e.commit.date()));
        h.why = whyText(h, f);
    }

    private static void add(List<Event> into, Event e, Set<String> seen) {
        String k = e.commit.sha() + "|" + e.kind + "|" + e.member;
        if (e.kind.equals("LINES")) {
            // a commit already listed with a member-level change needs no extra "wrote lines" entry
            for (Event x : into) if (x.commit.sha().equals(e.commit.sha())) return;
        }
        if (seen.add(k)) into.add(e);
    }

    private String whyText(Hunk h, ConflictFile f) {
        StringBuilder sb = new StringBuilder();
        String where = labels(h.members.getOrDefault("base", h.members.getOrDefault("ours", List.of())));
        if (f.kind.equals("DELETED_BY_US")) {
            sb.append("The pick changes ").append(where.isEmpty() ? "this file" : where).append(" in a file the tenant does not have. ");
            sb.append(f.explanation == null ? "" : f.explanation);
            return sb.toString().trim();
        }
        sb.append("Both sides changed ").append(where.isEmpty() ? "the same lines" : where).append(" relative to the pick's parent. ");
        List<Event> structural = new ArrayList<>();
        Set<String> commits = new LinkedHashSet<>();
        for (Event e : h.mainline) {
            commits.add(short10(e.commit.sha()));
            if (!e.kind.equals("LINES") && !e.kind.equals("MODIFIED")) structural.add(e);
        }
        if (h.mainline.isEmpty()) {
            sb.append("Mainline did not change this code between the fork (").append(short10(forkSha)).append(") and the pick's parent");
        } else {
            sb.append("Mainline changed it after the fork in ").append(commits.size()).append(" commit(s) the tenant does not have");
            if (!structural.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Event e : structural) parts.add(describe(e));
                sb.append(": ").append(String.join("; ", parts));
            } else {
                List<String> parts = new ArrayList<>();
                for (Event e : h.mainline) parts.add(short10(e.commit.sha()) + " \"" + e.commit.subject() + "\"");
                sb.append(" (").append(String.join(", ", new LinkedHashSet<>(parts))).append(")");
            }
        }
        sb.append(". ");
        if (h.tenant.isEmpty()) {
            sb.append("The tenant did not edit these lines since the fork, so the ours side is simply the pre-fork code: "
                    + "the conflict is caused by the mainline changes the tenant lacks.");
        } else {
            Set<String> tc = new LinkedHashSet<>();
            for (Event e : h.tenant) tc.add(short10(e.commit.sha()) + " \"" + e.commit.subject() + "\"");
            sb.append("The tenant also edited this code since the fork (").append(String.join(", ", tc)).append("): keep those edits when resolving.");
        }
        return sb.toString();
    }

    static String describe(Event e) {
        String what = switch (e.kind) {
            case "ADDED" -> "added " + nz(e.to);
            case "DELETED" -> "deleted " + nz(e.from);
            case "RENAMED" -> "renamed " + nz(e.from) + " -> " + nz(e.to);
            case "MOVED" -> "moved " + nz(e.from) + " -> " + nz(e.to) + (e.detail != null && e.detail.startsWith("moved") ? " (" + e.detail + ")" : "");
            case "SIGNATURE_CHANGED" -> "changed the signature " + nz(e.from) + " -> " + nz(e.to);
            case "MODIFIED" -> "modified " + nz(e.to);
            case "FILE_ADDED" -> "added the file";
            case "FILE_RENAMED" -> "renamed the file " + nz(e.from) + " -> " + nz(e.to);
            case "FILE_DELETED" -> "deleted the file";
            default -> nz(e.detail);
        };
        return what + " in " + short10(e.commit.sha()) + " (" + day(e.commit.date()) + " \"" + e.commit.subject() + "\")";
    }

    // ------------------------------------------------------------------ take theirs

    private void takeTheirs(Hunk h, ConflictSession.Step st, String theirsContent) {
        if (theirsContent == null || h.theirs.empty()) {
            h.takeTheirs.summary = h.theirs.empty() ? "Theirs removes these lines; taking theirs needs nothing new." : "Theirs deletes the file.";
            if (theirsContent != null && h.baseText != null) movedImports(h, theirsContent);
            return;
        }
        // theirs lines of this step, mapped onto the feature tip the regular analysis indexed
        String tipContent = ctx.theirs().snapshot.files().get(h.path);
        if (tipContent == null) tipContent = ctx.theirs().snapshot.siblingFiles().get(h.path);
        Range r = h.theirs;
        if (tipContent != null && !tipContent.equals(theirsContent)) {
            List<String> a = LineHunks.lines(theirsContent), b = LineHunks.lines(tipContent);
            int s = MergeHunks.map(a, b, r.start() - 1);
            r = new Range(s + 1, s + r.size());
            h.notes.add("theirs lines mapped onto the feature tip " + short10(session.featureTip) + " for symbol resolution");
        }
        Set<String> declaredHere = new LinkedHashSet<>();
        for (Member m : h.members.getOrDefault("theirs", List.of())) declaredHere.add(m.id());
        Map<String, Need> needs = new LinkedHashMap<>();
        String lower = h.path.toLowerCase();
        if (lower.endsWith(".java") && tipContent != null) javaNeeds(h, r, needs, declaredHere);
        else if (lower.endsWith("pom.xml")) pomNeeds(h, needs);
        else if (lower.endsWith(".xml")) mapperNeeds(h, needs);
        h.takeTheirs.needs.addAll(needs.values());
        // transitive trees for everything not plainly present
        for (Need n : h.takeTheirs.needs) {
            if (n.ours.equals(ContextModel.PRESENT) || n.node == null || n.origin.equals("IN_HUNK")) continue;
            if (h.takeTheirs.trees.size() >= 12) break;
            TreeNode tree = cb.dependencyTree(n.node);
            h.takeTheirs.trees.add(tree);
            annotate(tree);
        }
        // theirs members of the hunk as roots too (when they are methods with their own dependencies)
        for (Member m : h.members.getOrDefault("theirs", List.of())) {
            if (!m.id().startsWith("M:") || h.takeTheirs.trees.size() >= 14) continue;
            TreeNode tree = cb.dependencyTree(m.id());
            if (!tree.children.isEmpty() && hasPort(tree)) {
                h.takeTheirs.trees.add(0, tree);
                annotate(tree);
            }
        }
        int missing = 0, changed = 0, renamed = 0;
        for (Need n : h.takeTheirs.needs) {
            if (n.origin.equals("IN_HUNK") || n.origin.equals("IN_PICK")) continue;
            if (n.ours.equals(ContextModel.MISSING)) missing++;
            if (n.ours.equals(ContextModel.CHANGED)) changed++;
            if (n.tenantName != null) renamed++;
        }
        h.takeTheirs.summary = !analyzable(h.path) ? "No symbol analysis for this file type (only Java, MyBatis XML, pom, YAML and properties are parsed)."
                : missing + changed == 0 ? "Everything the theirs side uses exists in the tenant (or arrives with the pick)."
                : missing + " symbol(s) missing in the tenant, " + changed + " with an incompatible signature"
                + (renamed > 0 ? ", " + renamed + " of them exist in the tenant under an older name / signature" : "") + ".";
    }

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;");

    /** imports removed by theirs in this hunk that theirs imports again elsewhere (reordered or renamed package) */
    private void movedImports(Hunk h, String theirsContent) {
        List<String> tl = LineHunks.lines(theirsContent);
        for (String line : h.baseText) {
            Matcher m = IMPORT.matcher(line);
            if (!m.find()) continue;
            String name = m.group(2), simple = name.substring(name.lastIndexOf('.') + 1);
            for (int i = 0; i < tl.size(); i++) {
                Matcher mt = IMPORT.matcher(tl.get(i));
                if (!mt.find()) continue;
                String other = mt.group(2);
                if (other.equals(name) || other.endsWith("." + simple)) {
                    h.takeTheirs.movedImports.add(other.equals(name) ? name + " moved to line " + (i + 1) : name + " -> " + other + " at line " + (i + 1));
                    break;
                }
            }
        }
        if (!h.takeTheirs.movedImports.isEmpty()) {
            h.takeTheirs.summary = "Theirs does not drop these imports, it moves them: " + String.join(", ", h.takeTheirs.movedImports)
                    + " (theirs' line numbers). The moved line merges automatically; whether the tenant can use it is checked under "
                    + "\"lines git merged automatically\".";
        }
    }

    private boolean hasPort(TreeNode t) {
        for (TreeNode c : t.children) {
            ContextModel.Node n = cb.model().nodes.get(c.node);
            if (n != null && n.needsPort() || hasPort(c)) return true;
        }
        return false;
    }

    private void javaNeeds(Hunk h, Range r, Map<String, Need> needs, Set<String> declaredHere) {
        CompilationUnit cu = ctx.theirs().unit(h.path);
        if (cu == null) return;
        // imports inside the range
        for (ImportDeclaration imp : cu.getImports()) {
            int l = Ast.line(imp);
            if (!r.overlaps(l, l) || imp.isAsterisk()) continue;
            String name = imp.isStatic() ? imp.getNameAsString().substring(0, imp.getNameAsString().lastIndexOf('.')) : imp.getNameAsString();
            if (ctx.theirs().isProjectType(name)) {
                addNeed(needs, new Dep(DepKind.TYPE, name, "", name, 0, true, List.of()), l, h, declaredHere);
            } else if (!name.startsWith("java.")) {
                Need n = libraryNeed(name, l);
                if (n != null) needs.putIfAbsent(n.node, n);
            }
        }
        for (ClassInfo c : ctx.theirs().classes.values()) {
            if (!c.file.equals(h.path)) continue;
            for (MethodInfo m : c.methods.values()) {
                if (m.node() == null || !r.overlaps(Ast.line(m.node()), Ast.endLine(m.node()))) continue;
                collectIn(m.node(), c, m.node(), r, needs, h, declaredHere);
            }
            for (FieldDeclaration fd : c.node().getFields()) {
                if (!r.overlaps(Ast.line(fd), Ast.endLine(fd))) continue;
                collectIn(fd, c, null, r, needs, h, declaredHere);
            }
            // class header (annotations, extends / implements) inside the range
            int hs = Ast.line(c.node());
            int he = c.node().getMembers().isEmpty() ? Ast.endLine(c.node()) : Ast.line(c.node().getMembers().get(0)) - 1;
            if (r.overlaps(hs, Math.max(hs, he))) {
                for (String sup : c.supertypes) {
                    if (ctx.theirs().isProjectType(sup)) addNeed(needs, new Dep(DepKind.TYPE, sup, "", sup, 0, true, List.of()), hs, h, declaredHere);
                }
            }
        }
    }

    /** dependencies of the maximal sub-nodes of {@code decl} that lie inside the range */
    private void collectIn(Node decl, ClassInfo c, CallableDeclaration<?> callable, Range r, Map<String, Need> needs, Hunk h,
                           Set<String> declaredHere) {
        List<Node> roots = new ArrayList<>();
        if (!r.empty() && Ast.line(decl) >= r.start() && Ast.endLine(decl) <= r.end()) roots.add(decl);
        else maximal(decl, r, roots);
        for (Node n : roots) {
            Map<Dep, Integer> found;
            try {
                found = deps.collect(n, c, callable);
            } catch (RuntimeException | StackOverflowError e) {
                continue;
            }
            for (Map.Entry<Dep, Integer> e : found.entrySet()) addNeed(needs, e.getKey(), e.getValue(), h, declaredHere);
        }
    }

    private static void maximal(Node n, Range r, List<Node> out) {
        for (Node c : n.getChildNodes()) {
            if (c instanceof com.github.javaparser.ast.comments.Comment) continue;
            int s = Ast.line(c), e = Ast.endLine(c);
            if (s == 0) continue;
            if (s >= r.start() && e <= r.end()) out.add(c);
            else if (s <= r.end() && e >= r.start()) maximal(c, r, out);
        }
    }

    private void addNeed(Map<String, Need> needs, Dep d, int line, Hunk h, Set<String> declaredHere) {
        String key = cb.keyOf(d, ctx.theirs());
        if (key == null || needs.containsKey(key)) return;
        if (key.startsWith("T:java.") || key.startsWith("M:java.")) return;
        Need n = new Need();
        n.node = key;
        ContextModel.Node node = cb.nodeOf(key);
        n.kind = node.kind;
        n.symbol = node.symbol;
        n.line = line;
        n.uncertain = !d.certain();
        DependencyAnalyzer.Lookup inOurs = deps.lookup(d, ctx.ours());
        if (!inOurs.ok() && (d.kind() == DepKind.METHOD || d.kind() == DepKind.METHOD_REF) && !inOurs.status().equals("MISSING_TYPE")) {
            String owner = d.candidateOwners().isEmpty() ? d.owner() : d.candidateOwners().get(0);
            if (Boolean.TRUE.equals(ctx.ours().externalSupertypeHasMethod(owner, d.name(), d.arity()))) inOurs = new DependencyAnalyzer.Lookup("OK", "inherited from a library supertype", null);
        }
        n.ours = inOurs.ok() ? (ContextModel.DIFFERS.equals(node.ours) ? ContextModel.DIFFERS : ContextModel.PRESENT)
                : inOurs.status().equals("INCONSISTENT") ? ContextModel.CHANGED : ContextModel.MISSING;
        n.oursLocation = inOurs.location();
        ContextModel.Loc tl = node.loc.get("theirs");
        n.theirsLocation = tl == null ? null : tl.location();
        if (!inOurs.detail().isBlank()) n.detail = inOurs.detail();
        if (declaredHere.contains(key) || isDeclaredInRange(key, h)) {
            n.origin = "IN_HUNK";
        } else {
            classify(n, d);
        }
        needs.put(key, n);
    }

    private boolean isDeclaredInRange(String key, Hunk h) {
        for (Member m : h.members.getOrDefault("theirs", List.of())) if (m.id().equals(key)) return true;
        return false;
    }

    private Set<String> tenantImports;

    /** imported names of every tenant Java source */
    private Set<String> tenantImports() {
        if (tenantImports != null) return tenantImports;
        tenantImports = new HashSet<>();
        Pattern imp = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
        for (Map<String, String> files : List.of(ctx.ours().snapshot.files(), ctx.ours().snapshot.siblingFiles())) {
            files.forEach((p, c) -> {
                if (!p.endsWith(".java")) return;
                Matcher m = imp.matcher(c);
                while (m.find()) tenantImports.add(m.group(1));
            });
        }
        return tenantImports;
    }

    /**
     * A library type theirs imports that the tenant cannot have: decided on the classpaths when both resolved, otherwise by
     * the tenant's own imports (no tenant source imports anything from that package). Also finds the tenant's counterpart
     * (same name under another root, e.g. javax.* for jakarta.*) and the mainline commit that started using the package.
     */
    private Need libraryNeed(String name, int line) {
        String node = "LIB:" + name;
        Need known = model.symbols.get(node);
        if (known != null) return copyAt(known, line);
        boolean classpath = ctx.librariesComparable() && ctx.ours().solver != null;
        String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (classpath) {
            if (!solves(ctx.theirs(), name) || solves(ctx.ours(), name)) return null;
        } else {
            Set<String> ti = tenantImports();
            if (ti.contains(name)) return null;
            for (String i : ti) if (i.startsWith(pkg + ".")) return null;
        }
        Need n = new Need();
        n.node = node;
        cb.nodeOf(node);
        n.kind = "library";
        n.symbol = name;
        n.line = line;
        n.ours = ContextModel.MISSING;
        n.origin = "LIBRARY";
        n.uncertain = !classpath;
        String tail = name.contains(".") ? name.substring(name.indexOf('.')) : name;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        String counterpart = null;
        for (String i : tenantImports()) {
            if (i.endsWith(tail) && !i.equals(name)) {
                counterpart = i;
                break;
            }
            if (counterpart == null && i.endsWith("." + simple) && !i.equals(name)) counterpart = i;
        }
        if (counterpart != null) {
            n.tenantName = counterpart;
            n.tenantLocation = null;
        }
        GitRunner.Result r = git.exec("log", "--reverse", "--format=%H", "-S", "import " + pkg + ".", forkSha + ".." + session.featureBase);
        if (r.exit() == 0 && !r.out().isBlank()) n.introducedBy.add(history.commit(r.out().trim().split("\n")[0].trim()));
        n.detail = (classpath ? "on theirs' classpath, not on the tenant's (Maven dependency / version)"
                : "no tenant source imports anything from " + pkg + " (no classpath to confirm)")
                + (counterpart != null ? "; the tenant uses " + counterpart : "")
                + (n.introducedBy.isEmpty() ? "" : "; mainline started using " + pkg + " in " + c(n.introducedBy.get(0)));
        model.symbols.put(node, n);
        return copyAt(n, line);
    }

    private static Need copyAt(Need s, int line) {
        Need n = new Need();
        n.node = s.node;
        n.kind = s.kind;
        n.symbol = s.symbol;
        n.line = line;
        n.ours = s.ours;
        n.origin = s.origin;
        n.uncertain = s.uncertain;
        n.tenantName = s.tenantName;
        n.tenantLocation = s.tenantLocation;
        n.introducedBy = s.introducedBy;
        n.detail = s.detail;
        return n;
    }

    private static boolean solves(CodeIndex idx, String fqn) {
        try {
            return idx.solver.tryToSolveType(fqn).isSolved();
        } catch (Throwable e) {
            return false;
        }
    }

    /** where a needed symbol comes from: the pick itself, mainline after the fork (and under which old name), the tenant */
    private void classify(Need n, Dep d) {
        if (n.ours.equals(ContextModel.PRESENT) || n.ours.equals(ContextModel.DIFFERS)) {
            n.origin = "PRESENT";
            return;
        }
        boolean inBase = exists(n.node, ctx.base());
        if (!inBase) {
            n.origin = "IN_PICK";
            n.detail = append(n.detail, "introduced by the pick itself; arrives with it if that part is applied");
            return;
        }
        Need known = model.symbols.get(n.node);
        if (known == null) {
            known = symbolOrigin(n.node);
            model.symbols.put(n.node, known);
        }
        n.origin = known.origin;
        n.tenantName = known.tenantName;
        n.tenantLocation = known.tenantLocation;
        n.introducedBy = known.introducedBy;
        n.detail = append(n.detail, known.detail);
    }

    /** history of a symbol the feature base has and the tenant lacks (or has differently) */
    private Need symbolOrigin(String key) {
        Need n = new Need();
        n.node = key;
        ContextModel.Node node = cb.nodeOf(key);
        n.kind = node.kind;
        n.symbol = node.symbol;
        n.ours = node.ours;
        String file = fileOf(key, ctx.base());
        boolean inFork = exists(key, fork);
        if (inFork) {
            n.origin = "CHANGED_BY_TENANT";
            Change tr = tenantRenames().get(key);
            if (tr != null && tr.after() != null) {
                n.tenantName = tr.after().member().label();
                n.tenantLocation = tr.after().member().path() + ":" + tr.after().member().start();
                n.detail = "the tenant " + tr.kind().name().toLowerCase().replace('_', ' ') + " it after the fork";
            } else if (!exists(key, ctx.ours())) {
                n.detail = "existed at the fork; the tenant deleted it";
            } else {
                n.detail = "the tenant changed it after the fork";
            }
            return n;
        }
        Change mr = mainlineRenames().get(key);
        List<Event> lineage = file == null ? List.of() : history.lineage(forkSha, session.featureBase, file, key);
        for (Event e : lineage) {
            if (!e.kind.equals("MODIFIED") && !n.introducedBy.contains(e.commit)) n.introducedBy.add(e.commit);
        }
        if (n.introducedBy.isEmpty() && file != null) {
            for (Event e : history.fileEvents(forkSha, session.featureBase, file)) {
                if (e.kind.equals("FILE_ADDED") || e.kind.equals("FILE_RENAMED")) n.introducedBy.add(e.commit);
            }
        }
        if (mr != null && mr.before() != null) {
            String oldId = mr.before().id();
            boolean tenantHasOld = exists(oldId, ctx.ours());
            n.origin = mr.kind() == MemberDiff.Kind.SIGNATURE_CHANGED ? "SIGNATURE_CHANGED_ON_MAINLINE"
                    : mr.kind() == MemberDiff.Kind.MOVED ? "MOVED_ON_MAINLINE" : "RENAMED_ON_MAINLINE";
            if (tenantHasOld) {
                n.tenantName = mr.before().member().label();
                n.tenantLocation = locationOf(oldId, ctx.ours());
            }
            n.detail = "mainline " + mr.kind().name().toLowerCase().replace('_', ' ') + " " + mr.before().member().label() + " -> "
                    + mr.after().member().label() + (tenantHasOld ? "; the tenant still has the old one" : "");
            return n;
        }
        // a type the tenant lacks may be a renamed / moved file
        if (key.startsWith("T:") && file != null) {
            String old = history.renames(forkSha, session.featureBase).get(file);
            if (old != null && git.show(session.tenant, old) != null) {
                n.origin = "RENAMED_ON_MAINLINE";
                n.tenantName = old;
                n.tenantLocation = old + ":1";
                n.detail = "mainline renamed / moved the file " + old + " -> " + file;
                return n;
            }
        }
        n.origin = "ADDED_ON_MAINLINE";
        n.detail = "added on mainline after the fork" + (n.introducedBy.isEmpty() ? "" : " by " + short10(n.introducedBy.get(0).sha()));
        if (node.ours.equals(ContextModel.CHANGED)) {
            n.origin = "SIGNATURE_CHANGED_ON_MAINLINE";
            n.detail = "the tenant has another signature" + (node.loc.get("ours") != null ? " at " + node.loc.get("ours").location() : "");
        }
        return n;
    }

    /** records the origin of every MISSING / CHANGED node of a tree */
    private void annotate(TreeNode t) {
        ContextModel.Node n = cb.model().nodes.get(t.node);
        if (n != null && (ContextModel.MISSING.equals(n.ours) || ContextModel.CHANGED.equals(n.ours)) && !model.symbols.containsKey(t.node)
                && !t.node.startsWith("LIB:") && !t.node.startsWith("MVN:")) {
            Need s;
            if (exists(t.node, ctx.base())) {
                s = symbolOrigin(t.node);
            } else {
                s = new Need();
                s.node = t.node;
                s.kind = n.kind;
                s.symbol = n.symbol;
                s.ours = n.ours;
                s.origin = "IN_PICK";
                s.detail = "introduced by the pick itself";
            }
            model.symbols.put(t.node, s);
            if (!s.introducedBy.isEmpty() && (n.note == null || !n.note.contains(short10(s.introducedBy.get(0).sha())))) {
                n.note = append(n.note, s.origin.toLowerCase().replace('_', ' ') + " by " + short10(s.introducedBy.get(0).sha())
                        + " \"" + s.introducedBy.get(0).subject() + "\"" + (s.tenantName != null ? "; tenant has " + s.tenantName : ""));
            }
        }
        for (TreeNode c : t.children) annotate(c);
    }

    private void pomNeeds(Hunk h, Map<String, Need> needs) {
        Map<String, String> oursPoms = new LinkedHashMap<>();
        ctx.ours().snapshot.files().forEach((p, c) -> {
            if (p.endsWith("pom.xml")) oursPoms.put(p, c);
        });
        for (Member m : h.members.getOrDefault("theirs", List.of())) {
            if (!m.kind().equals("maven-dependency") && !m.kind().equals("maven-property")) continue;
            Need n = new Need();
            n.node = m.id();
            n.kind = m.kind();
            n.symbol = m.label();
            n.line = m.start();
            String art = m.id().substring(m.id().lastIndexOf(':') + 1);
            boolean found = false;
            for (String c : oursPoms.values()) {
                if (m.kind().equals("maven-dependency") ? c.contains("<artifactId>" + art + "</artifactId>") : c.contains("<" + art + ">")) found = true;
            }
            n.ours = found ? ContextModel.PRESENT : ContextModel.MISSING;
            n.origin = found ? "PRESENT" : "ADDED_ON_MAINLINE";
            n.detail = found ? "declared in a tenant pom (version may differ)" : "not declared in any tenant pom";
            needs.putIfAbsent(n.node, n);
        }
    }

    private static final Pattern REFID = Pattern.compile("\\b(refid|resultMap)\\s*=\\s*\"([\\w.]+)\"");
    private static final Pattern XTYPE = Pattern.compile("\\b(resultType|parameterType|type)\\s*=\\s*\"([\\w.]+)\"");

    private void mapperNeeds(Hunk h, Map<String, Need> needs) {
        String text = String.join("\n", h.theirsText);
        String ns = null;
        for (Member m : h.members.getOrDefault("theirs", List.of())) if (m.id().startsWith("SQL:")) ns = m.id().substring(4, m.id().indexOf('#'));
        if (ns == null) {
            Matcher nm = Pattern.compile("namespace\\s*=\\s*\"([^\"]+)\"").matcher(String.join("\n", contents.get(viewKeyOf(h))[2] == null ? "" : contents.get(viewKeyOf(h))[2]));
            if (nm.find()) ns = nm.group(1);
        }
        if (ns == null) return;
        Matcher m = REFID.matcher(text);
        while (m.find()) {
            String id = m.group(2);
            String key = "SQL:" + (id.contains(".") ? id.substring(0, id.lastIndexOf('.')) + "#" + id.substring(id.lastIndexOf('.') + 1) : ns + "#" + id);
            Need n = sqlNeed(key, m.group(1) + "=\"" + id + "\"");
            needs.putIfAbsent(key, n);
        }
        Matcher t = XTYPE.matcher(text);
        while (t.find()) {
            String fqn = t.group(2);
            if (!fqn.contains(".") || fqn.startsWith("java.")) continue;
            if (!ctx.theirs().isProjectType(fqn)) continue;
            addNeed(needs, new Dep(DepKind.TYPE, fqn, "", fqn, 0, true, List.of()), 0, h, Set.of());
        }
        // every statement of the hunk needs its mapper interface method
        for (Member sm : h.members.getOrDefault("theirs", List.of())) {
            if (!sm.kind().equals("statement")) continue;
            String id = sm.id().substring(sm.id().indexOf('#') + 1);
            String sns = sm.id().substring(4, sm.id().indexOf('#'));
            List<MethodInfo> inTheirs = ctx.theirs().findMethodsByName(sns, id);
            if (inTheirs.isEmpty()) continue;
            String key = "M:" + inTheirs.get(0).id();
            if (needs.containsKey(key)) continue;
            Need n = new Need();
            n.node = key;
            ContextModel.Node node = cb.nodeOf(key);
            n.kind = node.kind;
            n.symbol = node.symbol;
            n.line = sm.start();
            n.ours = ctx.ours().findMethodsByName(sns, id).isEmpty() ? ContextModel.MISSING : ContextModel.PRESENT;
            n.detail = "mapper interface method of statement " + id;
            if (n.ours.equals(ContextModel.MISSING)) classify(n, null);
            else n.origin = "PRESENT";
            needs.put(key, n);
        }
    }

    private Need sqlNeed(String key, String usage) {
        Need n = new Need();
        n.node = key;
        ContextModel.Node node = cb.nodeOf(key);
        n.kind = node.kind;
        n.symbol = node.symbol;
        n.ours = exists(key, ctx.ours()) ? ContextModel.PRESENT : ContextModel.MISSING;
        n.detail = usage;
        if (n.ours.equals(ContextModel.MISSING)) classify(n, null);
        else n.origin = "PRESENT";
        return n;
    }

    private String viewKeyOf(Hunk h) {
        return "~conflict/" + h.step + "/" + h.path;
    }

    // ------------------------------------------------------------------ take ours

    private void takeOurs(Hunk h, ConflictSession.Step st, List<M> mo, List<M> mt, String o, String t) {
        Map<String, M> theirsById = Members.byId(mt), oursById = Members.byId(mo);
        List<String> starts = new ArrayList<>();
        for (Member m : h.members.getOrDefault("ours", List.of())) {
            if (m.kind().equals("imports")) continue;
            M om = oursById.get(m.id()), tm = theirsById.get(m.id());
            // a member both sides have identically is not affected by the choice
            if (om != null && tm != null && om.body().equals(tm.body()) && t != null) continue;
            h.takeOurs.symbols.add(m.id());
            if (m.id().startsWith("M:") || m.id().startsWith("F:") || m.id().startsWith("SQL:") || m.id().startsWith("CFG:")) {
                cb.nodeOf(m.id());
                TreeNode impact = cb.impactTreeOf(m.id());
                h.takeOurs.impact.add(impact);
                starts.add(m.id());
                // mapper statements are reached through their interface method
                if (m.id().startsWith("SQL:")) {
                    String ns = m.id().substring(4, m.id().indexOf('#')), id = m.id().substring(m.id().indexOf('#') + 1);
                    for (MethodInfo mi : ctx.ours().findMethodsByName(ns, id)) starts.add("M:" + mi.id());
                }
            }
        }
        if (!starts.isEmpty()) {
            EntryPoints ep = new EntryPoints(ctx.ours(), cb::usersOf);
            h.takeOurs.entryPoints.addAll(ep.trace(starts));
            for (EntryPoint e : h.takeOurs.entryPoints) for (String k : e.path) cb.nodeOf(k);
        }
        // what the theirs side brings that keeping ours drops
        Set<String> theirsDeclared = new LinkedHashSet<>();
        for (Member m : h.members.getOrDefault("theirs", List.of())) {
            M om = oursById.get(m.id()), tm = theirsById.get(m.id());
            if (om == null) {
                h.takeOurs.lost.add("theirs adds " + m.kind() + " " + m.label());
                theirsDeclared.add(m.id());
            } else if (tm != null && !tm.body().equals(om.body())) {
                h.takeOurs.lost.add("theirs changes " + m.kind() + " " + m.label());
            }
        }
        if (h.takeOurs.lost.isEmpty() && !h.theirs.empty()) h.takeOurs.lost.add("theirs' edit of lines " + h.theirs + " (no member-level difference)");
        // code of the pick (other hunks and cleanly merged files) that uses what only theirs' side declares
        if (!theirsDeclared.isEmpty()) orphans(h, theirsDeclared);
        h.takeOurs.summary = (h.takeOurs.entryPoints.isEmpty() ? "No tenant entry point reaches the ours-side code"
                : h.takeOurs.entryPoints.size() + " tenant entry point(s) reach the ours-side code")
                + (h.takeOurs.orphans.isEmpty() ? "." : "; keeping ours leaves " + h.takeOurs.orphans.size()
                + " place(s) of the pick referencing symbols only theirs' side provides.");
    }

    private void orphans(Hunk h, Set<String> theirsDeclared) {
        for (MethodChange ch : ctx.theirsChangesAll().changes) {
            MethodInfo m = ch.newMethod;
            if (m == null || m.node() == null) continue;
            String self = "M:" + m.id();
            if (theirsDeclared.contains(self)) continue;
            Map<Dep, Integer> found;
            try {
                found = deps.collect(m);
            } catch (RuntimeException | StackOverflowError e) {
                continue;
            }
            for (Map.Entry<Dep, Integer> e : found.entrySet()) {
                String k = cb.keyOf(e.getKey(), ctx.theirs());
                if (k == null || !theirsDeclared.contains(k)) continue;
                if (m.owner.file.equals(h.path) && h.theirs.overlaps(e.getValue(), e.getValue())) continue;
                cb.nodeOf(self);
                h.takeOurs.orphans.add(new Orphan(k, self, m.owner.file + ":" + e.getValue(),
                        (model.cleanFiles.contains(m.owner.file) || !m.owner.file.equals(h.path) ? "merged cleanly / other file" : "same file")
                                + "; " + ch.kind.name().toLowerCase() + " by the pick"));
            }
        }
        // mapper statements declared by theirs' side, used by mapper methods the pick adds
        for (String k : theirsDeclared) {
            if (!k.startsWith("SQL:")) continue;
            String ns = k.substring(4, k.indexOf('#')), id = k.substring(k.indexOf('#') + 1);
            for (MethodInfo mi : ctx.theirs().findMethodsByName(ns, id)) {
                h.takeOurs.orphans.add(new Orphan(k, "M:" + mi.id(), mi.location(), "mapper method bound to this statement"));
                cb.nodeOf("M:" + mi.id());
            }
        }
        // config keys declared by theirs' side, read by @Value in theirs
        for (String k : theirsDeclared) {
            if (!k.startsWith("CFG:")) continue;
            String key = k.substring(4);
            for (var v : ctx.theirs().valueRefs) {
                if (v.key().equals(key) && !ctx.ours().config.has(key)) {
                    h.takeOurs.orphans.add(new Orphan(k, "T:" + v.ownerFqn(), v.location(), "reads " + key));
                }
            }
        }
    }

    // ------------------------------------------------------------------ findings

    private void relatedFindings(Hunk h) {
        for (Finding f : ctx.findings()) {
            boolean hit = false;
            for (Finding.Usage u : f.neededBy) if (inHunk(u.location(), h, "theirs")) hit = true;
            if (inHunk(f.theirsLocation, h, "theirs") || inHunk(f.oursLocation, h, "ours")) hit = true;
            if (hit && !h.findings.contains(f.id)) h.findings.add(f.id);
        }
    }

    private static boolean inHunk(String loc, Hunk h, String side) {
        if (loc == null) return false;
        int c = loc.lastIndexOf(':');
        if (c < 0 || !loc.substring(0, c).equals(h.path)) return false;
        int line;
        try {
            line = Integer.parseInt(loc.substring(c + 1).replaceAll("-.*", ""));
        } catch (NumberFormatException e) {
            return false;
        }
        List<Member> ms = h.members.getOrDefault(side, List.of());
        Range r = side.equals("ours") ? h.ours : h.theirs;
        if (r.overlaps(line, line)) return true;
        for (Member m : ms) if (!m.kind().equals("class") && line >= m.start() && line <= m.end()) return true;
        return false;
    }

    private void cleanFileFindings() {
        Set<String> conflictPaths = new HashSet<>();
        for (ConflictFile f : model.files) conflictPaths.add(f.path);
        for (Finding f : ctx.findings()) {
            boolean inConflictHunk = false;
            for (ConflictFile cf : model.files) for (Hunk h : cf.hunks) if (h.findings.contains(f.id)) inConflictHunk = true;
            if (inConflictHunk) continue;
            model.cleanFileFindings.add(f.id);
        }
    }

    // ------------------------------------------------------------------ checklist

    private void checklist() {
        // symbols to bring over, dependencies first (post-order over every take-theirs tree)
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Map<String, Set<String>> neededByHunk = new LinkedHashMap<>();
        for (ConflictFile f : model.files) {
            for (Hunk h : f.hunks) {
                for (TreeNode t : h.takeTheirs.trees) post(t, order, done, new HashSet<>(), neededByHunk, h.id);
                for (Need n : h.takeTheirs.needs) {
                    if (n.node == null || n.origin == null) continue;
                    if (n.ours.equals(ContextModel.MISSING) || n.ours.equals(ContextModel.CHANGED)) {
                        neededByHunk.computeIfAbsent(n.node, k -> new LinkedHashSet<>()).add(h.id);
                        if (done.add(n.node)) order.add(n.node);
                    }
                }
            }
        }
        for (ConflictFile f : model.files) {
            if (f.merged != null) collectNeeds(f.merged, f.path + " (auto-merged lines)", order, done, neededByHunk);
        }
        model.cleanNeeds.forEach((p, tt) -> collectNeeds(tt, p + " (merged cleanly)", order, done, neededByHunk));
        Map<String, CommitNeed> commits = new LinkedHashMap<>();
        int step = 0;
        List<ChecklistItem> adapt = new ArrayList<>(), bring = new ArrayList<>();
        Map<String, BringGroup> groups = new LinkedHashMap<>();
        for (String key : order) {
            Need s = model.symbols.get(key);
            if (s == null || s.origin == null || s.origin.equals("IN_PICK") || s.origin.equals("IN_HUNK")) continue;
            List<String> hunks = new ArrayList<>(neededByHunk.getOrDefault(key, Set.of()));
            if (s.origin.equals("LIBRARY")) {
                String why = nz(s.detail) + ". " + (s.tenantName != null ? "Rewrite the picked code to the tenant's " + s.tenantName
                        : "Add the library to the tenant's build, or rewrite the picked code without it")
                        + (s.introducedBy.isEmpty() ? "" : " (taking the library means taking " + commitList(s.introducedBy) + ")") + ".";
                List<String> lcs = new ArrayList<>();
                for (Commit c : s.introducedBy) lcs.add(c.sha());
                // not offered as a commit to bring: adopting a library (often a framework upgrade) is a decision of its own
                adapt.add(new ChecklistItem(0, "ADAPT", label(key), key, why, hunks, lcs));
                continue;
            }
            String file = fileOf(key, ctx.base());
            if (file == null) file = fileOf(key, ctx.theirs());
            ConflictFile cfile = conflictFile(file);
            // declared inside a conflict hunk's theirs side: resolving that hunk with theirs brings it (see RESOLVE)
            if (s.tenantName == null && cfile != null && cfile.kind.equals("CONTENT") && declaredInTheirsHunk(key, cfile)) continue;
            List<String> cs = new ArrayList<>();
            for (Commit c : s.introducedBy) {
                cs.add(c.sha());
                CommitNeed cn = commits.get(c.sha());
                List<String> provides = cn == null ? new ArrayList<>() : new ArrayList<>(cn.provides());
                List<String> needed = cn == null ? new ArrayList<>() : new ArrayList<>(cn.neededBy());
                if (!provides.contains(label(key))) provides.add(label(key));
                for (String hh : hunks) if (!needed.contains(hh)) needed.add(hh);
                commits.put(c.sha(), new CommitNeed(c, provides, needed, false));
            }
            if (s.tenantName != null) {
                adapt.add(new ChecklistItem(0, "ADAPT", label(key), key, "theirs uses " + label(key) + "; the tenant has " + s.tenantName
                        + (s.tenantLocation != null ? " (" + s.tenantLocation + ")" : "") + ". Either rename the tenant code (bring "
                        + commitList(s.introducedBy) + ") or rewrite the picked code to the tenant name.", hunks, cs));
            } else if (s.origin.equals("CHANGED_BY_TENANT")) {
                adapt.add(new ChecklistItem(0, "ADAPT", label(key), key, nz(s.detail) + ": decide whether the pick may use it as on mainline.", hunks, cs));
            } else {
                String g = file == null ? key : file;
                BringGroup bg = groups.computeIfAbsent(g, k -> new BringGroup());
                bg.file = file;
                if (bg.firstKey == null) bg.firstKey = key;
                if (bg.detail == null) bg.detail = s.detail;
                bg.symbols.add(label(key));
                bg.hunks.addAll(hunks);
                bg.commits.addAll(s.introducedBy);
            }
        }
        for (BringGroup bg : groups.values()) {
            List<String> cs = new ArrayList<>();
            for (Commit c : bg.commits) cs.add(c.sha());
            String from = bg.commits.isEmpty() ? "" : " (from " + commitList(new ArrayList<>(bg.commits)) + ")";
            if (bg.file == null) {
                bring.add(new ChecklistItem(0, "BRING", bg.symbols.get(0), bg.firstKey, nz(bg.detail) + from, new ArrayList<>(bg.hunks), cs));
                continue;
            }
            ConflictFile cf = conflictFile(bg.file);
            boolean tenantHas = git.show(session.tenant, bg.file) != null;
            String what = String.join(", ", bg.symbols);
            if (cf != null && (cf.kind.equals("DELETED_BY_US") || cf.kind.equals("ADDED_BY_THEM"))) {
                bring.add(new ChecklistItem(0, "BRING_FILE", bg.file, bg.firstKey, "the tenant lacks this file; resolve its "
                        + cf.kind.toLowerCase().replace('_', ' ') + " conflict by adding the pick's version (git add), which brings "
                        + what + from, new ArrayList<>(bg.hunks), cs));
            } else if (!tenantHas) {
                bring.add(new ChecklistItem(0, "BRING_FILE", bg.file, bg.firstKey, "the tenant lacks this file; add mainline's version (git checkout "
                        + short10(session.featureBase) + " -- " + bg.file + ") for " + what + from, new ArrayList<>(bg.hunks), cs));
            } else {
                bring.add(new ChecklistItem(0, "BRING", bg.file, bg.firstKey, "add to the tenant's file: " + what + from
                        + "; the pick does not carry these lines (mainline wrote them before the pick)", new ArrayList<>(bg.hunks), cs));
            }
        }
        List<CommitNeed> cl = new ArrayList<>(commits.values());
        cl.sort(Comparator.comparing(c -> c.commit().date()));
        model.bringAlong.addAll(cl);
        for (CommitNeed c : cl) {
            model.checklist.add(new ChecklistItem(++step, "CONSIDER_COMMIT", short10(c.commit().sha()) + " " + c.commit().subject(), null,
                    "introduced " + firstN(c.provides(), 8) + " that the pick needs (" + day(c.commit().date())
                            + "); cherry-pick it first or port just those symbols", c.neededBy(), List.of(c.commit().sha())));
        }
        for (ChecklistItem i : bring) model.checklist.add(renumber(i, ++step));
        for (ChecklistItem i : adapt) model.checklist.add(renumber(i, ++step));
        for (ConflictFile f : model.files) {
            for (Hunk h : f.hunks) {
                String rec = recommendation(h, f);
                model.checklist.add(new ChecklistItem(++step, "RESOLVE", h.id + " " + labels(h.members.getOrDefault("theirs",
                        h.members.getOrDefault("ours", List.of()))), null, rec, List.of(h.id), List.of()));
            }
        }
        for (Integer id : model.cleanFileFindings) {
            Finding f = byId(id);
            if (f == null || f.severity == Finding.Severity.LOW) continue;
            model.checklist.add(new ChecklistItem(++step, "CHECK_FINDING", "#" + id + " " + f.symbol, null,
                    f.status + ": " + f.message, List.of(), List.of()));
        }
    }

    private static final class BringGroup {
        String file, firstKey, detail;
        final List<String> symbols = new ArrayList<>();
        final Set<String> hunks = new LinkedHashSet<>();
        final Set<Commit> commits = new LinkedHashSet<>();
    }

    private ConflictFile conflictFile(String path) {
        if (path == null) return null;
        for (ConflictFile f : model.files) if (f.path.equals(path)) return f;
        return null;
    }

    private static boolean declaredInTheirsHunk(String key, ConflictFile f) {
        for (Hunk h : f.hunks) {
            for (Member m : h.members.getOrDefault("theirs", List.of())) {
                if (m.id().equals(key) && m.start() >= h.theirs.start() && m.end() <= h.theirs.end()) return true;
                if (m.id().equals(key) && !m.kind().equals("class")) return true;
            }
        }
        return false;
    }

    private void collectNeeds(TakeTheirs tt, String where, List<String> order, Set<String> done, Map<String, Set<String>> byHunk) {
        for (TreeNode t : tt.trees) post(t, order, done, new HashSet<>(), byHunk, where);
        for (Need n : tt.needs) {
            byHunk.computeIfAbsent(n.node, k -> new LinkedHashSet<>()).add(where);
            if (done.add(n.node)) order.add(n.node);
        }
    }

    private static ChecklistItem renumber(ChecklistItem i, int step) {
        return new ChecklistItem(step, i.action(), i.target(), i.node(), i.why(), i.hunks(), i.commits());
    }

    private void post(TreeNode t, List<String> out, Set<String> done, Set<String> path, Map<String, Set<String>> byHunk, String hunk) {
        if (!path.add(t.node)) return;
        for (TreeNode c : t.children) post(c, out, done, path, byHunk, hunk);
        path.remove(t.node);
        ContextModel.Node n = cb.model().nodes.get(t.node);
        if (n != null && (ContextModel.MISSING.equals(n.ours) || ContextModel.CHANGED.equals(n.ours))) {
            byHunk.computeIfAbsent(t.node, k -> new LinkedHashSet<>()).add(hunk);
            if (done.add(t.node)) out.add(t.node);
        }
    }

    private String recommendation(Hunk h, ConflictFile f) {
        List<String> parts = new ArrayList<>();
        int missing = 0;
        for (Need n : h.takeTheirs.needs) {
            if (n.origin != null && !n.origin.equals("IN_HUNK") && !n.origin.equals("IN_PICK")
                    && (n.ours.equals(ContextModel.MISSING) || n.ours.equals(ContextModel.CHANGED))) missing++;
        }
        int mergedMissing = f.merged == null ? 0 : f.merged.needs.size();
        if (!h.takeTheirs.movedImports.isEmpty() && h.tenant.isEmpty()) {
            parts.add("keep ours here: theirs only moved the import(s) (" + String.join(", ", h.takeTheirs.movedImports) + ")"
                    + (mergedMissing > 0 ? "; the moved copy merged automatically and uses what the tenant lacks, so remove it or rewrite it (see ADAPT)" : ""));
        } else if (f.kind.equals("DELETED_BY_US")) {
            parts.add("the tenant lacks the file: adding it brings mainline's whole version (check the BRING items above and the file's history)");
        } else if (!h.tenant.isEmpty()) {
            parts.add("merge by hand: keep the tenant's edits (" + commitList(commitsOf(h.tenant)) + ") and add theirs' change");
        } else if (!analyzable(h.path)) {
            parts.add("no symbol analysis for this file type: compare both sides by hand");
        } else if (missing == 0) {
            parts.add("taking theirs is self-contained (everything it uses exists in the tenant)");
        } else {
            parts.add("taking theirs needs " + missing + " symbol(s) the tenant lacks (see BRING / ADAPT)");
        }
        if (mergedMissing > 0 && h.takeTheirs.movedImports.isEmpty() && f.hunks.indexOf(h) == 0) {
            parts.add("whatever you choose, " + mergedMissing + " symbol(s) used by the automatically merged lines of this file are missing in the tenant (see ADAPT / BRING)");
        }
        if (!h.takeOurs.orphans.isEmpty()) parts.add("do not simply keep ours: " + h.takeOurs.orphans.size() + " place(s) of the pick use what theirs' side declares");
        if (!h.takeOurs.entryPoints.isEmpty()) {
            List<String> routes = new ArrayList<>();
            for (EntryPoint e : h.takeOurs.entryPoints) if (routes.size() < 4) routes.add(e.route);
            parts.add("retest " + String.join(", ", routes) + (h.takeOurs.entryPoints.size() > 4 ? " …" : ""));
        }
        return String.join("; ", parts);
    }

    static boolean analyzable(String path) {
        String l = path.toLowerCase();
        return l.endsWith(".java") || l.endsWith(".xml") || l.endsWith(".yml") || l.endsWith(".yaml") || l.endsWith(".properties");
    }

    private static List<Commit> commitsOf(List<Event> es) {
        List<Commit> res = new ArrayList<>();
        for (Event e : es) if (!res.contains(e.commit)) res.add(e.commit);
        return res;
    }

    private Finding byId(int id) {
        for (Finding f : ctx.findings()) if (f.id == id) return f;
        return null;
    }

    // ------------------------------------------------------------------ rename maps

    private Map<String, Change> mainlineRenames() {
        if (mainlineRenames == null) mainlineRenames = renameMap(forkSha, session.featureBase, true);
        return mainlineRenames;
    }

    private Map<String, Change> tenantRenames() {
        if (tenantRenames == null) tenantRenames = renameMap(forkSha, session.tenant, false);
        return tenantRenames;
    }

    /** member renames / moves / signature changes between two revisions over all changed source files */
    private Map<String, Change> renameMap(String from, String to, boolean byNewId) {
        Map<String, Change> res = new LinkedHashMap<>();
        GitRunner.Result r = git.exec("-c", "core.quotePath=false", "diff", "-M", "--name-status", from, to);
        if (r.exit() != 0) return res;
        List<M> before = new ArrayList<>(), after = new ArrayList<>();
        for (String l : r.out().split("\n")) {
            String[] f = l.split("\t");
            if (f.length < 2) continue;
            String st = f[0].substring(0, 1), oldP = f[1], newP = f.length > 2 ? f[2] : f[1];
            if (!(newP.endsWith(".java") || newP.endsWith(".xml")) || newP.contains("/src/test/")) continue;
            if (!st.equals("A")) before.addAll(history.members(from, oldP));
            if (!st.equals("D")) after.addAll(history.members(to, newP));
        }
        for (Change c : MemberDiff.diff(before, after)) {
            if (c.kind() == MemberDiff.Kind.RENAMED || c.kind() == MemberDiff.Kind.MOVED || c.kind() == MemberDiff.Kind.SIGNATURE_CHANGED) {
                res.put(byNewId ? c.after().id() : c.before().id(), c);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ symbol lookups

    static boolean exists(String key, CodeIndex idx) {
        String body = key.substring(key.indexOf(':') + 1);
        switch (key.substring(0, key.indexOf(':'))) {
            case "M": {
                if (idx.methodsById.containsKey(body)) return true;
                int h = body.indexOf('#');
                if (h < 0) return false;
                return idx.findMethod(body.substring(0, h), body.substring(h + 1)) != null;
            }
            case "F": {
                int d = body.lastIndexOf('.');
                return d > 0 && idx.findField(body.substring(0, d), body.substring(d + 1)) != null;
            }
            case "T":
                return idx.classes.containsKey(body);
            case "SQL": {
                int h = body.indexOf('#');
                return h > 0 && idx.mapperXml.statements(body.substring(0, h)).containsKey(body.substring(h + 1));
            }
            case "CFG":
                return idx.config.has(body);
            default:
                return false;
        }
    }

    static String fileOf(String key, CodeIndex idx) {
        String body = key.substring(key.indexOf(':') + 1);
        switch (key.substring(0, key.indexOf(':'))) {
            case "M": {
                MethodInfo m = idx.methodsById.get(body);
                if (m == null && body.contains("#")) m = idx.findMethod(body.substring(0, body.indexOf('#')), body.substring(body.indexOf('#') + 1));
                return m == null ? null : m.owner.file;
            }
            case "F": {
                int d = body.lastIndexOf('.');
                FieldInfo f = d > 0 ? idx.findField(body.substring(0, d), body.substring(d + 1)) : null;
                return f == null ? null : f.owner.file;
            }
            case "T": {
                ClassInfo c = idx.classes.get(body);
                return c == null ? null : c.file;
            }
            case "SQL":
                return body.contains("#") ? idx.mapperXml.fileOf(body.substring(0, body.indexOf('#'))) : null;
            default:
                return null;
        }
    }

    static String locationOf(String key, CodeIndex idx) {
        String body = key.substring(key.indexOf(':') + 1);
        if (key.startsWith("M:")) {
            MethodInfo m = idx.methodsById.get(body);
            return m == null ? fileOf(key, idx) : m.location();
        }
        if (key.startsWith("F:")) {
            int d = body.lastIndexOf('.');
            FieldInfo f = d > 0 ? idx.findField(body.substring(0, d), body.substring(d + 1)) : null;
            return f == null ? null : f.location();
        }
        if (key.startsWith("T:")) {
            ClassInfo c = idx.classes.get(body);
            return c == null ? null : c.location();
        }
        return fileOf(key, idx);
    }

    // ------------------------------------------------------------------ summary / text helpers

    private void limitations() {
        model.limitations.add("Hunks are the conflict regions of `git merge-file --diff3` on the index stages (or the merge-tree "
                + "stages offline); rename/rename and directory-rename conflicts are reported per path as git lists them.");
        model.limitations.add("Member-level history (added / renamed / moved / signature changed) is computed per commit by parsing "
                + "both versions of each touched file; renames are detected by body similarity >= "
                + (int) (MemberDiff.RENAME_SIMILARITY * 100) + "% and moves across files only within one commit.");
        model.limitations.add("Callers and entry points are found through the tenant's source (Spring MVC mappings, @Scheduled, "
                + "listeners, runners); reflection, XML / string based wiring (e.g. Quartz invokeTarget strings) and front-end callers are not seen.");
        if (session.steps.size() > 1) {
            model.limitations.add("For picks after the first, 'ours' is the tenant plus the earlier picks (resolved with -X theirs when "
                    + "they conflicted); symbols are still checked against the tenant itself.");
        }
    }

    private void summary() {
        int hunks = 0, missing = 0;
        for (ConflictFile f : model.files) hunks += f.hunks.size();
        for (Need n : model.symbols.values()) if (!"IN_PICK".equals(n.origin)) missing++;
        model.summary.put("conflictFiles", model.files.size());
        model.summary.put("conflictHunks", hunks);
        model.summary.put("cleanFiles", model.cleanFiles.size());
        model.summary.put("symbolsToBringOrAdapt", missing);
        model.summary.put("commitsToConsider", model.bringAlong.size());
        model.summary.put("mainlineCommitsSinceFork", model.mainlineCommitsInWindow);
        model.summary.put("tenantCommitsSinceFork", model.tenantCommitsSinceFork);
        model.summary.put("findingsInCleanFiles", model.cleanFileFindings.size());
    }

    private String label(String key) {
        ContextModel.Node n = cb.model().nodes.get(key);
        return n != null && n.label != null ? n.label : key;
    }

    static String firstN(List<String> xs, int n) {
        if (xs.size() <= n) return String.join(", ", xs);
        return String.join(", ", xs.subList(0, n)) + " … (+" + (xs.size() - n) + " more)";
    }

    static String labels(List<Member> ms) {
        List<String> res = new ArrayList<>();
        for (Member m : ms) {
            if (res.size() == 4) {
                res.add("… (+" + (ms.size() - 4) + " more)");
                break;
            }
            res.add(m.label());
        }
        return String.join(", ", res);
    }

    static List<String> slice(String content, Range r) {
        List<String> lines = LineHunks.lines(content);
        if (r.empty() || content == null) return List.of();
        return new ArrayList<>(lines.subList(Math.max(0, r.start() - 1), Math.min(lines.size(), r.end())));
    }

    static String c(Commit c) {
        return short10(c.sha()) + " (" + day(c.date()) + " \"" + c.subject() + "\")";
    }

    static String commitList(List<Commit> cs) {
        List<String> res = new ArrayList<>();
        for (Commit c : cs) res.add(short10(c.sha()));
        return res.isEmpty() ? "no commit found" : String.join(", ", res);
    }

    static String short10(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(10, sha.length()));
    }

    static String day(String iso) {
        return iso == null || iso.length() < 10 ? nz(iso) : iso.substring(0, 10);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static String append(String a, String b) {
        if (b == null || b.isBlank()) return a;
        return a == null || a.isBlank() ? b : a + "; " + b;
    }
}
