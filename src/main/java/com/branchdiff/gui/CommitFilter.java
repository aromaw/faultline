package com.branchdiff.gui;

import com.branchdiff.gui.GitQueries.CommitRow;

import java.util.Locale;

/**
 * Search box semantics of the commit list: the query is split on whitespace and every word must match the commit's
 * message or author (case-insensitive substring) or be a prefix of its sha. {@code author:name} and {@code sha:abc}
 * restrict a word to one field.
 */
public final class CommitFilter {
    private final String[] words;

    public CommitFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        this.words = q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    public boolean isEmpty() {
        return words.length == 0;
    }

    public boolean matches(CommitRow c) {
        String subject = c.subject().toLowerCase(Locale.ROOT);
        String author = c.author().toLowerCase(Locale.ROOT);
        String sha = c.sha().toLowerCase(Locale.ROOT);
        for (String w : words) {
            boolean ok;
            if (w.startsWith("author:")) ok = author.contains(w.substring(7));
            else if (w.startsWith("sha:")) ok = sha.startsWith(w.substring(4));
            else ok = subject.contains(w) || author.contains(w) || sha.startsWith(w);
            if (!ok) return false;
        }
        return true;
    }
}
