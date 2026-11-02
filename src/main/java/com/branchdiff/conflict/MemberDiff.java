package com.branchdiff.conflict;

import com.branchdiff.conflict.Members.M;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs the members of two versions (of one file, or of several files of one commit): unchanged / modified by id,
 * then, among the members that disappeared and appeared, signature changes (same owner and name), renames (same
 * owner, similar body) and moves (other owner or file, similar body). Similarity is the Dice coefficient of the
 * multisets of identifier / literal tokens of the normalized bodies.
 */
public final class MemberDiff {
    public static final double RENAME_SIMILARITY = 0.75;
    /** bodies shorter than this many tokens are only paired when identical */
    public static final int MIN_TOKENS = 6;

    public enum Kind {ADDED, DELETED, MODIFIED, SIGNATURE_CHANGED, RENAMED, MOVED}

    public record Change(Kind kind, M before, M after, double similarity) {
        public String id() {
            return after != null ? after.id() : before.id();
        }
    }

    private MemberDiff() {
    }

    public static List<Change> diff(List<M> before, List<M> after) {
        List<Change> res = new ArrayList<>();
        Map<String, M> b = Members.byId(before), a = Members.byId(after);
        List<M> gone = new ArrayList<>(), come = new ArrayList<>();
        for (M m : b.values()) {
            M n = a.get(m.id());
            if (n == null) gone.add(m);
            else if (!m.body().equals(n.body())) res.add(new Change(Kind.MODIFIED, m, n, similarity(m.body(), n.body())));
        }
        for (M n : a.values()) if (!b.containsKey(n.id())) come.add(n);
        // signature changes: same owner, same name, same kind (methods / constructors)
        for (M g : new ArrayList<>(gone)) {
            if (!g.kind().equals("method") && !g.kind().equals("constructor")) continue;
            M best = null;
            double bs = -1;
            for (M c : come) {
                if (!c.kind().equals(g.kind()) || !c.owner().equals(g.owner()) || !c.name().equals(g.name())) continue;
                double s = similarity(g.body(), c.body());
                if (s > bs) {
                    bs = s;
                    best = c;
                }
            }
            if (best != null) {
                gone.remove(g);
                come.remove(best);
                res.add(new Change(Kind.SIGNATURE_CHANGED, g, best, bs));
            }
        }
        // renames and moves by body similarity, best pairs first
        List<Object[]> pairs = new ArrayList<>();
        for (M g : gone) {
            for (M c : come) {
                if (!compatible(g, c)) continue;
                double s = similarity(g.body(), c.body());
                int tokens = Math.min(tokens(g.body()).size(), tokens(c.body()).size());
                if (s >= RENAME_SIMILARITY && (tokens >= MIN_TOKENS || s == 1.0 && !g.body().isBlank())) pairs.add(new Object[] {g, c, s});
            }
        }
        pairs.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
        for (Object[] p : pairs) {
            M g = (M) p[0], c = (M) p[1];
            if (!gone.contains(g) || !come.contains(c)) continue;
            gone.remove(g);
            come.remove(c);
            boolean sameOwner = g.owner().equals(c.owner()) && g.member().path().equals(c.member().path());
            res.add(new Change(sameOwner ? Kind.RENAMED : Kind.MOVED, g, c, (double) p[2]));
        }
        for (M g : gone) res.add(new Change(Kind.DELETED, g, null, 0));
        for (M c : come) res.add(new Change(Kind.ADDED, null, c, 0));
        return res;
    }

    static boolean compatible(M a, M b) {
        String ka = a.kind(), kb = b.kind();
        if (ka.equals(kb)) return !ka.equals("imports");
        return (ka.equals("field") || ka.equals("constant")) && (kb.equals("field") || kb.equals("constant"));
    }

    public static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        Map<String, Integer> ta = tokens(a), tb = tokens(b);
        int na = 0, nb = 0, common = 0;
        for (int v : ta.values()) na += v;
        for (int v : tb.values()) nb += v;
        if (na + nb == 0) return 1.0;
        for (Map.Entry<String, Integer> e : ta.entrySet()) common += Math.min(e.getValue(), tb.getOrDefault(e.getKey(), 0));
        return 2.0 * common / (na + nb);
    }

    private static final Map<String, Map<String, Integer>> CACHE = new HashMap<>();

    static synchronized Map<String, Integer> tokens(String s) {
        Map<String, Integer> c = CACHE.get(s);
        if (c != null) return c;
        Map<String, Integer> res = new LinkedHashMap<>();
        for (String t : s.split("[^\\p{L}\\p{N}_$\"']+")) if (!t.isEmpty()) res.merge(t, 1, Integer::sum);
        if (CACHE.size() > 20000) CACHE.clear();
        CACHE.put(s, res);
        return res;
    }
}
