package com.branchdiff.index;

import com.branchdiff.util.Ast;
import com.branchdiff.util.Fingerprint;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

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
    /** whether the last parameter is variadic ({@code T...}; its param type is then written {@code T[]}) */
    public final boolean varargs;
    public final Set<String> annotations;
    public final int line;
    public final int endLine;
    /** fingerprint of the normalized full declaration (annotations, modifiers, signature, body) without comments */
    public final Fingerprint declPrint;
    /** fingerprint of the normalized body only (used for rename detection) */
    public final Fingerprint bodyPrint;
    /** the texts of a member without a declaration (record accessor); null otherwise: printed again from the AST */
    private final String declText, bodyText;
    /** position among the declaring type's members; -1 for members without a declaration (record accessors) */
    final int memberIndex;

    public MethodInfo(ClassInfo owner, String name, List<String> params, String returnType, boolean constructor,
                      boolean isStatic, boolean isAbstract, boolean isDefault, boolean varargs, Set<String> annotations,
                      int line, int endLine, Fingerprint declPrint, Fingerprint bodyPrint, String declText, String bodyText,
                      int memberIndex) {
        this.owner = owner;
        this.name = name;
        this.params = params;
        this.returnType = returnType;
        this.constructor = constructor;
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;
        this.isDefault = isDefault;
        this.varargs = varargs;
        this.annotations = annotations;
        this.line = line;
        this.endLine = endLine;
        this.declPrint = declPrint;
        this.bodyPrint = bodyPrint;
        this.declText = memberIndex < 0 ? declText : null;
        this.bodyText = memberIndex < 0 ? bodyText : null;
        this.memberIndex = memberIndex;
    }

    /** the declaration's AST (parsed on demand, see {@link ClassInfo#node()}); null for record accessors */
    public CallableDeclaration<?> node() {
        return memberIndex < 0 ? null : (CallableDeclaration<?>) owner.node().getMember(memberIndex);
    }

    /** normalized full declaration (annotations, modifiers, signature, body) without comments */
    public String normalizedDecl() {
        return declText != null ? declText : Ast.normalized(node());
    }

    /** normalized body only ("" for abstract methods) */
    public String normalizedBody() {
        return bodyText != null ? bodyText : body(node());
    }

    /** the normalized body of a declaration, as fingerprinted by the index */
    public static String body(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration md) return md.getBody().map(Ast::normalized).orElse("");
        if (cd instanceof ConstructorDeclaration c) return Ast.normalized(c.getBody());
        return "";
    }

    /** whether the normalized declarations are the same text */
    public boolean sameDecl(MethodInfo other) {
        return declPrint.equals(other.declPrint);
    }

    /** whether the normalized bodies are the same text */
    public boolean sameBody(MethodInfo other) {
        return bodyPrint.equals(other.bodyPrint);
    }

    /** whether the member has a declaration in the source (false for record accessors) */
    public boolean hasNode() {
        return memberIndex >= 0;
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
