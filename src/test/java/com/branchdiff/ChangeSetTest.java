package com.branchdiff;

import com.branchdiff.diff.ChangeSet;
import com.branchdiff.diff.MethodChange;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.model.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static com.branchdiff.TestSupport.snap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSetTest {
    static final String P = "src/main/java/a/A.java";

    static Map<String, MethodChange.Kind> diff(String before, String after) {
        Snapshot b = snap("base", P, before);
        Snapshot a = snap("theirs", P, after);
        ChangeSet cs = ChangeSet.compute("theirs", CodeIndex.build(b, null, false), CodeIndex.build(a, null, false));
        return cs.changes.stream().collect(Collectors.toMap(
                c -> c.kind == MethodChange.Kind.ADDED ? c.newMethod.key() : c.oldMethod.key(), c -> c.kind));
    }

    @Test
    void keyedBySignatureNotLines() {
        String before = """
                package a;
                public class A {
                    public int f(int x) { return x + 1; }
                    public int g(java.util.List<String> l) { return l.size(); }
                    public int h() { return 0; }
                }
                """;
        String after = """
                package a;
                public class A {
                    /** javadoc added */
                    public int h() {
                        // comment only
                        return 0;
                    }

                    public int f(int x) { return x + 2; }
                    public int g(java.util.List<String> l) {
                        return l.size();
                    }
                    public int g(java.util.List<String> l, int y) { return y; }
                    public void k() { }
                }
                """;
        Map<String, MethodChange.Kind> d = diff(before, after);
        assertEquals(MethodChange.Kind.MODIFIED, d.get("f(int)"));
        assertNull(d.get("h()"), "comment/format/move-only change is not a modification");
        assertFalse(d.containsKey("g(List)"), "unchanged overload is untouched");
        assertEquals(MethodChange.Kind.ADDED, d.get("g(List,int)"));
        assertEquals(MethodChange.Kind.ADDED, d.get("k()"));
    }

    @Test
    void detectsSignatureChangeRenameAndRemoval() {
        String before = """
                package a;
                public class A {
                    public String gen(Long id) { return "ORD-" + String.format("%010d", id) + "-" + id.hashCode(); }
                    public int disc(int a, int p) { return a * p; }
                    public void gone() { }
                }
                """;
        String after = """
                package a;
                public class A {
                    public String nextNo(Long id) { return "ORD-" + String.format("%010d", id) + "-" + id.hashCode(); }
                    public int disc(int a, int p, boolean cap) { return a * p; }
                }
                """;
        Map<String, MethodChange.Kind> d = diff(before, after);
        assertEquals(MethodChange.Kind.RENAMED, d.get("gen(Long)"));
        assertEquals(MethodChange.Kind.SIGNATURE_CHANGED, d.get("disc(int,int)"));
        assertEquals(MethodChange.Kind.REMOVED, d.get("gone()"));
        assertTrue(d.size() == 3, d.toString());
    }

    @Test
    void returnTypeChangeIsFlaggedAsDeclarationChange() {
        Snapshot b = snap("base", P, "package a; public class A { public int f() { return 1; } }");
        Snapshot a = snap("theirs", P, "package a; public class A { public long f() { return 1; } }");
        ChangeSet cs = ChangeSet.compute("theirs", CodeIndex.build(b, null, false), CodeIndex.build(a, null, false));
        assertEquals(1, cs.changes.size());
        assertTrue(cs.changes.get(0).declarationChanged);
    }
}
