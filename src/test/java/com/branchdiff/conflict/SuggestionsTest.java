package com.branchdiff.conflict;

import com.branchdiff.Options;
import com.branchdiff.conflict.ConflictModel.Commit;
import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.conflict.ConflictModel.Range;
import com.branchdiff.conflict.ConflictModel.Suggestion;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.branchdiff.conflict.ConflictModeTest.commit;
import static com.branchdiff.conflict.ConflictModeTest.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Suggested resolutions of conflict hunks. */
class SuggestionsTest {
    static final Commit TENANT = new Commit("aaaaaaaaaaaa", "2026-01-02T00:00:00Z", "t", "tenant edit");
    static final Commit MAINLINE = new Commit("bbbbbbbbbbbb", "2026-01-03T00:00:00Z", "m", "mainline edit");

    // ------------------------------------------------------------------ line-level combine

    @Test
    void editsOnAdjacentLinesAreCombined() {
        Suggestions.Combined c = Suggestions.combine(List.of("a", "b", "c"), List.of("a-ours", "b", "c"), List.of("a", "b-theirs", "c"));
        assertNotNull(c);
        assertEquals(List.of("a-ours", "b-theirs", "c"), c.lines());
    }

    @Test
    void editsOfTheSameLineAreNotCombined() {
        assertNull(Suggestions.combine(List.of("a", "b"), List.of("a-ours", "b"), List.of("a-theirs", "b")));
        assertNull(Suggestions.combine(List.of("a", "b", "c"), List.of("a", "c"), List.of("a", "b2", "c")), "delete vs edit");
    }

    @Test
    void separateInsertionsAtTheSamePlaceKeepOursFirst() {
        Suggestions.Combined c = Suggestions.combine(List.of("x", "}"), List.of("x", "void a() {", "}", "}"), List.of("x", "void b() {", "}", "}"));
        assertNotNull(c);
        assertEquals(List.of("x", "void a() {", "}", "void b() {", "}", "}"), c.lines());
        Suggestions.Combined imports = Suggestions.combine(List.of(), List.of("import a.A;", "import c.C;"), List.of("import c.C;", "import b.B;"));
        assertEquals(List.of("import a.A;", "import c.C;", "import b.B;"), imports.lines(), "an import both add is kept once");
    }

    @Test
    void twoVersionsOfTheSameNewCodeAreNotCombined() {
        List<String> ours = List.of("if (paid) {", "    throw new IllegalStateException();", "}");
        List<String> theirs = List.of("if (paid) {", "    refund();", "}");
        assertNull(Suggestions.combine(List.of(), ours, theirs));
    }

    @Test
    void anInsertionNextToAnEditIsCombined() {
        Suggestions.Combined c = Suggestions.combine(List.of("a", "b"), List.of("a", "b-ours"), List.of("a", "new", "b"));
        assertNotNull(c);
        assertEquals(List.of("a", "new", "b-ours"), c.lines());
    }

    // ------------------------------------------------------------------ decisions

    @Test
    void whitespaceOnlyChangeOfTheirsKeepsOurs() {
        Hunk h = hunk(List.of("int x = 1;"), List.of("int x = 2;"), List.of("int  x = 1; "));
        Suggestion s = Suggestions.suggest(h, file("CONTENT"), Suggestions.Facts.NONE);
        assertEquals(Suggestions.OURS, s.choice);
        assertEquals(Suggestions.HIGH, s.confidence);
        assertEquals(List.of("int x = 2;"), s.resolution);
    }

    @Test
    void untouchedTenantCodeTakesTheirsAndListsWhatToBring() {
        Hunk h = hunk(List.of("a();"), List.of("a();"), List.of("b(Util.x());"));
        h.oursText = List.of("old();");
        h.mainline.add(new Event(MAINLINE, "MODIFIED", "M:A#f()", null, "f()", null));
        Need n = new Need();
        n.symbol = "Util.x()";
        n.ours = ContextModel.MISSING;
        n.origin = "ADDED_ON_MAINLINE";
        h.takeTheirs.needs.add(n);
        Suggestion s = Suggestions.suggest(h, file("CONTENT"), Suggestions.Facts.NONE);
        assertEquals(Suggestions.THEIRS, s.choice);
        assertEquals(Suggestions.MEDIUM, s.confidence);
        assertEquals(List.of("b(Util.x());"), s.resolution);
        assertTrue(s.followUps.get(0).contains("Util.x()"), s.followUps.toString());
    }

