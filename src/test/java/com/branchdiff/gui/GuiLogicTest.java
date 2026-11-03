package com.branchdiff.gui;

import com.branchdiff.conflict.ConflictModel;
import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.git.GitRunner;
import com.branchdiff.gui.GitQueries.Branch;
import com.branchdiff.gui.GitQueries.CommitRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GUI's non-UI logic: branch listing, commit list, filter, apply order, hunk badges, preferences, locations. */
class GuiLogicTest {
    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ branches

    @Test
    void branchesListLocalBeforeRemoteAndSkipRemoteHead() {
        List<Branch> bs = GitQueries.parseBranches(String.join("\n", "refs/heads/tenant", "refs/heads/master",
                "refs/remotes/origin/HEAD", "refs/remotes/origin/master", "refs/remotes/origin/feature/x", "refs/tags/v1", ""));
        assertEquals(List.of("master", "tenant", "origin/feature/x", "origin/master"), bs.stream().map(Branch::name).toList());
        assertFalse(bs.get(0).remote());
        assertTrue(bs.get(2).remote());
    }

    @Test
    void mainlineAndTargetGuesses() {
        List<Branch> bs = List.of(new Branch("tenant", false), new Branch("origin/main", true), new Branch("origin/tenant", true));
        assertEquals("origin/main", GitQueries.guessMainline(bs));
        assertEquals("tenant", GitQueries.guessTarget(bs, "tenant", "origin/main"));
        // on the mainline branch itself: the first other local branch
        List<Branch> bs2 = List.of(new Branch("master", false), new Branch("tenant", false));
        assertEquals("master", GitQueries.guessMainline(bs2));
        assertEquals("tenant", GitQueries.guessTarget(bs2, "master", "master"));
        assertEquals("tenant", GitQueries.guessTarget(bs2, null, "master"));
    }

    @Test
    void branchesAndMissingCommitsOfARealRepository() throws Exception {
        GitRunner git = repo();
        List<Branch> bs = GitQueries.branches(git);
        assertEquals(List.of("master", "tenant"), bs.stream().map(Branch::name).toList());
        assertEquals("tenant", GitQueries.currentBranch(git));
        List<CommitRow> rows = GitQueries.missingCommits(git, "tenant", "master", 100);
        // newest first, the merge commit excluded (its side branch's commit is listed), apply order oldest first
        assertEquals(List.of("m3 | with pipe", "m2", "side work", "m1"), rows.stream().map(CommitRow::subject).toList());
        assertEquals(List.of(3, 2, 1, 0), rows.stream().map(CommitRow::order).toList());
        assertEquals("Mainline Dev", rows.get(0).author());
        assertEquals(40, rows.get(0).sha().length());
        List<CommitRow> newest = GitQueries.missingCommits(git, "tenant", "master", 2);
        assertEquals(List.of("m3 | with pipe", "m2"), newest.stream().map(CommitRow::subject).toList());
    }

    @Test
    void logParsingKeepsSubjectsWithSeparatorsAndOrders() {
        String out = "aaa\u001f2026-04-16T10:00:00+08:00\u001fRuoYi\u001f角色权限变更 | refresh\u001e\n"
                + "bbb\u001f2026-04-14T10:00:00+08:00\u001fRuoYi\u001fnotice\u001e\n";
        List<CommitRow> rows = GitQueries.parseLog(out);
        assertEquals(2, rows.size());
        assertEquals("角色权限变更 | refresh", rows.get(0).subject());
        assertEquals(1, rows.get(0).order());
        assertEquals(0, rows.get(1).order());
        assertEquals("2026-04-16", rows.get(0).day());
    }

    // ------------------------------------------------------------------ filter and order

    @Test
    void filterMatchesMessageAuthorAndShaPrefixAllWords() {
        CommitRow c = new CommitRow("dbe9834e4cf5185bca2e", "2026-04-16", "RuoYi", "角色权限变更后刷新 online users", 3);
        assertTrue(new CommitFilter("").matches(c));
        assertTrue(new CommitFilter("  ").isEmpty());
        assertTrue(new CommitFilter("ONLINE").matches(c));
        assertTrue(new CommitFilter("角色").matches(c));
        assertTrue(new CommitFilter("ruoyi").matches(c));
        assertTrue(new CommitFilter("dbe98").matches(c));
        assertFalse(new CommitFilter("4cf5").matches(c), "sha matches by prefix only");
        assertTrue(new CommitFilter("online dbe9").matches(c));
        assertFalse(new CommitFilter("online offline").matches(c));
        assertTrue(new CommitFilter("author:ruo").matches(c));
        assertFalse(new CommitFilter("author:online").matches(c));
        assertTrue(new CommitFilter("sha:DBE").matches(c));
    }

    @Test
    void applyOrderIsChronologicalWhateverTheClickOrder() {
        CommitRow a = new CommitRow("a".repeat(40), "", "x", "oldest", 0);
        CommitRow b = new CommitRow("b".repeat(40), "", "x", "middle", 1);
        CommitRow c = new CommitRow("c".repeat(40), "", "x", "newest", 2);
        assertEquals(List.of(a, b, c), PickOrder.applyOrder(List.of(c, a, b)));
        assertEquals(List.of(a, c), PickOrder.applyOrder(List.of(c, a, c)));
        assertEquals("a".repeat(40) + "," + "c".repeat(40), PickOrder.pickSpec(List.of(c, a)));
        assertEquals("", PickOrder.pickSpec(List.of()));
    }

