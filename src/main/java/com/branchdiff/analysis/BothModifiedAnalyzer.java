package com.branchdiff.analysis;

import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.model.Snapshot;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Confidence;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.util.LineDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Methods changed on both sides since the merge-base: always need human review, even if git merges them cleanly. */
public final class BothModifiedAnalyzer {
    private final Snapshot base;
    private final Snapshot ours;
    private final Snapshot theirs;
    private final Map<String, Integer> conflictCache = new HashMap<>();
    public int identicalChanges = 0;

    public BothModifiedAnalyzer(Snapshot base, Snapshot ours, Snapshot theirs) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
    }

    public List<Finding> analyze(ChangeSet theirsCs, ChangeSet oursCs) {
        List<Finding> res = new ArrayList<>();
        for (MethodChange tc : theirsCs.changes) {
            if (tc.oldMethod != null) {
                MethodChange oc = oursCs.byOldId.get(tc.oldId());
                if (oc == null) continue;
                if (tc.newMethod != null && oc.newMethod != null && tc.newMethod.key().equals(oc.newMethod.key())
                        && tc.newMethod.sameDecl(oc.newMethod)) {
                    identicalChanges++;
                    continue;
                }
                if (tc.newMethod == null && oc.newMethod == null) {
                    identicalChanges++;
                    continue;
                }
                String kind = label(oc) + "/" + label(tc);
                Finding f = new Finding(Category.BOTH_MODIFIED, Severity.HIGH, Confidence.CERTAIN, "BOTH_MODIFIED", kind,
                        tc.oldMethod.id(), "BOTH", "ours: " + oc.kind + ", theirs: " + tc.kind);
                if (oc.newMethod != null) f.oursAt(oc.newMethod.owner.file + ":" + oc.newMethod.line + "-" + oc.newMethod.endLine);
                if (tc.newMethod != null) f.theirsAt(tc.newMethod.owner.file + ":" + tc.newMethod.line + "-" + tc.newMethod.endLine);
                f.detail("base", tc.oldMethod.owner.file + ":" + tc.oldMethod.line);
                String oursText = oc.newMethod == null ? "" : oc.newMethod.normalizedDecl();
                String theirsText = tc.newMethod == null ? "" : tc.newMethod.normalizedDecl();
                f.detail("diffBaseToOurs", LineDiff.compact(LineDiff.diff(tc.oldMethod.normalizedDecl(), oursText)));
                f.detail("diffBaseToTheirs", LineDiff.compact(LineDiff.diff(tc.oldMethod.normalizedDecl(), theirsText)));
                textual(f, tc.oldMethod.owner.file,
                        oc.newMethod != null ? oc.newMethod.owner.file : tc.oldMethod.owner.file,
                        tc.newMethod != null ? tc.newMethod.owner.file : tc.oldMethod.owner.file);
                res.add(f);
            } else if (tc.kind == MethodChange.Kind.ADDED) {
                MethodChange oc = oursCs.byNewId.get(tc.newId());
                if (oc == null || oc.kind != MethodChange.Kind.ADDED) continue;
                if (oc.newMethod.sameDecl(tc.newMethod)) {
                    identicalChanges++;
                    continue;
                }
                Finding f = new Finding(Category.BOTH_MODIFIED, Severity.HIGH, Confidence.CERTAIN, "BOTH_ADDED",
                        "added/added", tc.newMethod.id(), "BOTH", "Both sides added this method with different bodies");
                f.oursAt(oc.newMethod.owner.file + ":" + oc.newMethod.line + "-" + oc.newMethod.endLine);
                f.theirsAt(tc.newMethod.owner.file + ":" + tc.newMethod.line + "-" + tc.newMethod.endLine);
                f.detail("diffOursToTheirs", LineDiff.compact(LineDiff.diff(oc.newMethod.normalizedDecl(), tc.newMethod.normalizedDecl())));
                textual(f, null, oc.newMethod.owner.file, tc.newMethod.owner.file);
                res.add(f);
            }
        }
        return res;
    }

    private void textual(Finding f, String basePath, String oursPath, String theirsPath) {
        if (!oursPath.equals(theirsPath) || (basePath != null && !basePath.equals(oursPath))) {
            f.detail("gitTextualConflict", "unknown (file moved/renamed)");
            return;
        }
        int c = conflictCache.computeIfAbsent(oursPath, p -> MergeFile.conflicts(
                base.files().get(p), ours.files().get(p), theirs.files().get(p)));
        if (c < 0) f.detail("gitTextualConflict", "unknown (git merge-file failed)");
        else if (c == 0) {
            f.detail("gitTextualConflict", "NO - git merges this file cleanly, so only semantic review can catch contradictions");
        } else f.detail("gitTextualConflict", "YES - " + c + " conflict hunk(s) in " + oursPath);
    }

    private static String label(MethodChange c) {
        return switch (c.kind) {
            case MODIFIED -> "modified";
            case REMOVED -> "deleted";
            case SIGNATURE_CHANGED -> "signature-changed";
            case RENAMED -> "renamed";
            case ADDED -> "added";
        };
    }
}
