package com.branchdiff.gui;

import com.branchdiff.gui.GitQueries.Branch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search semantics of the branch popup, like IDEA's: the query is split on whitespace and every word must occur in the
 * branch name (case-insensitive substring). Local branches come first, then remote-tracking ones, each group under a
 * header row and in the given order.
 */
public final class BranchFilter {
    private BranchFilter() {
    }

    /** one popup row: a group header ({@code branch == null}) or a branch */
    public record Row(String header, Branch branch) {
        public boolean isHeader() {
            return branch == null;
        }
    }

    public static boolean matches(String name, String query) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : words(query)) if (!n.contains(w)) return false;
        return true;
    }

    static String[] words(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    /** the rows to show for {@code query}; empty when nothing matches */
    public static List<Row> rows(List<Branch> branches, String query) {
        List<Row> local = new ArrayList<>(), remote = new ArrayList<>();
        for (Branch b : branches) {
            if (!matches(b.name(), query)) continue;
            (b.remote() ? remote : local).add(new Row(null, b));
        }
        List<Row> res = new ArrayList<>();
        if (!local.isEmpty()) {
            res.add(new Row("Local branches", null));
            res.addAll(local);
        }
        if (!remote.isEmpty()) {
            res.add(new Row("Remote branches", null));
            res.addAll(remote);
        }
        return res;
    }

    /** index of the next selectable (branch) row from {@code from} in direction {@code dir}; -1 when none */
    public static int nextBranchRow(List<Row> rows, int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) if (!rows.get(i).isHeader()) return i;
        return -1;
    }

    /** the row to preselect: the current branch when visible, else the first branch */
    public static int initialRow(List<Row> rows, String current) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.isHeader() && r.branch().name().equals(current)) return i;
        }
        return nextBranchRow(rows, -1, 1);
    }
}