    @Test
    void appliedOrderOfARealLogMatchesGitRevListReverse() throws Exception {
        GitRunner git = repo();
        List<CommitRow> rows = GitQueries.missingCommits(git, "tenant", "master", 100);
        List<CommitRow> clicked = new ArrayList<>(List.of(rows.get(0), rows.get(3), rows.get(1), rows.get(2)));
        String spec = PickOrder.pickSpec(clicked);
        // parents before children, like git cherry-pick tenant..master
        String revList = git.run("rev-list", "--reverse", "--topo-order", "--no-merges", "tenant..master").trim().replace('\n', ',');
        assertEquals(revList, spec);
    }

    // ------------------------------------------------------------------ badges

    @Test
    void hunkSeverityBadges() {
        ConflictFile f = new ConflictFile();
        f.kind = "CONTENT";
        Hunk h = new Hunk();
        assertEquals(HunkSeverity.LOW, HunkSeverity.of(f, h));
        Need present = need("PRESENT", "IN_TENANT", false);
        h.takeTheirs.needs.add(present);
        assertEquals(HunkSeverity.LOW, HunkSeverity.of(f, h));
        h.takeOurs.entryPoints.add(new EntryPoint());
        assertEquals(HunkSeverity.MEDIUM, HunkSeverity.of(f, h));
        h.takeTheirs.needs.add(need("MISSING", "IN_PICK", false));
        assertEquals(HunkSeverity.MEDIUM, HunkSeverity.of(f, h), "the pick brings it");
        h.takeTheirs.needs.add(need("MISSING", "ADDED_ON_MAINLINE", true));
        assertEquals(HunkSeverity.MEDIUM, HunkSeverity.of(f, h), "uncertain gaps are not HIGH");
        h.takeTheirs.needs.add(need("CHANGED", "SIGNATURE_CHANGED_ON_MAINLINE", false));
        assertEquals(HunkSeverity.HIGH, HunkSeverity.of(f, h));

        ConflictFile del = new ConflictFile();
        del.kind = "DELETED_BY_US";
        assertEquals(HunkSeverity.HIGH, HunkSeverity.of(del, new Hunk()));
        assertEquals(HunkSeverity.HIGH, HunkSeverity.of(del));
        f.hunks.add(new Hunk());
        f.hunks.add(h);
        assertEquals(HunkSeverity.HIGH, HunkSeverity.of(f));
    }

    private static Need need(String ours, String origin, boolean uncertain) {
        Need n = new Need();
        n.ours = ours;
        n.origin = origin;
        n.uncertain = uncertain;
        return n;
    }

    // ------------------------------------------------------------------ prefs and locations

    @Test
    void recentProjectsAreMostRecentFirstUniqueAndCapped() {
        Path f = tmp.resolve("p/gui.properties");
        Prefs p = new Prefs(f);
        for (int i = 0; i < 12; i++) p.addRecent("/repo/" + i);
        p.addRecent("/repo/5");
        List<String> r = new Prefs(f).recentProjects();
        assertEquals(Prefs.MAX_RECENT, r.size());
        assertEquals("/repo/5", r.get(0));
        assertEquals("/repo/11", r.get(1));
        assertEquals(1, r.stream().filter("/repo/5"::equals).count());
        assertFalse(r.contains("/repo/0"));
        p.setDark(true);
        p.setBranch("/repo/5", "target", "tenant");
        Prefs again = new Prefs(f);
        assertTrue(again.dark());
        assertEquals("tenant", again.branch("/repo/5", "target"));
        assertNull(again.branch("/repo/6", "target"));
    }

    @Test
    void locationsParse() {
        assertEquals(new Loc("ours", "a/B.java", 112), Loc.parse("ours", "a/B.java:112"));
        assertEquals(new Loc("base", "a/B.java", 12), Loc.parse("base", "a/B.java:12 (base)"));
        assertEquals(new Loc("theirs", "pom.xml", 0), Loc.parse("theirs", "pom.xml"));
        assertNull(Loc.parse("ours", " "));
        assertNull(Loc.parse("ours", null));
    }

    // ------------------------------------------------------------------ fixture

    /** master: base, m1, merge, m2, m3; tenant forks at base with one commit; HEAD = tenant */
    private GitRunner repo() throws IOException {
        Path r = tmp.resolve("repo");
        Files.createDirectories(r);
        GitRunner git = new GitRunner(r);
        git.run("init", "-q", "-b", "master");
        commit(git, r, "f.txt", "base", "base", "Base Dev");
        git.run("branch", "tenant");
        commit(git, r, "f.txt", "m1", "m1", "Mainline Dev");
        git.run("checkout", "-q", "-b", "side");
        commit(git, r, "s.txt", "s", "side work", "Mainline Dev");
        git.run("checkout", "-q", "master");
        git.run("-c", "user.name=Mainline Dev", "-c", "user.email=m@x", "merge", "-q", "--no-ff", "-m", "merge side", "side");
        git.run("branch", "-D", "side");
        commit(git, r, "f.txt", "m2", "m2", "Mainline Dev");
        commit(git, r, "f.txt", "m3", "m3 | with pipe", "Mainline Dev");
        git.run("checkout", "-q", "tenant");
        commit(git, r, "t.txt", "t", "tenant change", "Tenant Dev");
        return git;
    }

    private static void commit(GitRunner git, Path r, String file, String content, String msg, String author) throws IOException {
        Files.writeString(r.resolve(file), content + "\n");
        git.run("add", file);
        git.run("-c", "user.name=" + author, "-c", "user.email=a@x", "commit", "-q", "-m", msg);
    }
}
