package com.branchdiff.diff;

import com.branchdiff.index.MethodInfo;

/** A method-level change between the merge-base and one side. Identity = class FQN + signature. */
public final class MethodChange {
    public enum Kind {ADDED, REMOVED, MODIFIED, SIGNATURE_CHANGED, RENAMED}

    public final Kind kind;
    public final String classFqn;
    /** version in the merge-base (null for ADDED) */
    public final MethodInfo oldMethod;
    /** version on the side (null for REMOVED) */
    public final MethodInfo newMethod;
    /** return type / static / abstract changed although parameter list is the same */
    public final boolean declarationChanged;
    public final boolean classAdded;
    public final boolean classRemoved;

    public MethodChange(Kind kind, String classFqn, MethodInfo oldMethod, MethodInfo newMethod,
                        boolean declarationChanged, boolean classAdded, boolean classRemoved) {
        this.kind = kind;
        this.classFqn = classFqn;
        this.oldMethod = oldMethod;
        this.newMethod = newMethod;
        this.declarationChanged = declarationChanged;
        this.classAdded = classAdded;
        this.classRemoved = classRemoved;
    }

    public String oldId() {
        return oldMethod == null ? null : oldMethod.id();
    }

    public String newId() {
        return newMethod == null ? null : newMethod.id();
    }

    /** signature as shown in reports */
    public String describe() {
        return switch (kind) {
            case ADDED -> newMethod.display();
            case REMOVED -> oldMethod.display();
            case MODIFIED -> newMethod.display() + (declarationChanged ? "  (return type/modifiers changed: was "
                    + oldMethod.returnType + ")" : "");
            case SIGNATURE_CHANGED, RENAMED -> oldMethod.owner.fqn + "#" + oldMethod.key() + "  ->  " + newMethod.key();
        };
    }
}
