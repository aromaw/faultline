package com.branchdiff.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassInfo {
    public enum Kind {CLASS, INTERFACE, ENUM, RECORD, ANNOTATION}

    public final String fqn;
    public final String simpleName;
    public final String pkg;
    public final Kind kind;
    public final boolean isAbstract;
    public final String file;
    public final int line;
    public final String outerFqn;
    /** the index this class belongs to (its AST is parsed on demand, see {@link AstCache}) */
    final CodeIndex index;
    /** position of the declaration in {@code cu.findAll(TypeDeclaration.class)} */
    final int typeIndex;
    /** the file's imports (shared by all classes of the file) */
    public final List<Import> imports;
    /**
     * simple name -> the annotation's normalized source; parsed again by {@link #annotation} (an annotation AST takes
     * about 30 times the heap of its text, and only a few annotations of a class are ever read)
     */
    final Map<String, String> annotations = new LinkedHashMap<>();
    /** supertypes as written (extends + implements) */
    public final List<String> superRaw = new ArrayList<>();
    /** supertypes resolved to FQN where possible (filled in second pass) */
    public final List<String> supertypes = new ArrayList<>();
    public final Set<String> typeParams = new LinkedHashSet<>();
    public final Map<String, FieldInfo> fields = new LinkedHashMap<>();
    public final Map<String, MethodInfo> methods = new LinkedHashMap<>();
    /**
     * false for classes of a sibling module (outside --service-dir): they satisfy / fail lookups, but are never
     * themselves a reported caller, implementation, injection point, etc.
     */
    public boolean inScope = true;
    /** spring facts read from the AST at parse time, resolved and dropped by {@link CodeIndex} once all classes are known */
    java.util.List<CodeIndex.SpringOp> springOps;

    public ClassInfo(String fqn, String simpleName, String pkg, Kind kind, boolean isAbstract, String file, int line,
                     String outerFqn, CodeIndex index, int typeIndex, List<Import> imports) {
        this.fqn = fqn;
        this.simpleName = simpleName;
        this.pkg = pkg;
        this.kind = kind;
        this.isAbstract = isAbstract;
        this.file = file;
        this.line = line;
        this.outerFqn = outerFqn;
        this.index = index;
        this.typeIndex = typeIndex;
        this.imports = imports;
    }

    /** an import declaration of the class's file */
    public record Import(String name, boolean isStatic, boolean isAsterisk) {
    }

    /**
     * The declaration's AST. It is parsed on demand and may be dropped and parsed again later (a new node), so do not
     * keep it across unrelated work and compare nodes by position ({@link com.branchdiff.util.Ast#same}), not identity.
     */
    public TypeDeclaration<?> node() {
        return index.entry(file).types().get(typeIndex);
    }

    /** the file's compilation unit (parsed on demand, see {@link #node()}) */
    public CompilationUnit cu() {
        return index.entry(file).cu();
    }

    public boolean isInterface() {
        return kind == Kind.INTERFACE;
    }

    /** simple names of the class's annotations, in source order */
    public Set<String> annotationNames() {
        return java.util.Collections.unmodifiableSet(annotations.keySet());
    }

    /** the class's annotation with this simple name (a new detached node on every call), null when absent */
    public AnnotationExpr annotation(String simple) {
        String src = annotations.get(simple);
        if (src == null) return null;
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parseAnnotation(src).getResult().orElseThrow(() -> new IllegalStateException("cannot parse " + src));
    }

    /** normalized class header (annotations, modifiers, kind, name, extends / implements), printed from the AST */
    public String normalizedHeader() {
        return FileSummaries.header(node(), kind, superRaw);
    }

    public boolean hasAnnotation(String... simple) {
        for (String s : simple) if (annotations.containsKey(s)) return true;
        return false;
    }

    public List<MethodInfo> methodsNamed(String name) {
        List<MethodInfo> res = new ArrayList<>();
        for (MethodInfo m : methods.values()) if (m.name.equals(name)) res.add(m);
        return res;
    }

    public List<MethodInfo> constructors() {
        List<MethodInfo> res = new ArrayList<>();
        for (MethodInfo m : methods.values()) if (m.constructor) res.add(m);
        return res;
    }

    public String location() {
        return file + ":" + line;
    }
}
