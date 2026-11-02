package com.branchdiff.util;

import java.util.ArrayList;
import java.util.List;

/** Minimal LCS line diff for short method bodies (report readability only). */
public final class LineDiff {
    private LineDiff() {
    }

    public static List<String> diff(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        int n = x.length, m = y.length;
        if ((long) n * m > 4_000_000L) return List.of("(method too large for inline diff)");
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[i].equals(y[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (x[i].equals(y[j])) {
                out.add("  " + x[i]);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + x[i++]);
            } else {
                out.add("+ " + y[j++]);
            }
        }
        while (i < n) out.add("- " + x[i++]);
        while (j < m) out.add("+ " + y[j++]);
        return out;
    }

    /** Only changed lines with one line of context. */
    public static List<String> compact(List<String> diff) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < diff.size(); k++) {
            boolean changed = !diff.get(k).startsWith("  ");
            boolean near = (k > 0 && !diff.get(k - 1).startsWith("  ")) || (k + 1 < diff.size() && !diff.get(k + 1).startsWith("  "));
            if (changed || near) out.add(diff.get(k));
            else if (!out.isEmpty() && !out.get(out.size() - 1).equals("  ...")) out.add("  ...");
        }
        return out;
    }
}
