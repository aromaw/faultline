package com.branchdiff.index;

import java.util.Set;

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
    /** simple names of the field's annotations */
    public final Set<String> annotations;
    public final int line;
    /** normalized initializer (enum constants: arguments and class body), null when there is none */
    public String initializer;

    public FieldInfo(ClassInfo owner, String name, String typeRaw, String type, boolean isStatic, boolean isFinal,
                     boolean hasInitializer, boolean enumConstant, Set<String> annotations, int line) {
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
