package com.branchdiff.analysis;

import com.branchdiff.index.ClassInfo;
import com.branchdiff.index.CodeIndex;
import com.branchdiff.index.FieldInfo;
import com.branchdiff.util.Names;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Best-effort, solver-free inference of the static type of a call scope (used when the symbol solver fails). */
public final class Scopes {
    private final CodeIndex idx;
    private final ClassInfo ctx;
    private final Set<String> typeVars;
    /** local variable / parameter name -> declared type ("var" / lambda params map to null) */
    private final Map<String, String> locals = new LinkedHashMap<>();

    public Scopes(CodeIndex idx, ClassInfo ctx, Node callable, Set<String> typeVars) {
        this.idx = idx;
        this.ctx = ctx;
        this.typeVars = typeVars;
        if (callable != null) {
            for (Parameter p : callable.findAll(Parameter.class)) locals.put(p.getNameAsString(), p.getType().asString());
            for (VariableDeclarator v : callable.findAll(VariableDeclarator.class)) {
                String t = v.getType().asString();
                locals.put(v.getNameAsString(), t.equals("var") ? null : t);
            }
            for (LambdaExpr l : callable.findAll(LambdaExpr.class)) {
                for (Parameter p : l.getParameters()) {
                    if (p.getType().isUnknownType()) locals.put(p.getNameAsString(), null);
                }
            }
        }
    }

    /** the imports of the context class's file */
    public List<ClassInfo.Import> imports() {
        return ctx.imports;
    }

    public boolean isLocal(String name) {
        return locals.containsKey(name);
    }

    /** Field visible from ctx by simple name (own, inherited, enclosing classes). */
    public FieldInfo visibleField(String name) {
        ClassInfo c = ctx;
        while (c != null) {
            FieldInfo f = idx.findField(c.fqn, name);
            if (f != null) return f;
            c = c.outerFqn == null ? null : idx.classes.get(c.outerFqn);
        }
        return null;
    }

    /**
     * Possible FQNs of the scope expression's type. Empty list = unknown. For a missing scope, the enclosing
     * classes are returned.
     */
    public List<String> scopeTypes(Expression scope) {
        List<String> res = new ArrayList<>();
        if (scope == null) {
            ClassInfo c = ctx;
            while (c != null) {
                res.add(c.fqn);
                c = c.outerFqn == null ? null : idx.classes.get(c.outerFqn);
            }
            return res;
        }
        if (scope instanceof ThisExpr) {
            res.add(ctx.fqn);
        } else if (scope instanceof SuperExpr) {
            res.addAll(ctx.supertypes);
        } else if (scope instanceof NameExpr n) {
            String name = n.getNameAsString();
            if (locals.containsKey(name)) {
                String t = locals.get(name);
                if (t != null) add(res, idx.resolveType(t, ctx, typeVars));
            } else {
                FieldInfo f = visibleField(name);
                if (f != null) add(res, idx.resolveType(f.typeRaw, f.owner, typeVars));
                else add(res, idx.resolveType(name, ctx, typeVars)); // static call on a type
            }
        } else if (scope instanceof FieldAccessExpr fa) {
            if (fa.getScope() instanceof ThisExpr) {
                FieldInfo f = visibleField(fa.getNameAsString());
                if (f != null) add(res, idx.resolveType(f.typeRaw, f.owner, typeVars));
            } else {
                String asType = idx.resolveType(fa.toString(), ctx, typeVars);
                if (asType != null && idx.isProjectType(asType)) res.add(asType);
            }
        } else if (scope instanceof TypeExpr te) {
            add(res, idx.resolveType(te.getType().asString(), ctx, typeVars));
        }
        if (res.isEmpty() && idx.symbolSolverEnabled) {
            try {
                String d = scope.calculateResolvedType().describe();
                res.add(Names.eraseGenerics(d));
            } catch (Throwable ignored) {
                // unknown
            }
        }
        return res;
    }

    private static void add(List<String> l, String s) {
        if (s != null) l.add(s);
    }

    public static Set<String> typeVars(ClassInfo c, CallableDeclaration<?> cd) {
        Set<String> s = new java.util.LinkedHashSet<>(CodeIndex.methodTypeVars(cd));
        if (c != null) s.addAll(c.typeParams);
        return s;
    }
}
