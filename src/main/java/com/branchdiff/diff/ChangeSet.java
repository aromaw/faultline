package com.branchdiff.diff;

import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.MethodInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Method-level change set base -> side. */
public final class ChangeSet {
    public final String side;
    public final List<MethodChange> changes = new ArrayList<>();
    public final Map<String, MethodChange> byOldId = new LinkedHashMap<>();
    public final Map<String, MethodChange> byNewId = new LinkedHashMap<>();
    public final Set<String> addedClasses = new LinkedHashSet<>();
    public final Set<String> removedClasses = new LinkedHashSet<>();

    private ChangeSet(String side) {
        this.side = side;
    }

    public Map<MethodChange.Kind, Integer> counts() {
        Map<MethodChange.Kind, Integer> m = new EnumMap<>(MethodChange.Kind.class);
        for (MethodChange.Kind k : MethodChange.Kind.values()) m.put(k, 0);
        for (MethodChange c : changes) m.merge(c.kind, 1, Integer::sum);
        return m;
    }

    /** minimal body length (normalized chars) for a body-identical add/remove pair to count as a rename */
    static final int MIN_RENAME_BODY = 40;

    public static ChangeSet compute(String side, CodeIndex base, CodeIndex other) {
        ChangeSet cs = new ChangeSet(side);
        Set<String> all = new LinkedHashSet<>(base.classes.keySet());
        all.addAll(other.classes.keySet());
        for (String fqn : all) {
            ClassInfo b = base.classes.get(fqn);
            ClassInfo o = other.classes.get(fqn);
            if (b == null) cs.addedClasses.add(fqn);
            if (o == null) cs.removedClasses.add(fqn);
            Map<String, MethodInfo> bm = b == null ? Map.of() : b.methods;
            Map<String, MethodInfo> om = o == null ? Map.of() : o.methods;
            List<MethodInfo> removed = new ArrayList<>();
            List<MethodInfo> added = new ArrayList<>();
            for (MethodInfo m : bm.values()) {
                MethodInfo n = om.get(m.key());
                if (n == null) removed.add(m);
                else if (!n.normalizedDecl.equals(m.normalizedDecl)) {
                    boolean declChanged = !n.returnType.equals(m.returnType) || n.isStatic != m.isStatic
                            || n.isAbstract != m.isAbstract;
                    cs.add(new MethodChange(MethodChange.Kind.MODIFIED, fqn, m, n, declChanged, false, false));
                }
            }
            for (MethodInfo n : om.values()) if (!bm.containsKey(n.key())) added.add(n);
            // pair removed+added -> signature change (same name) or rename (same body)
            for (MethodInfo r : new ArrayList<>(removed)) {
                MethodInfo best = null;
                int bestScore = -1;
                for (MethodInfo a : added) {
                    if (!a.name.equals(r.name)) continue;
                    int score = commonParams(r.params, a.params);
                    if (score > bestScore) {
                        best = a;
                        bestScore = score;
                    }
                }
                if (best != null) {
                    removed.remove(r);
                    added.remove(best);
                    cs.add(new MethodChange(MethodChange.Kind.SIGNATURE_CHANGED, fqn, r, best,
                            !best.returnType.equals(r.returnType), false, false));
                }
            }
            for (MethodInfo r : new ArrayList<>(removed)) {
                if (r.constructor || r.normalizedBody.length() < MIN_RENAME_BODY) continue;
                for (MethodInfo a : added) {
                    if (!a.constructor && a.normalizedBody.equals(r.normalizedBody)) {
                        removed.remove(r);
                        added.remove(a);
                        cs.add(new MethodChange(MethodChange.Kind.RENAMED, fqn, r, a, false, false, false));
                        break;
                    }
                }
            }
            for (MethodInfo r : removed) cs.add(new MethodChange(MethodChange.Kind.REMOVED, fqn, r, null, false, false, o == null));
            for (MethodInfo a : added) cs.add(new MethodChange(MethodChange.Kind.ADDED, fqn, null, a, false, b == null, false));
        }
        return cs;
    }

    /**
     * The part of this change set inside the reporting scope (--service-dir): changes of sibling-module classes are
     * dropped. A class counts as in scope if it is in scope in either version.
     */
    public ChangeSet scoped(CodeIndex base, CodeIndex other) {
        ChangeSet cs = new ChangeSet(side);
        java.util.function.Predicate<String> in = fqn -> {
            ClassInfo b = base.classes.get(fqn);
            ClassInfo o = other.classes.get(fqn);
            return (b != null && b.inScope) || (o != null && o.inScope);
        };
        for (MethodChange c : changes) if (in.test(c.classFqn)) cs.add(c);
        for (String a : addedClasses) if (in.test(a)) cs.addedClasses.add(a);
        for (String r : removedClasses) if (in.test(r)) cs.removedClasses.add(r);
        return cs;
    }

    private static int commonParams(List<String> a, List<String> b) {
        int n = 0;
        for (String s : a) if (b.contains(s)) n++;
        return n * 10 - Math.abs(a.size() - b.size());
    }

    private void add(MethodChange c) {
        changes.add(c);
        if (c.oldMethod != null) byOldId.put(c.oldId(), c);
        if (c.newMethod != null) byNewId.put(c.newId(), c);
    }
}
