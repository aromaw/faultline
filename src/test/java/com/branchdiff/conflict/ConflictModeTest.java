package com.branchdiff.conflict;

import com.branchdiff.TestSupport;
import com.branchdiff.conflict.ConflictModel.BlameRun;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Range;
import com.branchdiff.conflict.MemberDiff.Change;
import com.branchdiff.git.GitRunner;
import com.branchdiff.index.CodeIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conflict mode: index stages, merge-tree output, hunk regions, member renames, history attribution, entry points. */
class ConflictModeTest {

    // ------------------------------------------------------------------ index stages

    @Test
    void unmergedStagesAreGroupedPerPathAndClassified() {
        String nul = "\u0000";
        String lsFiles = String.join(nul, "100644 aaaa 1\tsrc/A.java", "100644 bbbb 2\tsrc/A.java", "100644 cccc 3\tsrc/A.java",
                "100644 dddd 1\tsrc/Gone.java", "100644 eeee 3\tsrc/Gone.java",
                "100644 ffff 1\tsrc/Dropped.java", "100644 gggg 2\tsrc/Dropped.java",
                "100644 hhhh 2\tsrc/New.java", "100644 iiii 3\tsrc/New.java",
                "100644 jjjj 2\tpath with spaces/B.java", "100644 kkkk 3\tpath with spaces/B.java") + nul;
        Map<String, ConflictSession.Unmerged> u = new TreeMap<>();
        ConflictSession.parseUnmerged(lsFiles, u);
        assertEquals(5, u.size());
        assertEquals("CONTENT", u.get("src/A.java").kind());
        assertEquals("bbbb", u.get("src/A.java").sha.get(2));
        assertEquals("DELETED_BY_US", u.get("src/Gone.java").kind());
        assertEquals("DELETED_BY_THEM", u.get("src/Dropped.java").kind());
        assertEquals("ADDED_BY_BOTH", u.get("src/New.java").kind());
        assertEquals("kkkk", u.get("path with spaces/B.java").sha.get(3));
    }

    @Test
    void mergeTreeOutputGivesTreeStagesAndMessages() {
        String out = "4b825dc642cb6eb9a060e54bf8d69288fbee4904\n"
                + "100644 1111111111111111111111111111111111111111 1\tsrc/A.java\n"
                + "100644 2222222222222222222222222222222222222222 2\tsrc/A.java\n"
                + "100644 3333333333333333333333333333333333333333 3\tsrc/A.java\n"
                + "100644 4444444444444444444444444444444444444444 1\tsrc/B.java\n"
                + "100644 5555555555555555555555555555555555555555 3\tsrc/B.java\n"
                + "\n"
                + "Auto-merging src/A.java\n"
                + "CONFLICT (content): Merge conflict in src/A.java\n"
                + "CONFLICT (modify/delete): src/B.java deleted in HEAD and modified in pick.\n";
        ConflictSession.MergeTree mt = ConflictSession.parseMergeTree(out);
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", mt.tree);
        assertEquals("CONTENT", mt.unmerged.get("src/A.java").kind());
        assertEquals("DELETED_BY_US", mt.unmerged.get("src/B.java").kind());
        assertEquals(3, mt.messages.size());
    }

    // ------------------------------------------------------------------ hunks

    @Test
    void conflictRegionsCarryLineRangesOnAllThreeSides() {
        String base = lines("a", "b", "c", "d", "e", "f", "g", "h");
        String ours = lines("a", "b", "c-ours", "d", "e", "f", "g", "h", "i-ours");
        String theirs = lines("x", "a", "b", "c-theirs", "c2-theirs", "d", "e", "f", "g", "h");
        List<MergeHunks.Region> rs = MergeHunks.regions(base, ours, theirs);
        assertEquals(1, rs.size(), rs.toString());
        MergeHunks.Region r = rs.get(0);
        assertEquals(new Range(3, 3), r.ours());
        assertEquals(new Range(3, 3), r.base());
        assertEquals(new Range(4, 5), r.theirs());
    }

