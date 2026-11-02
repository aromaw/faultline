package com.branchdiff.conflict;

import com.branchdiff.Options;
import com.branchdiff.analysis.Analyzer;
import com.branchdiff.git.GitRunner;
import com.branchdiff.git.SnapshotLoader;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code faultline conflicts}: finds the conflicted state (live or simulated), runs the regular semantic analysis
 * with ours = tenant, theirs = picked feature, base = picked commit's parent, then explains every conflict hunk.
 */
public final class ConflictCommand {
    /** viewKey -> [ours, base, theirs] contents of the conflicted files (for the HTML report) */
    public final Map<String, String[]> contents = new LinkedHashMap<>();
    public Analyzer analyzer;

    public Report run(Options o) {
        GitRunner git = new GitRunner(o.repo.toAbsolutePath().normalize());
        git.run("rev-parse", "--git-dir");
        ConflictSession s;
        if (o.pick != null) {
            s = ConflictSession.simulate(git, o.onto != null ? o.onto : "HEAD", o.pick);
        } else {
            if (o.onto != null) throw new IllegalArgumentException("--onto needs --pick");
            s = ConflictSession.live(git);
            if (s == null) {
                throw new IllegalArgumentException("the repository is not in the middle of a cherry-pick, merge or revert "
                        + "(no CHERRY_PICK_HEAD / MERGE_HEAD / REVERT_HEAD); use --onto <tenant> --pick <commit|A..B> to simulate one");
            }
        }
        if (o.verbose) {
            System.err.println("[faultline] " + s.mode + ": tenant=" + s.tenant + " picks=" + s.picks + " base=" + s.featureBase
                    + " theirs=" + s.featureTip);
        }
        String fork = ConflictAnalyzer.forkPoint(git, s, o.mainline);
        if (o.verbose) System.err.println("[faultline] fork point (merge-base of tenant and mainline) = " + fork);
        o.ours = s.tenant;
        o.theirs = s.featureTip;
        Snapshot forkSnap = new SnapshotLoader(git).load("fork", fork, fork, SnapshotLoader.normalizeDir(o.serviceDir), List.of());
        CodeIndex forkIndex = CodeIndex.build(forkSnap, null, o.includeTests);
        analyzer = new Analyzer(o);
        String mainline = o.mainline;
        analyzer.after = ctx -> {
            long t0 = System.nanoTime();
            ConflictAnalyzer ca = new ConflictAnalyzer(git, s, mainline, ctx, forkSnap, forkIndex);
            try {
                ctx.report().conflicts = ca.analyze();
            } catch (RuntimeException | StackOverflowError e) {
                ctx.warnings().add("conflict analysis failed: " + e);
                if (o.verbose) e.printStackTrace();
            }
            contents.putAll(ca.contents);
            if (o.verbose) System.err.println("[faultline] conflict analysis in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        };
        Report r = analyzer.runFromGit(s.featureBase);
        r.repo = git.repo().toString();
        r.refs.put("mergeBase", s.featureBase);
        r.refs.put("fork", fork);
        r.refs.put("mode", s.mode);
        return r;
    }
}
