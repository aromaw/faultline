package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;

import java.util.ArrayList;
import java.util.List;

/** Read-only git queries the GUI needs: branches, the current branch, and the mainline commits missing from a target. */
public final class GitQueries {
    private GitQueries() {
    }

    /** A local ({@code tenant}) or remote-tracking ({@code origin/master}) branch. */
    public record Branch(String name, boolean remote) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** One mainline commit; {@code order} is its 0-based position in apply order (oldest first). */
    public record CommitRow(String sha, String date, String author, String subject, int order) {
        public String shortSha() {
            return sha.substring(0, Math.min(10, sha.length()));
        }

        public String day() {
            return date == null || date.length() < 10 ? String.valueOf(date) : date.substring(0, 10);
        }
    }

    /** Local branches first, then remote-tracking ones; each group sorted by name; {@code <remote>/HEAD} skipped. */
    public static List<Branch> branches(GitRunner git) {
        return parseBranches(git.run("for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes"));
    }

    static List<Branch> parseBranches(String forEachRef) {
        List<Branch> local = new ArrayList<>(), remote = new ArrayList<>();
        for (String l : forEachRef.split("\n")) {
            String ref = l.trim();
            if (ref.startsWith("refs/heads/")) {
                local.add(new Branch(ref.substring("refs/heads/".length()), false));
            } else if (ref.startsWith("refs/remotes/")) {
                String n = ref.substring("refs/remotes/".length());
                if (n.endsWith("/HEAD")) continue;
                remote.add(new Branch(n, true));
            }
        }
        local.sort((a, b) -> a.name.compareTo(b.name));
        remote.sort((a, b) -> a.name.compareTo(b.name));
        List<Branch> res = new ArrayList<>(local);
        res.addAll(remote);
        return res;
    }

    /** current branch name, or null on a detached HEAD */
    public static String currentBranch(GitRunner git) {
        GitRunner.Result r = git.exec("symbolic-ref", "--short", "-q", "HEAD");
        return r.exit() == 0 && !r.out().isBlank() ? r.out().trim() : null;
    }

    /** the usual mainline names, local before remote; null when none exists */
    public static String guessMainline(List<Branch> branches) {
        for (String n : List.of("master", "main", "origin/master", "origin/main", "upstream/master", "upstream/main",
                "develop", "origin/develop", "trunk")) {
            for (Branch b : branches) if (b.name.equals(n)) return n;
        }
        return branches.isEmpty() ? null : branches.get(0).name;
    }

    /** the current branch unless it is the mainline; otherwise the first other local branch */
    public static String guessTarget(List<Branch> branches, String current, String mainline) {
        if (current != null && !current.equals(mainline)) return current;
        for (Branch b : branches) if (!b.remote && !b.name.equals(mainline)) return b.name;
        for (Branch b : branches) if (!b.name.equals(mainline)) return b.name;
        return current;
    }

    /**
     * Non-merge commits of {@code mainline} that {@code target} does not have ({@code git log target..mainline}),
     * newest first as a log shows them; {@link CommitRow#order} gives the apply order (topological, oldest first).
     * At most {@code limit} commits (the newest ones).
     */
    public static List<CommitRow> missingCommits(GitRunner git, String target, String mainline, int limit) {
        String out = git.run("-c", "core.quotePath=false", "log", "--no-merges", "--topo-order", "-n", String.valueOf(limit),
                "--format=%H%x1f%aI%x1f%an%x1f%s%x1e", target + ".." + mainline, "--");
        return parseLog(out);
    }

    static List<CommitRow> parseLog(String out) {
        List<String[]> raw = new ArrayList<>();
        for (String rec : out.split("\u001e")) {
            String r = rec.strip();
            if (r.isEmpty()) continue;
            String[] p = r.split("\u001f", -1);
            if (p.length < 4) continue;
            raw.add(p);
        }
        List<CommitRow> res = new ArrayList<>();
        int n = raw.size();
        // git log lists newest first: the last line is applied first
        for (int i = 0; i < n; i++) {
            String[] p = raw.get(i);
            res.add(new CommitRow(p[0], p[1], p[2], p[3], n - 1 - i));
        }
        return res;
    }
}
