package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.BlameRun;
import com.branchdiff.conflict.ConflictModel.Commit;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.MemberDiff.Change;
import com.branchdiff.conflict.Members.M;
import com.branchdiff.git.GitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes code to commits of a history window ({@code from..to}): which commits touched a file (following
 * renames), what each did to the file's members (added / deleted / modified / signature changed / renamed / moved,
 * see {@link MemberDiff}), the lineage of one member, and per-line blame. Results are cached per file and window.
 */
public final class History {
    /** one commit of a file's history, with the member-level changes it made to that file */
    public record FileCommit(Commit commit, String status, String oldPath, String newPath, List<Change> changes) {
    }

    private final GitRunner git;
    private final Map<String, Commit> commits = new HashMap<>();
    private final Map<String, List<FileCommit>> fileCache = new HashMap<>();
    private final Map<String, List<M>> memberCache = new HashMap<>();
    private final Map<String, List<String[]>> commitFiles = new HashMap<>();

    public History(GitRunner git) {
        this.git = git;
    }

    public Commit commit(String sha) {
        return commits.computeIfAbsent(sha, s -> ConflictSession.commit(git, s));
    }

    public List<M> members(String rev, String path) {
        if (rev == null || path == null) return List.of();
        return memberCache.computeIfAbsent(rev + ":" + path, k -> Members.of(path, git.show(rev, path)));
    }

    /** commits of {@code from..to} that touched {@code path} (path as of {@code to}), oldest first */
    public List<FileCommit> fileCommits(String from, String to, String path) {
        String key = from + ".." + to + ":" + path;
        List<FileCommit> cached = fileCache.get(key);
        if (cached != null) return cached;
        List<FileCommit> res = new ArrayList<>();
        String range = from == null ? to : from + ".." + to;
        GitRunner.Result r = git.exec("-c", "core.quotePath=false", "log", "--follow", "-M", "--name-status",
                "--format=%x1e%H%x1f%aI%x1f%an%x1f%s", range, "--", path);
        if (r.exit() == 0) {
            for (String chunk : r.out().split("\u001e")) {
                if (chunk.isBlank()) continue;
                String[] lines = chunk.split("\n");
                String[] h = lines[0].split("\u001f", -1);
                Commit c = new Commit(h[0], h.length > 1 ? h[1] : "", h.length > 2 ? h[2] : "", h.length > 3 ? h[3] : "");
                commits.putIfAbsent(c.sha(), c);
                String status = "M", oldPath = path, newPath = path;
                for (int i = 1; i < lines.length; i++) {
                    String[] f = lines[i].split("\t");
                    if (f.length < 2 || f[0].isEmpty()) continue;
                    status = f[0].substring(0, 1);
                    if (f.length >= 3) {
                        oldPath = f[1];
                        newPath = f[2];
                    } else {
                        oldPath = newPath = f[1];
                    }
                }
                res.add(new FileCommit(c, status, oldPath, newPath, null));
            }
        }
        Collections.reverse(res);
        List<FileCommit> withChanges = new ArrayList<>();
        for (FileCommit fc : res) {
            List<M> before = fc.status.equals("A") ? List.of() : members(fc.commit.sha() + "^", fc.oldPath);
            List<M> after = fc.status.equals("D") ? List.of() : members(fc.commit.sha(), fc.newPath);
            withChanges.add(new FileCommit(fc.commit, fc.status, fc.oldPath, fc.newPath, MemberDiff.diff(before, after)));
        }
        fileCache.put(key, withChanges);
        return withChanges;
    }

    /** file-level events (added / renamed / deleted) of {@code path} in the window */
    public List<Event> fileEvents(String from, String to, String path) {
        List<Event> res = new ArrayList<>();
        for (FileCommit fc : fileCommits(from, to, path)) {
            switch (fc.status) {
                case "A" -> res.add(new Event(fc.commit, "FILE_ADDED", null, null, fc.newPath, "file added"));
                case "D" -> res.add(new Event(fc.commit, "FILE_DELETED", null, fc.oldPath, null, "file deleted"));
                case "R" -> res.add(new Event(fc.commit, "FILE_RENAMED", null, fc.oldPath, fc.newPath, "file renamed"));
                default -> res.add(new Event(fc.commit, "FILE_MODIFIED", null, null, fc.newPath, summary(fc.changes)));
            }
        }
        return res;
    }

