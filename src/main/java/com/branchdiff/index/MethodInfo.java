package com.branchdiff.index;

import com.github.javaparser.ast.body.CallableDeclaration;

import java.util.List;
import java.util.Set;

/** One method or constructor, keyed by declaring class FQN + erased signature (never by line number). */
public final class MethodInfo {
    public final ClassInfo owner;
    public final String name;
    public final List<String> params;
    public final String returnType;
    public final boolean constructor;
    public final boolean isStatic;
    public final boolean isAbstract;
    public final boolean isDefault;
    public final Set<String> annotations;
    public final int line;
    public final int endLine;
    /** normalized full declaration (annotations, modifiers, signature, body) without comments */
    public final String normalizedDecl;
    /** normalized body only (used for rename detection) */
    public final String normalizedBody;
    public final CallableDeclaration<?> node;

    public MethodInfo(ClassInfo owner, String name, List<String> params, String returnType, boolean constructor,
                      boolean isStatic, boolean isAbstract, boolean isDefault, Set<String> annotations, int line,
                      int endLine, String normalizedDecl, String normalizedBody, CallableDeclaration<?> node) {
        this.owner = owner;
        this.name = name;
        this.params = params;
        this.returnType = returnType;
        this.constructor = constructor;
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;
        this.isDefault = isDefault;
        this.annotations = annotations;
        this.line = line;
        this.endLine = endLine;
        this.normalizedDecl = normalizedDecl;
        this.normalizedBody = normalizedBody;
        this.node = node;
    }

    public static String key(String name, List<String> params) {
        return name + "(" + String.join(",", params) + ")";
    }

    public String key() {
        return key(name, params);
    }

    /** class FQN + '#' + signature, the stable identity used across versions */
    public String id() {
        return owner.fqn + "#" + key();
    }

    public String display() {
        return owner.fqn + "#" + key() + (constructor ? "" : " : " + returnType);
    }

    public String location() {
        return owner.file + ":" + line;
    }
}
