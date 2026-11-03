package com.branchdiff.gui;

import com.branchdiff.gui.GitQueries.CommitRow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns a set of chosen commits, in whatever order the user clicked them, into the order a cherry-pick applies them. */
public final class PickOrder {
    private PickOrder() {
    }

    /** the chosen commits oldest first (by {@link CommitRow#order}), duplicates removed */
    public static List<CommitRow> applyOrder(Collection<CommitRow> chosen) {
        Map<String, CommitRow> unique = new LinkedHashMap<>();
        for (CommitRow c : chosen) unique.putIfAbsent(c.sha(), c);
        List<CommitRow> res = new ArrayList<>(unique.values());
        res.sort(Comparator.comparingInt(CommitRow::order));
        return res;
    }

    /** the {@code --pick} value: full shas, comma separated, in apply order */
    public static String pickSpec(Collection<CommitRow> chosen) {
        StringBuilder sb = new StringBuilder();
        for (CommitRow c : applyOrder(chosen)) sb.append(sb.length() > 0 ? "," : "").append(c.sha());
        return sb.toString();
    }
}