    static String summary(List<Change> changes) {
        Map<MemberDiff.Kind, Integer> n = new LinkedHashMap<>();
        for (Change c : changes) n.merge(c.kind(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        n.forEach((k, v) -> parts.add(v + " " + k.name().toLowerCase().replace('_', ' ')));
        return parts.isEmpty() ? "no member-level change (formatting / comments)" : String.join(", ", parts);
    }

    /**
     * What happened to member {@code id} (as it exists in {@code path} at {@code to}) during {@code from..to},
     * newest first while walking back, returned oldest first. Renames, signature changes and in-file moves are
     * followed; the walk stops where the member was added (possibly moved in from another file of the same commit).
     */
    public List<Event> lineage(String from, String to, String path, String id) {
        List<FileCommit> fcs = fileCommits(from, to, path);
        List<Event> res = new ArrayList<>();
        String cur = id;
        for (int i = fcs.size() - 1; i >= 0 && cur != null; i--) {
            FileCommit fc = fcs.get(i);
            for (Change ch : fc.changes) {
                if (ch.after() == null || !ch.after().id().equals(cur)) continue;
                switch (ch.kind()) {
                    case MODIFIED -> res.add(event(fc, "MODIFIED", ch, null));
                    case SIGNATURE_CHANGED, RENAMED, MOVED -> {
                        res.add(event(fc, ch.kind().name(), ch, null));
                        cur = ch.before().id();
                    }
                    case ADDED -> {
                        Change from2 = movedFromOtherFile(fc, ch.after());
                        if (from2 != null) res.add(event(fc, "MOVED", from2, "moved from " + from2.before().member().path()));
                        else res.add(event(fc, fc.status.equals("A") ? "ADDED" : "ADDED", ch, fc.status.equals("A") ? "with the new file" : null));
                        cur = null;
                    }
                    default -> {
                    }
                }
                break;
            }
        }
        Collections.reverse(res);
        return res;
    }

    /**
     * What happened during {@code from..to} to member {@code id} that exists in {@code pathAtFrom} at {@code from}:
     * the file is followed forward via the history of its path at {@code to} ({@code pathAtTo}, may be null when the
     * file is gone).
     */
    public List<Event> forward(String from, String to, String pathAtTo, String id) {
        List<Event> res = new ArrayList<>();
        if (pathAtTo == null) return res;
        String cur = id;
        for (FileCommit fc : fileCommits(from, to, pathAtTo)) {
            if (cur == null) break;
            for (Change ch : fc.changes) {
                if (ch.before() == null || !ch.before().id().equals(cur)) continue;
                switch (ch.kind()) {
                    case MODIFIED -> res.add(event(fc, "MODIFIED", ch, null));
                    case SIGNATURE_CHANGED, RENAMED, MOVED -> {
                        res.add(event(fc, ch.kind().name(), ch, null));
                        cur = ch.after().id();
                    }
                    case DELETED -> {
                        Change to2 = movedToOtherFile(fc, ch.before());
                        if (to2 != null) res.add(event(fc, "MOVED", to2, "moved to " + to2.after().member().path()));
                        else res.add(event(fc, "DELETED", ch, null));
                        cur = null;
                    }
                    default -> {
                    }
                }
                break;
            }
        }
        return res;
    }

    private static Event event(FileCommit fc, String kind, Change ch, String detail) {
        String from = ch.before() == null ? null : ch.before().member().label();
        String to = ch.after() == null ? null : ch.after().member().label();
        String member = ch.after() != null ? ch.after().id() : ch.before().id();
        String d = detail;
        if (d == null && (kind.equals("RENAMED") || kind.equals("MOVED")) && ch.similarity() < 1.0) {
            d = String.format("body similarity %.0f%%", ch.similarity() * 100);
        }
        if (d == null && kind.equals("SIGNATURE_CHANGED")) d = "parameters changed";
        return new Event(fc.commit, kind, member, from, to, d);
    }

    /** [status, oldPath, newPath] of every file of a commit */
    List<String[]> filesOf(String sha) {
        return commitFiles.computeIfAbsent(sha, s -> {
            List<String[]> res = new ArrayList<>();
            GitRunner.Result r = git.exec("-c", "core.quotePath=false", "show", "-M", "--name-status", "--format=", s);
            if (r.exit() != 0) return res;
            for (String l : r.out().split("\n")) {
                String[] f = l.split("\t");
                if (f.length < 2) continue;
                res.add(new String[] {f[0].substring(0, 1), f[1], f.length > 2 ? f[2] : f[1]});
            }
            return res;
        });
    }

    private Change movedFromOtherFile(FileCommit fc, M added) {
        for (String[] f : filesOf(fc.commit.sha())) {
            if (f[2].equals(fc.newPath) || f[0].equals("A")) continue;
            List<M> before = members(fc.commit.sha() + "^", f[1]);
            List<M> after = f[0].equals("D") ? List.of() : members(fc.commit.sha(), f[2]);
            Map<String, M> a = Members.byId(after);
            for (M b : before) {
                if (a.containsKey(b.id()) || !MemberDiff.compatible(b, added)) continue;
                double s = MemberDiff.similarity(b.body(), added.body());
                if (s >= MemberDiff.RENAME_SIMILARITY && MemberDiff.tokens(added.body()).size() >= MemberDiff.MIN_TOKENS) {
                    return new Change(MemberDiff.Kind.MOVED, b, added, s);
                }
            }
        }
        return null;
    }

    private Change movedToOtherFile(FileCommit fc, M deleted) {
        for (String[] f : filesOf(fc.commit.sha())) {
            if (f[1].equals(fc.oldPath) && f[0].equals("M") || f[0].equals("D")) continue;
            List<M> before = f[0].equals("A") ? List.of() : members(fc.commit.sha() + "^", f[1]);
            List<M> after = members(fc.commit.sha(), f[2]);
            Map<String, M> b = Members.byId(before);
            for (M a : after) {
                if (b.containsKey(a.id()) || !MemberDiff.compatible(deleted, a)) continue;
                double s = MemberDiff.similarity(deleted.body(), a.body());
                if (s >= MemberDiff.RENAME_SIMILARITY && MemberDiff.tokens(deleted.body()).size() >= MemberDiff.MIN_TOKENS) {
                    return new Change(MemberDiff.Kind.MOVED, deleted, a, s);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ blame

    /**
     * Which commits of {@code from..to} wrote lines {@code start..end} of {@code path} at {@code to}; lines older than
     * the window are attributed to the boundary ({@code boundary = true}).
     */
    public List<BlameRun> blame(String from, String to, String path, int start, int end) {
        List<BlameRun> res = new ArrayList<>();
        if (end < start || start < 1) return res;
        GitRunner.Result r = git.exec("blame", "--porcelain", "-w", "-M", "-L", start + "," + end, from + ".." + to, "--", path);
        if (r.exit() != 0) return res;
        Map<String, Boolean> boundary = new HashMap<>();
        String sha = null;
        int line = 0;
        List<Object[]> perLine = new ArrayList<>();
        for (String l : r.out().split("\n")) {
            if (l.startsWith("\t")) {
                perLine.add(new Object[] {line, sha});
                continue;
            }
            String[] p = l.split(" ");
            if (p.length >= 3 && p[0].matches("[0-9a-f]{40}")) {
                sha = p[0];
                line = Integer.parseInt(p[2]);
            } else if (l.equals("boundary") && sha != null) {
                boundary.put(sha, true);
            }
        }
        int runStart = -1, prev = -1;
        String runSha = null;
        for (Object[] pl : perLine) {
            int ln = (int) pl[0];
            String s = (String) pl[1];
            if (runSha != null && s.equals(runSha) && ln == prev + 1) {
                prev = ln;
                continue;
            }
            if (runSha != null) res.add(new BlameRun(runStart, prev, commit(runSha), boundary.getOrDefault(runSha, false)));
            runSha = s;
            runStart = prev = ln;
        }
        if (runSha != null) res.add(new BlameRun(runStart, prev, commit(runSha), boundary.getOrDefault(runSha, false)));
        return res;
    }

    /** number of commits in {@code from..to} */
    public int count(String from, String to) {
        GitRunner.Result r = git.exec("rev-list", "--count", from + ".." + to);
        return r.exit() == 0 ? Integer.parseInt(r.out().trim()) : 0;
    }

    /** paths renamed between two revisions: new path -> old path (git rename detection) */
    public Map<String, String> renames(String from, String to) {
        Map<String, String> res = new LinkedHashMap<>();
        GitRunner.Result r = git.exec("-c", "core.quotePath=false", "diff", "-M", "--name-status", "--diff-filter=R", from, to);
        if (r.exit() != 0) return res;
        for (String l : r.out().split("\n")) {
            String[] f = l.split("\t");
            if (f.length == 3) res.put(f[2], f[1]);
        }
        return res;
    }
}
