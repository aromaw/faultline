package com.branchdiff.index;

import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.Map;

public final class FieldInfo {
    public final ClassInfo owner;
    public final String name;
    /** type as written, generics kept */
    public final String typeRaw;
    /** erased simple type */
    public final String type;
    public final boolean isStatic;
    public final boolean isFinal;
    public final boolean hasInitializer;
    public final boolean enumConstant;
    public final Map<String, AnnotationExpr> annotations;
    public final int line;

    public FieldInfo(ClassInfo owner, String name, String typeRaw, String type, boolean isStatic, boolean isFinal,
                     boolean hasInitializer, boolean enumConstant, Map<String, AnnotationExpr> annotations, int line) {
        this.owner = owner;
        this.name = name;
        this.typeRaw = typeRaw;
        this.type = type;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
        this.hasInitializer = hasInitializer;
        this.enumConstant = enumConstant;
        this.annotations = annotations;
        this.line = line;
    }

    public boolean isConstant() {
        return enumConstant || (isStatic && isFinal);
    }

    public String location() {
        return owner.file + ":" + line;
    }
}
