package com.branchdiff.gui;

import com.branchdiff.Main;
import com.branchdiff.Options;
import com.branchdiff.conflict.ConflictCommand;
import com.branchdiff.conflict.ConflictMarkdown;
import com.branchdiff.git.GitRunner;
import com.branchdiff.report.Report;
import com.branchdiff.report.html.HtmlRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One offline conflict analysis for the GUI: the same {@link ConflictCommand} the CLI runs with {@code --onto
 * --pick --mainline} (git merge-tree; the worktree, the index and the refs are never touched). Only the report and
 * the conflicted files' contents are kept; snapshots, ASTs and symbol solver caches are dropped when it returns.
 */
public final class AnalysisJob {
    private AnalysisJob() {
    }

    /** What the GUI shows and exports. Other files are read from git on demand. */
    public record Result(Report report, Map<String, String[]> contents, GitRunner git, String baseCommit,
                         String oursCommit, String theirsCommit, long millis) {
        /** a {@code path:line} location's file in one version ("ours" / "theirs" / "base"); null when absent */
        public String file(String side, String path) {
            return HtmlRenderer.gitSource(git, baseCommit, oursCommit, theirsCommit).read(side, path);
        }
    }

    public static Result run(Path repo, String onto, String mainline, String pickSpec, boolean maven,
                             Consumer<String> progress, BooleanSupplier cancelled) {
        long t0 = System.nanoTime();
        Options o = new Options();
        o.command = "conflicts";
        o.repo = repo;
        o.onto = onto;
        o.mainline = mainline;
        o.pick = pickSpec;
        o.useMaven = maven;
        o.progress = progress;
        o.cancelled = cancelled;
        ConflictCommand cc = new ConflictCommand();
        Report r = cc.run(o);
        Result res = new Result(r, new LinkedHashMap<>(cc.contents), cc.analyzer.lastGit, cc.analyzer.lastBase.commit(),
                cc.analyzer.lastOurs.commit(), cc.analyzer.lastTheirs.commit(), (System.nanoTime() - t0) / 1_000_000);
        o.phase("done");
        return res;
    }

    /** writes the existing CLI report formats: "html", "md" or "json" */
    public static void export(Result res, String format, Path out) throws Exception {
        String text = switch (format) {
            case "json" -> Main.toJson(res.report());
            case "html" -> HtmlRenderer.render(res.report(), HtmlRenderer.gitSource(res.git(), res.baseCommit(),
                    res.oursCommit(), res.theirsCommit()), res.contents());
            default -> ConflictMarkdown.render(res.report());
        };
        if (out.toAbsolutePath().getParent() != null) Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }
}
