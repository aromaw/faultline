package com.branchdiff.gui;

/** A tree / list entry of the result tabs; {@code loc} (may be null) is opened in the source view when selected. */
record Item(String text, Loc loc, String badge) {
    Item(String text, Loc loc) {
        this(text, loc, null);
    }

    @Override
    public String toString() {
        return text;
    }
}
