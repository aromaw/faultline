package com.branchdiff.analysis;

import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.index.MapperXmlIndex.Body;
import com.branchdiff.report.Finding;
import com.branchdiff.report.Finding.Category;
import com.branchdiff.report.Finding.Confidence;
import com.branchdiff.report.Finding.Severity;
import com.branchdiff.util.LineDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Three-way comparison of values that have no method-level change set: field / constant initializers and MyBatis
 * mapper XML element bodies. Same model as methods:
 * <ul>
 *   <li>base == ours != theirs: theirs-only change. A whole-branch merge carries it; a backport/cherry-pick to a
 *   separately maintained line must port it. Reported LOW (like a same-class THEIRS_NEW dependency).</li>
 *   <li>base != ours, base != theirs, ours != theirs (or both added different values): conflicting edits, reported
 *   HIGH (like BOTH_MODIFIED) with git's textual-conflict prediction.</li>
 *   <li>no change, ours-only change, identical change on both sides, or ours removed it: not reported.</li>
 * </ul>
 * Only the service directory is in scope (sibling modules are lookups only).
 */
public final class ValueChangeAnalyzer {
    private final CodeIndex base;
    private final CodeIndex ours;
    private final CodeIndex theirs;
    private final Map<String, Integer> conflictCache = new HashMap<>();

    public ValueChangeAnalyzer(CodeIndex base, CodeIndex ours, CodeIndex theirs) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
    }

    public List<Finding> analyze() {
        List<Finding> res = new ArrayList<>(fields());
        res.addAll(mapperBodies());
        return res;
    }

    // ------------------------------------------------------------------ field initializers

    List<Finding> fields() {
        List<Finding> res = new ArrayList<>();
        for (ClassInfo tc : theirs.classes.values()) {
            if (!tc.inScope) continue;
            ClassInfo oc = ours.classes.get(tc.fqn);
            if (oc == null || !oc.inScope) continue;
            ClassInfo bc = base.classes.get(tc.fqn);
            for (FieldInfo tf : tc.fields.values()) {
                FieldInfo of = oc.fields.get(tf.name);
                if (of == null) continue; // ours removed it or never had it: not a value question
                FieldInfo bf = bc == null ? null : bc.fields.get(tf.name);
                String b = bf == null ? null : bf.initializer;
                String o = of.initializer;
                String t = tf.initializer;
                if (Objects.equals(o, t) || Objects.equals(b, t)) continue; // identical, or theirs did not change it
                String kind = tf.enumConstant ? "ENUM_CONSTANT" : tf.isConstant() ? "CONSTANT" : "FIELD";
                String symbol = tf.owner.fqn + "." + tf.name;
                Finding f;
                if (bf != null && Objects.equals(b, o)) {
                    f = new Finding(Category.CONSTANT_VALUE, Severity.LOW, Confidence.CERTAIN, "THEIRS_CHANGED_VALUE", kind,
                            symbol, "THEIRS_CHANGED", "Theirs changed the " + (tf.enumConstant ? "arguments" : "initializer")
                            + "; ours still has the merge-base value. A whole-branch merge carries theirs' value; "
                            + "a backport/cherry-pick to a separately maintained line must port it");
                } else {
                    boolean added = bf == null;
                    f = new Finding(Category.CONSTANT_VALUE, Severity.HIGH, Confidence.CERTAIN,
                            added ? "BOTH_ADDED_VALUE" : "BOTH_CHANGED_VALUE", kind, symbol, "BOTH",
                            added ? "Both sides added this field with different values"
                                    : "Ours and theirs changed the value differently since the merge-base");
                    f.detail("gitTextualConflict", textual(bc == null ? null : bc.file, oc.file, tc.file));
                }
                f.oursAt(of.location()).theirsAt(tf.location());
                if (bf != null) f.detail("base", bf.location());
                addDiffs(f, b, o, t);
                res.add(f);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ MyBatis XML element bodies

    List<Finding> mapperBodies() {
        List<Finding> res = new ArrayList<>();
        for (String ns : theirs.mapperXml.bodyNamespaces()) {
            Map<String, Body> tb = theirs.mapperXml.bodies(ns);
            Map<String, Body> ob = ours.mapperXml.bodies(ns);
            Map<String, Body> bb = base.mapperXml.bodies(ns);
            for (Body t : tb.values()) {
                Body o = ob.get(t.key());
                if (o == null) continue; // ours lacks the element: covered by the mapper id checks, or ours removed it
                Body b = bb.get(t.key());
                String bt = b == null ? null : b.normalized();
                if (Objects.equals(o.normalized(), t.normalized()) || Objects.equals(bt, t.normalized())) continue;
                String symbol = ns + "#" + t.id();
                Finding f;
                if (b != null && Objects.equals(bt, o.normalized())) {
                    f = new Finding(Category.MYBATIS, Severity.LOW, Confidence.CERTAIN, "THEIRS_CHANGED_STATEMENT",
                            "MAPPER_" + t.tag().toUpperCase(), symbol, "THEIRS_CHANGED",
                            "Theirs changed <" + t.tag() + " id=\"" + t.id() + "\">; ours still has the merge-base version. "
                                    + "A whole-branch merge carries theirs' version; a backport/cherry-pick to a separately "
                                    + "maintained line must port it");
                } else {
                    boolean added = b == null;
                    f = new Finding(Category.MYBATIS, Severity.HIGH, Confidence.CERTAIN,
                            added ? "BOTH_ADDED_STATEMENT" : "BOTH_CHANGED_STATEMENT", "MAPPER_" + t.tag().toUpperCase(),
                            symbol, "BOTH", added
                            ? "Both sides added <" + t.tag() + " id=\"" + t.id() + "\"> with different content"
                            : "Ours and theirs changed <" + t.tag() + " id=\"" + t.id() + "\"> differently since the merge-base");
                    f.detail("gitTextualConflict", textual(b == null ? null : b.file(), o.file(), t.file()));
                }
                f.oursAt(o.location()).theirsAt(t.location());
                f.detail("statement", t.tag() + " id=" + t.id());
                if (b != null) f.detail("base", b.location());
                addDiffs(f, bt, o.normalized(), t.normalized());
                res.add(f);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ helpers

    private static void addDiffs(Finding f, String b, String o, String t) {
        if (b != null) {
            f.detail("diffBaseToOurs", LineDiff.compact(LineDiff.diff(b, nz(o))));
            f.detail("diffBaseToTheirs", LineDiff.compact(LineDiff.diff(b, nz(t))));
        } else {
            f.detail("diffOursToTheirs", LineDiff.compact(LineDiff.diff(nz(o), nz(t))));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private String textual(String basePath, String oursPath, String theirsPath) {
        if (!oursPath.equals(theirsPath) || (basePath != null && !basePath.equals(oursPath))) return "unknown (file moved/renamed)";
        int c = conflictCache.computeIfAbsent(oursPath, p -> MergeFile.conflicts(
                base.snapshot.files().get(p), ours.snapshot.files().get(p), theirs.snapshot.files().get(p)));
        if (c < 0) return "unknown (git merge-file failed)";
        if (c == 0) return "NO - git merges this file cleanly, so only semantic review can catch the contradiction";
        return "YES - " + c + " conflict hunk(s) in " + oursPath;
    }
}
