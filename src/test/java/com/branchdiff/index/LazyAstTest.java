package com.branchdiff.index;

import com.branchdiff.model.Snapshot;
import com.branchdiff.util.Ast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index keeps summaries only; ASTs are parsed on demand and may be dropped and parsed again. */
class LazyAstTest {
    @AfterEach
    void restore() {
        AstCache.setBudget(-1);
        AstCache.clear();
    }

    private static CodeIndex index() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/a/Foo.java", String.join("\n",
                "package a;",
                "import java.util.List;",
                "import static a.Bar.LIMIT;",
                "@org.springframework.stereotype.Service",
                "public class Foo {",
                "    @org.springframework.beans.factory.annotation.Value(\"${foo.size:3}\")",
                "    private int size;",
                "    private final Bar bar;",
                "    public Foo(Bar bar) { this.bar = bar; }",
                "    public int run(List<String> xs) { return bar.count(xs) + LIMIT; }",
                "    static class Inner { void x() {} }",
                "}"));
        files.put("src/main/java/a/Bar.java", String.join("\n",
                "package a;",
                "@org.springframework.stereotype.Component",
                "public class Bar {",
                "    public static final int LIMIT = 10;",
                "    public int count(java.util.List<String> xs) { return xs.size(); }",
                "}"));
        return CodeIndex.build(new Snapshot("ours", "x", "x", "", files), null, false);
    }

    @Test
    void summariesNeedNoAst() {
        CodeIndex idx = index();
        AstCache.clear();
        ClassInfo foo = idx.classes.get("a.Foo");
        assertEquals("java.util.List", idx.resolveType("List", foo));
        assertEquals(2, foo.imports.size());
        assertTrue(foo.imports.get(1).isStatic());
        assertTrue(foo.hasAnnotation("Service"));
        assertNotNull(idx.classes.get("a.Foo.Inner"));
        // spring facts are resolved once all classes are known
        assertEquals("a.Bar", idx.injectionPoints.get(0).typeFqn());
        assertEquals("foo.size", idx.valueRefs.get(0).key());
        assertEquals(2, idx.javaFileCount());
        assertTrue(AstCache.stats().startsWith("0 files"), AstCache.stats());
    }

    @Test
    void droppedAstsAreParsedAgainWithTheSameShape() {
        CodeIndex idx = index();
        AstCache.setBudget(1); // keep one file at a time
        MethodInfo run = idx.classes.get("a.Foo").methods.get("run(List)");
        var first = run.node();
        assertEquals("run", first.getNameAsString());
        idx.classes.get("a.Bar").node(); // evicts Foo.java
        var again = run.node();
        assertNotSame(first, again);
        assertTrue(Ast.same(first, again));
        assertEquals(Ast.normalized(first), Ast.normalized(again));
        assertEquals("Inner", idx.classes.get("a.Foo.Inner").node().getNameAsString());
        assertNull(idx.unit("src/main/java/a/Missing.java"));
        assertNotNull(idx.unit("src/main/java/a/Bar.java"));
    }

    @Test
    void mentionsMatchesWholeIdentifiersOnly() {
        assertTrue(CodeIndex.mentionsWord("a.count(xs)", "count"));
        assertTrue(CodeIndex.mentionsWord("count", "count"));
        assertFalse(CodeIndex.mentionsWord("a.countAll(xs)", "count"));
        assertFalse(CodeIndex.mentionsWord("a.recount(xs)", "count"));
        assertTrue(CodeIndex.mentionsWord("recount count", "count"));
        assertFalse(CodeIndex.mentionsWord("x_count", "count"));
        assertFalse(CodeIndex.mentionsWord(null, "count"));
        CodeIndex idx = index();
        assertTrue(idx.mentions("src/main/java/a/Foo.java", "LIMIT"));
        assertFalse(idx.mentions("src/main/java/a/Bar.java", "run"));
    }
}
