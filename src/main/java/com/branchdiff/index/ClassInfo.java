package com.branchdiff.index;

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
    public final TypeDeclaration<?> node;
    public final CompilationUnit cu;
    public final Map<String, AnnotationExpr> annotations = new LinkedHashMap<>();
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
    /** normalized class header (annotations+modifiers+extends/implements) */
    public String normalizedHeader = "";

    public ClassInfo(String fqn, String simpleName, String pkg, Kind kind, boolean isAbstract, String file, int line,
                     String outerFqn, TypeDeclaration<?> node, CompilationUnit cu) {
        this.fqn = fqn;
        this.simpleName = simpleName;
        this.pkg = pkg;
        this.kind = kind;
        this.isAbstract = isAbstract;
        this.file = file;
        this.line = line;
        this.outerFqn = outerFqn;
        this.node = node;
        this.cu = cu;
    }

    public boolean isInterface() {
        return kind == Kind.INTERFACE;
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