    @Test
    void overlappingTenantEditNeedsAPerson() {
        Hunk h = hunk(List.of("x = 1;"), List.of("x = 2;"), List.of("x = 3;"));
        h.tenant.add(new Event(TENANT, "MODIFIED", "M:A#f()", null, "f()", null));
        Suggestion s = Suggestions.suggest(h, file("CONTENT"), Suggestions.Facts.NONE);
        assertEquals(Suggestions.MANUAL, s.choice);
        assertNull(s.resolution);
        assertTrue(s.reasons.get(0).contains(TENANT.shortSha()));
    }

    @Test
    void pickDeletingAFileTheTenantStillUsesKeepsIt() {
        Hunk h = hunk(List.of("class A {}"), List.of("class A { int y; }"), List.of());
        Suggestion s = Suggestions.suggest(h, file("DELETED_BY_THEM"), new Suggestions.Facts(0, List.of("A#y (2 users)"), null, null));
        assertEquals(Suggestions.OURS, s.choice);
        assertTrue(s.headline.contains("git add"));
        assertNull(s.resolution);
    }

    @Test
    void fileTheTenantRenamedIsPortedNotAdded() {
        Hunk h = hunk(List.of("a"), List.of(), List.of("b"));
        Suggestion s = Suggestions.suggest(h, file("DELETED_BY_US"), new Suggestions.Facts(0, List.of(), "RENAMED_ON_MAINLINE", "src/Old.java"));
        assertEquals(Suggestions.MANUAL, s.choice);
        assertTrue(s.headline.contains("src/Old.java"));
    }

    // ------------------------------------------------------------------ end to end

    @Test
    void adjacentTenantAndPickEditsAreCombinedInTheReport(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Path f = dir.resolve("src/demo/RoleService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, ConflictModeTest.V1);
        commit(dir, "fork point");
        git(dir, "checkout", "-q", "-b", "tenant");
        Files.writeString(f, ConflictModeTest.V1.replace("cache.evict(role.getRoleId());", "cache.evict(role.getRoleId()); // tenant"));
        commit(dir, "tenant edit");
        git(dir, "checkout", "-q", "main");
        Files.writeString(f, ConflictModeTest.V1.replace("log.info(\"updated {}\", role.getRoleId());", "log.info(\"done\");"));
        commit(dir, "feature");
        String pick = git(dir, "rev-parse", "HEAD").trim();

        Options o = new Options();
        o.repo = dir;
        o.onto = "tenant";
        o.pick = pick;
        o.mainline = "main";
        o.useMaven = false;
        Report r = new ConflictCommand().run(o);
        Hunk h = r.conflicts.files.get(0).hunks.get(0);
        Suggestion s = h.suggestion;
        assertNotNull(s, String.valueOf(h.notes));
        assertEquals(Suggestions.BOTH, s.choice, s.headline + " " + s.reasons);
        assertEquals(List.of("        cache.evict(role.getRoleId()); // tenant", "        log.info(\"done\");"), s.resolution);
        assertTrue(r.conflicts.checklist.stream().anyMatch(i -> i.action().equals("RESOLVE") && i.why().startsWith("Combine both")));
        assertTrue(ConflictMarkdown.render(r).contains("**Suggestion: combine both**"));
    }

    // ------------------------------------------------------------------ fixtures

    static Hunk hunk(List<String> base, List<String> ours, List<String> theirs) {
        Hunk h = new Hunk();
        h.id = "A.java#1";
        h.path = "src/A.java";
        h.index = 1;
        h.baseText = base;
        h.oursText = ours;
        h.theirsText = theirs;
        h.base = new Range(1, base.size());
        h.ours = new Range(1, ours.size());
        h.theirs = new Range(1, theirs.size());
        return h;
    }

    static ConflictFile file(String kind) {
        ConflictFile f = new ConflictFile();
        f.path = "src/A.java";
        f.kind = kind;
        return f;
    }
}