    @Test
    void lineEndingChangesDoNotTurnTheWholeFileIntoOneHunk() {
        String base = lines("a", "b", "c", "d", "e", "f");
        String ours = base.replace("\n", "\r\n").replace("c\r\n", "c-ours\r\n");
        String theirs = base.replace("c\n", "c-theirs\n");
        assertNotNull(ConflictAnalyzer.eolNote(ours, base, theirs));
        assertNull(ConflictAnalyzer.eolNote(base, base, theirs));
        List<MergeHunks.Region> rs = MergeHunks.regions(ConflictAnalyzer.stripCr(base), ConflictAnalyzer.stripCr(ours),
                ConflictAnalyzer.stripCr(theirs));
        assertEquals(1, rs.size());
        assertEquals(new Range(3, 3), rs.get(0).ours());
    }

    @Test
    void adjacentOrOverlappingRangesTouch() {
        assertTrue(ConflictAnalyzer.touches(new Range(5, 8), new Range(9, 9)));
        assertTrue(ConflictAnalyzer.touches(new Range(5, 4), new Range(5, 6)));
        assertFalse(ConflictAnalyzer.touches(new Range(5, 8), new Range(11, 12)));
    }

    // ------------------------------------------------------------------ member diff

    static final String V1 = """
            package demo;

            public class RoleService {
                public int updateRole(Role role) {
                    int rows = mapper.update(role);
                    cache.evict(role.getRoleId());
                    log.info("updated {}", role.getRoleId());
                    return rows;
                }

                public void keep() {
                    System.out.println("unchanged body here");
                }
            }
            """;

    @Test
    void renameIsDetectedFromTheBody() {
        String v2 = V1.replace("updateRole(Role role)", "modifyRole(Role role)");
        List<Change> cs = MemberDiff.diff(Members.of("RoleService.java", V1), Members.of("RoleService.java", v2));
        Change c = only(cs, MemberDiff.Kind.RENAMED);
        assertEquals("M:demo.RoleService#updateRole(Role)", c.before().id());
        assertEquals("M:demo.RoleService#modifyRole(Role)", c.after().id());
        assertTrue(cs.stream().noneMatch(x -> x.kind() == MemberDiff.Kind.ADDED || x.kind() == MemberDiff.Kind.DELETED), cs.toString());
    }

    @Test
    void signatureChangeAndMoveToAnotherClassAreDistinguished() {
        String sig = V1.replace("updateRole(Role role)", "updateRole(Role role, boolean refresh)");
        Change s = only(MemberDiff.diff(Members.of("RoleService.java", V1), Members.of("RoleService.java", sig)), MemberDiff.Kind.SIGNATURE_CHANGED);
        assertEquals("M:demo.RoleService#updateRole(Role,boolean)", s.after().id());

        String moved = V1.replace("public class RoleService", "public class RoleManager");
        List<Members.M> before = Members.of("RoleService.java", V1), after = Members.of("RoleManager.java", moved);
        List<Change> moves = MemberDiff.diff(before, after).stream().filter(c -> c.kind() == MemberDiff.Kind.MOVED).toList();
        assertTrue(moves.stream().anyMatch(c -> c.before().id().equals("M:demo.RoleService#updateRole(Role)")
                && c.after().id().equals("M:demo.RoleManager#updateRole(Role)")), moves.toString());
    }

    @Test
    void unrelatedMethodsAreNotPairedAsRenames() {
        String keep = "    public void keep() {\n        System.out.println(\"unchanged body here\");\n    }\n";
        assertTrue(V1.contains(keep));
        String v2 = V1.replace(keep, "    public long countUsers(String dept) {\n        return userMapper.countByDept(dept, true, 10);\n    }\n");
        List<Change> cs = MemberDiff.diff(Members.of("RoleService.java", V1), Members.of("RoleService.java", v2));
        assertTrue(cs.stream().anyMatch(c -> c.kind() == MemberDiff.Kind.ADDED), cs.toString());
        assertTrue(cs.stream().anyMatch(c -> c.kind() == MemberDiff.Kind.DELETED), cs.toString());
        assertTrue(cs.stream().noneMatch(c -> c.kind() == MemberDiff.Kind.RENAMED), cs.toString());
    }

    // ------------------------------------------------------------------ history attribution

