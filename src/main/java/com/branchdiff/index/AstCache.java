package com.branchdiff.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed Java files of all {@link CodeIndex}es, bounded by the total size of their sources.
 *
 * <p>An index keeps only a summary of each file (classes, members, signatures, normalized text); the AST of a file is
 * parsed again from the snapshot when an analysis asks for it, and the least recently used ASTs are dropped when the
 * cache holds more source than its budget. A JavaParser AST takes about 25-30 bytes of heap per source character, so
 * keeping every file of every version (fork point, base, ours, theirs) did not fit a 1 GB heap for a project of a
 * few thousand files. Re-parsing is deterministic, so a dropped and re-parsed file gives the same answers.
 *
 * <p>The budget defaults to max heap / 160 characters (about 6.7 M characters, roughly 180 MB of ASTs, for a 1 GB
 * heap) and can be set with {@code -Dfaultline.astCacheChars=<n>}.
 */
public final class AstCache {
    /** one parsed file and its type declarations in {@code findAll(TypeDeclaration.class)} order */
    record Entry(CompilationUnit cu, List<TypeDeclaration<?>> types, int chars) {
    }

    private record Key(CodeIndex index, String path) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.index == index && k.path.equals(path);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(index) * 31 + path.hashCode();
        }
    }

    private static final Map<Key, Entry> LRU = new LinkedHashMap<>(256, 0.75f, true);
    private static long budget = defaultBudget();
    private static long chars;
    private static long evictedSinceFacadeClear;
    private static long loads, hits;

    private AstCache() {
    }

    static long defaultBudget() {
        String p = System.getProperty("faultline.astCacheChars");
        if (p != null) {
            try {
                return Math.max(0, Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Math.max(2_000_000L, Runtime.getRuntime().maxMemory() / 160);
    }

    /** sets the budget in source characters (tests); a negative value restores the default */
    public static synchronized void setBudget(long sourceChars) {
        budget = sourceChars < 0 ? defaultBudget() : sourceChars;
        trim();
    }

    public static synchronized long budget() {
        return budget;
    }

    static synchronized Entry get(CodeIndex index, String path) {
        Key k = new Key(index, path);
        Entry e = LRU.get(k);
        if (e != null) {
            hits++;
            return e;
        }
        return null;
    }

    static synchronized void put(CodeIndex index, String path, Entry e) {
        loads++;
        Entry old = LRU.put(new Key(index, path), e);
        if (old != null) chars -= old.chars();
        chars += e.chars();
        trim();
    }

    private static void trim() {
        Iterator<Map.Entry<Key, Entry>> it = LRU.entrySet().iterator();
        // the most recently used entry always stays, however large it is
        while (chars > budget && LRU.size() > 1 && it.hasNext()) {
            Entry e = it.next().getValue();
            it.remove();
            chars -= e.chars();
            evictedSinceFacadeClear += e.chars();
        }
        if (evictedSinceFacadeClear > Math.max(budget, 1)) {
            // the symbol solver's facades cache resolved types per AST node (strongly), which would keep every
            // evicted AST alive; dropping them bounds the retained ASTs to about twice the budget
            evictedSinceFacadeClear = 0;
            com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();
        }
    }

    /** drops every AST of {@code index} (the index is no longer used) */
    public static synchronized void release(CodeIndex index) {
        Iterator<Map.Entry<Key, Entry>> it = LRU.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            if (e.getKey().index == index) {
                chars -= e.getValue().chars();
                it.remove();
            }
        }
    }

    /** drops every cached AST */
    public static synchronized void clear() {
        LRU.clear();
        chars = 0;
        evictedSinceFacadeClear = 0;
    }

    /** "files cached / source chars / budget / parses / hits", for --verbose */
    public static synchronized String stats() {
        return LRU.size() + " files, " + chars + " of " + budget + " chars cached, " + loads + " parses, " + hits + " hits";
    }
}
