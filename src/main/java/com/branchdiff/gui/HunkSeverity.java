package com.branchdiff.gui;

import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Need;

/**
 * Badge of a conflict hunk in the GUI tree (a triage aid, not part of the reports):
 * HIGH = taking theirs needs a symbol the tenant lacks or has incompatibly (and the pick does not bring it), or one
 * side deleted the file; MEDIUM = keeping ours affects entry points, the tenant edited the code since the fork, or a
 * need is uncertain / differs; LOW = everything else.
 */
public enum HunkSeverity {
    LOW, MEDIUM, HIGH;

    public static HunkSeverity of(ConflictFile f, Hunk h) {
        if (f != null && f.kind != null && f.kind.startsWith("DELETED_BY")) return HIGH;
        boolean medium = false;
        for (Need n : h.takeTheirs.needs) {
            boolean gap = "MISSING".equals(n.ours) || "CHANGED".equals(n.ours);
            if (gap && !"IN_PICK".equals(n.origin)) {
                if (!n.uncertain) return HIGH;
                medium = true;
            } else if (!"PRESENT".equals(n.ours) && !"IN_PICK".equals(n.origin)) {
                medium = true;
            }
        }
        if (!h.takeOurs.entryPoints.isEmpty() || !h.takeOurs.orphans.isEmpty() || !h.tenant.isEmpty()) medium = true;
        return medium ? MEDIUM : LOW;
    }

    public static HunkSeverity of(ConflictFile f) {
        HunkSeverity s = f.kind != null && f.kind.startsWith("DELETED_BY") ? HIGH : LOW;
        for (Hunk h : f.hunks) s = max(s, of(f, h));
        return s;
    }

    static HunkSeverity max(HunkSeverity a, HunkSeverity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