    @Test
    void historyAttributesRenameAndEditToTheirMainlineCommits(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Path f = dir.resolve("src/demo/RoleService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, V1);
        git(dir, "add", ".");
        commit(dir, "fork point");
        String fork = git(dir, "rev-parse", "HEAD").trim();
        Files.writeString(f, V1.replace("updateRole(Role role)", "modifyRole(Role role)"));
        commit(dir, "rename updateRole to modifyRole");
        String rename = git(dir, "rev-parse", "HEAD").trim();
        Files.writeString(f, Files.readString(f).replace("log.info(\"updated {}\", role.getRoleId());", "log.info(\"modified {}\", role.getRoleId());"));
        commit(dir, "change the log line");
        String edit = git(dir, "rev-parse", "HEAD").trim();

        History h = new History(new GitRunner(dir));
        List<Event> lineage = h.lineage(fork, edit, "src/demo/RoleService.java", "M:demo.RoleService#modifyRole(Role)");
        assertEquals(2, lineage.size(), lineage.toString());
        Event first = lineage.get(0), second = lineage.get(1);
        assertEquals(rename, first.commit.sha());
        assertEquals("RENAMED", first.kind);
        assertEquals("RoleService#updateRole(Role)", first.from);
        assertEquals(edit, second.commit.sha());
        assertEquals("MODIFIED", second.kind);

        // blame of the method body: the edited line belongs to the edit commit, the others predate the fork
        List<BlameRun> blame = h.blame(fork, edit, "src/demo/RoleService.java", 5, 8);
        BlameRun logLine = blame.stream().filter(b -> b.start() <= 7 && b.end() >= 7).findFirst().orElseThrow();
        assertEquals(edit, logLine.commit().sha());
        assertTrue(blame.stream().filter(b -> b.start() <= 5 && b.end() >= 5).findFirst().orElseThrow().boundary());
    }

    @Test
    void fileHistoryIsTheSameWithTheFastPlainLogAndWithFollow(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Path f = dir.resolve("src/demo/RoleService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, V1);
        commit(dir, "fork point");
        String fork = git(dir, "rev-parse", "HEAD").trim();
        // a side branch merged back: the plain log lists the merge, --follow does not
        git(dir, "checkout", "-q", "-b", "side");
        Files.writeString(f, V1.replace("updateRole(Role role)", "modifyRole(Role role)"));
        commit(dir, "rename on a side branch");
        git(dir, "checkout", "-q", "main");
        Files.writeString(dir.resolve("README"), "x\n");
        commit(dir, "unrelated");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@x", "merge", "-q", "--no-ff", "-m", "merge side", "side");
        // a file renamed in the window: only --follow finds its older commits
        Path g = dir.resolve("src/demo/Other.java");
        Files.writeString(g, "package demo;\nclass Other {\n    int a() { return 1; }\n}\n");
        commit(dir, "add Other");
        Files.writeString(g, Files.readString(g).replace("return 1;", "return 2;"));
        commit(dir, "edit Other");
        git(dir, "mv", "src/demo/Other.java", "src/demo/Moved.java");
        commit(dir, "move Other");
        String tip = git(dir, "rev-parse", "HEAD").trim();

        History h = new History(new GitRunner(dir));
        for (String path : List.of("src/demo/RoleService.java", "src/demo/Moved.java", "src/demo/Other.java", "README")) {
            String follow = git(dir, "-c", "core.quotePath=false", "log", "--follow", "-M", "--name-status",
                    "--format=%x1e%H", fork + ".." + tip, "--", path);
            List<String> expected = new ArrayList<>();
            for (String chunk : follow.split("\u001e")) if (!chunk.isBlank()) expected.add(chunk.split("\n")[0].trim());
            java.util.Collections.reverse(expected);
            List<String> got = new ArrayList<>();
            for (History.FileCommit fc : h.fileCommits(fork, tip, path)) got.add(fc.commit().sha());
            assertEquals(expected, got, path);
        }
        assertEquals(3, h.fileCommits(fork, tip, "src/demo/Moved.java").size(), "followed through the move");

        // the plain log is used only when every commit merely modifies the path
        assertTrue(History.onlyModifications("\u001ea\u001f\n\nM\tx\n\u001eb\u001f\n\nM\tx\n"));
        assertFalse(History.onlyModifications(""), "empty: the path may not exist at the end, ask --follow");
        assertFalse(History.onlyModifications("\u001ea\u001f\n\nA\tx\n"));
        assertFalse(History.onlyModifications("\u001ea\u001f\n\nR090\ty\tx\n"));
        assertFalse(History.onlyModifications("\u001ea\u001f\n"), "a commit without a status line");

        // a prefetched history is the same as one read on demand
        History p = new History(new GitRunner(dir));
        p.prefetch(fork, tip, "src/demo/Moved.java");
        assertEquals(h.fileCommits(fork, tip, "src/demo/Moved.java").toString(), p.fileCommits(fork, tip, "src/demo/Moved.java").toString());
    }

    @Test
    void oneCatFileProcessReadsLikeSeparateOnes(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Files.createDirectories(dir.resolve("src/a b"));
        Files.writeString(dir.resolve("src/a b/X.java"), "class X {}\n// é\n");
        Files.writeString(dir.resolve("empty.txt"), "");
        commit(dir, "files");
        String head = git(dir, "rev-parse", "HEAD").trim();
        GitRunner g = new GitRunner(dir);
        for (int round = 0; round < 2; round++) {
            assertEquals("class X {}\n// é\n", g.show(head, "src/a b/X.java"), "path with a space, UTF-8 content");
            assertEquals("", g.show(head, "empty.txt"));
            assertNull(g.show(head, "src/missing.java"), "missing path");
            assertNull(g.show(head, "src"), "a directory is not a file");
            assertNull(g.show("0000000000000000000000000000000000000000", "empty.txt"), "unknown commit");
            String blob = git(dir, "rev-parse", head + ":empty.txt").trim();
            assertEquals("", g.blob(blob));
            assertNull(g.blob(head), "a commit is not a blob");
            g.close(); // the next read starts a new process
        }
    }

    @Test
    void simulatedPicksAreTheSameCommitsOnEveryRun(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Path f = dir.resolve("src/demo/RoleService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, V1);
        commit(dir, "fork point");
        git(dir, "checkout", "-q", "-b", "tenant");
        Files.writeString(f, V1.replace("cache.evict(role.getRoleId());", "cache.evict(role.getRoleId()); // tenant"));
        commit(dir, "tenant edit");
        git(dir, "checkout", "-q", "main");
        Files.writeString(f, V1.replace("cache.evict(role.getRoleId());", "cache.evictAll();"));
        commit(dir, "first pick");
        Files.writeString(dir.resolve("README"), "x\n");
        commit(dir, "unrelated");
        Files.writeString(f, Files.readString(f).replace("log.info(\"updated {}\", role.getRoleId());", "log.info(\"done\");"));
        commit(dir, "second pick");
        String second = git(dir, "rev-parse", "HEAD").trim(), first = git(dir, "rev-parse", "HEAD~2").trim();

        GitRunner g = new GitRunner(dir);
        ConflictSession a = ConflictSession.simulate(g, "tenant", first + "," + second);
        Thread.sleep(1100); // commit-tree would otherwise stamp a later second into the commits
        ConflictSession b = ConflictSession.simulate(g, "tenant", first + "," + second);
        assertEquals(a.featureTip, b.featureTip);
        assertEquals(a.steps.get(1).ours, b.steps.get(1).ours, "the simulated first pick");
        assertTrue(git(dir, "log", "-1", "--format=%ad %cd", "--date=raw", a.steps.get(1).ours)
                .startsWith(git(dir, "log", "-1", "--format=%ad", "--date=raw", first).trim()), "dated like the pick");
    }

    @Test
    void liveCherryPickAndOfflineSimulationSeeTheSameConflict(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Path f = dir.resolve("src/demo/RoleService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, V1);
        git(dir, "add", ".");
        commit(dir, "fork point");
        git(dir, "checkout", "-q", "-b", "tenant");
        Files.writeString(f, V1.replace("cache.evict(role.getRoleId());", "cache.evict(role.getRoleId()); // tenant"));
        commit(dir, "tenant edit");
        git(dir, "checkout", "-q", "main");
        Files.writeString(f, V1.replace("cache.evict(role.getRoleId());", "cache.evictAll();"));
        commit(dir, "feature");
        String pick = git(dir, "rev-parse", "HEAD").trim();

        GitRunner g = new GitRunner(dir);
        ConflictSession sim = ConflictSession.simulate(g, "tenant", pick);
        assertEquals(1, sim.steps.size());
        assertEquals(List.of("src/demo/RoleService.java"), new ArrayList<>(sim.steps.get(0).unmerged.keySet()));

        git(dir, "checkout", "-q", "tenant");
        new ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@x", "cherry-pick", pick).directory(dir.toFile())
                .redirectErrorStream(true).start().waitFor();
        ConflictSession live = ConflictSession.live(g);
        assertNotNull(live);
        assertEquals("cherry-pick", live.mode);
        ConflictSession.Unmerged u = live.steps.get(0).unmerged.get("src/demo/RoleService.java");
        assertEquals("CONTENT", u.kind());
        assertEquals(sim.steps.get(0).unmerged.get("src/demo/RoleService.java").sha, u.sha);
        List<MergeHunks.Region> rs = MergeHunks.regions(g.blob(u.sha.get(1)), g.blob(u.sha.get(2)), g.blob(u.sha.get(3)));
        assertEquals(1, rs.size());
        assertEquals(new Range(6, 6), rs.get(0).ours());
    }

    // ------------------------------------------------------------------ entry points

    @Test
    void callersAreTracedToTheHttpEndpointThroughTheInterface() {
        var snap = TestSupport.snap("ours",
                "src/main/java/demo/RoleController.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/system/role")
                        public class RoleController {
                            private IRoleService roleService;
                            @PreAuthorize("@ss.hasPermi('system:role:edit')")
                            @PutMapping
                            public int edit(Role role) { return roleService.updateRole(role); }
                        }
                        """,
                "src/main/java/demo/IRoleService.java", """
                        package demo;
                        public interface IRoleService { int updateRole(Role role); }
                        """,
                "src/main/java/demo/RoleServiceImpl.java", """
                        package demo;
                        public class RoleServiceImpl implements IRoleService {
                            public int updateRole(Role role) { return 1; }
                        }
                        """,
                "src/main/java/demo/Jobs.java", """
                        package demo;
                        public class Jobs {
                            @Scheduled(cron = "0 0 * * * ?")
                            public void nightly() { }
                        }
                        """,
                "src/main/java/demo/Role.java", "package demo; public class Role { }");
        CodeIndex idx = CodeIndex.build(snap, null, false);
        Map<String, List<String[]>> users = Map.of(
                "M:demo.IRoleService#updateRole(Role)", List.<String[]>of(new String[] {"M:demo.RoleController#edit(Role)", "src/main/java/demo/RoleController.java:9"}));
        EntryPoints ep = new EntryPoints(idx, k -> users.getOrDefault(k, List.of()));
        List<EntryPoint> found = ep.trace(List.of("M:demo.RoleServiceImpl#updateRole(Role)"));
        assertEquals(1, found.size(), found.toString());
        EntryPoint e = found.get(0);
        assertEquals("HTTP", e.type);
        assertEquals("PUT /system/role", e.route);
        assertEquals("@ss.hasPermi('system:role:edit')", e.permission);
        // call path from the entry point down to the traced symbol
        assertEquals("M:demo.RoleController#edit(Role)", e.path.get(0));
        assertEquals("M:demo.RoleServiceImpl#updateRole(Role)", e.path.get(e.path.size() - 1));
        EntryPoint job = ep.describe("M:demo.Jobs#nightly()");
        assertNotNull(job);
        assertEquals("SCHEDULED", job.type);
        assertTrue(job.route.contains("0 0 * * * ?"), job.route);
    }

    // ------------------------------------------------------------------ helpers

    static Change only(List<Change> cs, MemberDiff.Kind k) {
        List<Change> m = cs.stream().filter(c -> c.kind() == k).toList();
        assertEquals(1, m.size(), "expected one " + k + " in " + cs);
        return m.get(0);
    }

    static String lines(String... ls) {
        return String.join("\n", ls) + "\n";
    }

    static void commit(Path dir, String msg) throws IOException, InterruptedException {
        git(dir, "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-q", "-m", msg);
    }

    static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        return out;
    }
}
