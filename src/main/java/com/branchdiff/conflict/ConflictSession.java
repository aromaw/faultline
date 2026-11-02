package com.branchdiff.conflict;

import com.branchdiff.conflict.ConflictModel.Commit;
import com.branchdiff.git.GitRunner;
import com.branchdiff.git.GitRunner.GitException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The conflicted state to analyze: either read from a repository that is in the middle of a cherry-pick / merge /
 * revert (CHERRY_PICK_HEAD / MERGE_HEAD / REVERT_HEAD plus the unmerged index entries, stages 1/2/3), or simulated
 * offline for {@code --onto <tenant> --pick <commits>} with {@code git merge-tree --write-tree} (the same "ort" merge
 * a cherry-pick runs, with the picked commit's parent as merge base). The simulation never touches the worktree,
 * the index or any ref.
 */
public final class ConflictSession {
    /** One unmerged path: stage -> blob sha (1 = base, 2 = ours, 3 = theirs), with the path of each stage. */
    public static final class Unmerged {
        public final Map<Integer, String> sha = new LinkedHashMap<>();
        public final Map<Integer, String> path = new LinkedHashMap<>();

        public String kind() {
            boolean b = sha.containsKey(1), o = sha.containsKey(2), t = sha.containsKey(3);
            if (b && o && t) return "CONTENT";
            if (b && !o && t) return "DELETED_BY_US";
            if (b && o) return "DELETED_BY_THEM";
            if (o && t) return "ADDED_BY_BOTH";
            if (o) return "ADDED_BY_US";
            return "ADDED_BY_THEM";
        }
    }

    /** One applied commit: the state it was applied to (ours), its parent (base) and what conflicted. */
    public static final class Step {
        public String pick;
        public String base;
        public String ours;
        public final Map<String, Unmerged> unmerged = new LinkedHashMap<>();
        public final List<String> messages = new ArrayList<>();
        /** paths changed by the picked commit (base -> pick) */
        public final List<String> changed = new ArrayList<>();
    }

    public String mode;
    /** tenant commit (HEAD for a live session, --onto for a simulation) */
    public String tenant;
    /** commits picked, in order */
    public final List<String> picks = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();
    public final List<String> pending = new ArrayList<>();
    public final List<String> messages = new ArrayList<>();
    /** base / theirs of the whole feature: first pick's parent, and a commit holding all picks applied onto it */
    public String featureBase;
    public String featureTip;

    private ConflictSession() {
    }

    // ------------------------------------------------------------------ live state

    /** @return null when the repository is not in the middle of a cherry-pick, merge or revert */
    public static ConflictSession live(GitRunner git) {
        String head = git.resolveCommit("HEAD");
        String cp = git.tryResolve("CHERRY_PICK_HEAD"), mh = git.tryResolve("MERGE_HEAD"), rv = git.tryResolve("REVERT_HEAD");
        ConflictSession s = new ConflictSession();
        s.tenant = head;
        Step st = new Step();
        st.ours = head;
        if (cp != null) {
            s.mode = "cherry-pick";
            st.pick = cp;
            st.base = parentOf(git, cp);
        } else if (mh != null) {
            s.mode = "merge";
            st.pick = mh;
            st.base = git.mergeBase(head, mh);
        } else if (rv != null) {
            s.mode = "revert";
            // a revert applies pick^ with pick as base
            st.pick = parentOf(git, rv);
            st.base = rv;
        } else {
            return null;
        }
        s.picks.add(st.pick);
        String out = git.run("ls-files", "-u", "-z");
        parseUnmerged(out, st.unmerged);
        st.changed.addAll(changedPaths(git, st.base, st.pick));
        s.steps.add(st);
        s.featureBase = st.base;
        s.featureTip = st.pick;
        // remaining picks of a multi-commit cherry-pick / revert
        try {
            String gitDir = git.run("rev-parse", "--git-dir").trim();
            Path todo = git.repo().resolve(gitDir).resolve("sequencer/todo");
            if (Files.exists(todo)) {
                for (String l : Files.readAllLines(todo)) {
                    if (l.isBlank() || l.startsWith("#")) continue;
                    String[] parts = l.trim().split("\\s+");
                    // the todo list still names the commit that is being applied
                    if (parts.length > 1 && parts[1].length() >= 4 && st.pick.startsWith(parts[1])) continue;
                    s.pending.add(l.trim());
                }
            }
        } catch (Exception ignored) {
            // no sequencer state
        }
        if (st.unmerged.isEmpty()) s.messages.add("no unmerged index entries: the conflicts were already resolved (git add) or "
                + "the operation stopped for another reason; only the non-conflicting analysis applies");
        return s;
    }

    static void parseUnmerged(String lsFilesUz, Map<String, Unmerged> into) {
        for (String e : lsFilesUz.split("\0")) {
            if (e.isBlank()) continue;
            int tab = e.indexOf('\t');
            if (tab < 0) continue;
            String[] meta = e.substring(0, tab).trim().split("\\s+");
            if (meta.length < 3) continue;
            String path = e.substring(tab + 1);
            int stage = Integer.parseInt(meta[2]);
            Unmerged u = into.computeIfAbsent(path, k -> new Unmerged());
            u.sha.put(stage, meta[1]);
            u.path.put(stage, path);
        }
    }

    // ------------------------------------------------------------------ offline simulation

    /**
     * Simulates picking {@code pickSpec} (a commit, a comma separated list, or a range {@code A..B}) onto {@code onto}.
     * Each commit is merged with {@code git merge-tree --write-tree --merge-base <commit>^ <state> <commit>}; when a
     * step conflicts, the simulation continues from the same merge done with {@code -X theirs} so later picks still see
     * the earlier ones (the report says so).
     */
    public static ConflictSession simulate(GitRunner git, String onto, String pickSpec) {
        ConflictSession s = new ConflictSession();
        s.mode = "simulated";
        s.tenant = git.resolveCommit(onto);
        s.picks.addAll(expandPicks(git, pickSpec));
        if (s.picks.isEmpty()) throw new IllegalArgumentException("--pick '" + pickSpec + "' selects no commits");
        String state = s.tenant;
        for (int i = 0; i < s.picks.size(); i++) {
            String c = s.picks.get(i);
            Step st = new Step();
            st.pick = c;
            st.base = parentOf(git, c);
            st.ours = state;
            st.changed.addAll(changedPaths(git, st.base, c));
            MergeTree mt = mergeTree(git, st.base, state, c, false);
            st.unmerged.putAll(mt.unmerged);
            st.messages.addAll(mt.messages);
            String tree = mt.tree;
            if (!mt.clean) {
                MergeTree resolved = mergeTree(git, st.base, state, c, true);
                tree = resolved.tree;
                if (i < s.picks.size() - 1) {
                    s.messages.add("step " + (i + 1) + " (" + c.substring(0, 10) + ") conflicts; later picks are simulated on top of it "
                            + "resolved with -X theirs");
                }
            }
            s.steps.add(st);
            if (i < s.picks.size() - 1) state = commitTree(git, tree, state, "faultline simulated pick of " + c);
        }
        s.featureBase = parentOf(git, s.picks.get(0));
        boolean contiguous = true;
        for (int i = 1; i < s.picks.size(); i++) if (!parentOf(git, s.picks.get(i)).equals(s.picks.get(i - 1))) contiguous = false;
        if (contiguous) {
            s.featureTip = s.picks.get(s.picks.size() - 1);
        } else {
            // the feature alone: every pick applied onto the first pick's parent
            String f = s.featureBase;
            for (String c : s.picks) {
                MergeTree mt = mergeTree(git, parentOf(git, c), f, c, true);
                f = commitTree(git, mt.tree, f, "faultline feature tip " + c);
            }
            s.featureTip = f;
            s.messages.add("picks are not contiguous on mainline: the feature's own changes are taken from a synthetic commit "
                    + "that applies all picks onto " + s.featureBase.substring(0, 10));
        }
        return s;
    }

    static List<String> expandPicks(GitRunner git, String spec) {
        List<String> res = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("..")) {
                String out = git.run("rev-list", "--reverse", "--no-merges", p);
                for (String l : out.split("\n")) if (!l.isBlank()) res.add(l.trim());
            } else {
                res.add(git.resolveCommit(p));
            }
        }
        Set<String> seen = new LinkedHashSet<>(res);
        return new ArrayList<>(seen);
    }

    static final class MergeTree {
        String tree;
        boolean clean;
        final Map<String, Unmerged> unmerged = new LinkedHashMap<>();
        final List<String> messages = new ArrayList<>();
    }

    static MergeTree mergeTree(GitRunner git, String base, String ours, String theirs, boolean favorTheirs) {
        List<String> args = new ArrayList<>(List.of("-c", "core.quotePath=false", "merge-tree", "--write-tree", "--messages",
                "--merge-base=" + base));
        if (favorTheirs) args.add("-Xtheirs");
        args.add(ours);
        args.add(theirs);
        GitRunner.Result r = git.exec(args.toArray(new String[0]));
        if (r.exit() > 1) throw new GitException("git merge-tree failed (" + r.exit() + "): " + r.err().trim()
                + " (git 2.40 or newer is required for the offline simulation)");
        MergeTree mt = parseMergeTree(r.out());
        mt.clean = r.exit() == 0;
        return mt;
    }

    /** Parses {@code merge-tree --write-tree --messages} output (without -z). */
    static MergeTree parseMergeTree(String out) {
        MergeTree mt = new MergeTree();
        String[] lines = out.split("\n", -1);
        mt.tree = lines[0].trim();
        int i = 1;
        for (; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) break;
            int tab = l.indexOf('\t');
            String[] meta = tab > 0 ? l.substring(0, tab).split(" ") : new String[0];
            if (meta.length == 3 && meta[2].matches("[123]")) {
                String path = l.substring(tab + 1);
                Unmerged u = mt.unmerged.computeIfAbsent(path, k -> new Unmerged());
                u.sha.put(Integer.parseInt(meta[2]), meta[1]);
                u.path.put(Integer.parseInt(meta[2]), path);
            }
        }
        for (i++; i < lines.length; i++) if (!lines[i].isBlank()) mt.messages.add(lines[i].trim());
        return mt;
    }

    // ------------------------------------------------------------------ helpers

    /** an unreferenced commit object (no ref is created or moved) */
    static String commitTree(GitRunner git, String tree, String parent, String msg) {
        return git.run("-c", "user.name=faultline", "-c", "user.email=faultline@localhost", "commit-tree", tree, "-p", parent,
                "-m", msg).trim();
    }

    static String parentOf(GitRunner git, String c) {
        String p = git.tryResolve(c + "^1");
        if (p == null) throw new GitException("commit " + c + " has no parent (root commits cannot be picked)");
        return p;
    }

    static List<String> changedPaths(GitRunner git, String a, String b) {
        List<String> res = new ArrayList<>();
        for (String l : git.run("-c", "core.quotePath=false", "diff", "--name-only", "--no-renames", a, b).split("\n")) {
            if (!l.isBlank()) res.add(l.trim());
        }
        return res;
    }

    public static Commit commit(GitRunner git, String sha) {
        if (sha == null) return null;
        String out = git.run("show", "-s", "--format=%H%x1f%aI%x1f%an%x1f%s", sha).trim();
        String[] p = out.split("\u001f", -1);
        return new Commit(p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "", p.length > 3 ? p[3] : "");
    }
}
