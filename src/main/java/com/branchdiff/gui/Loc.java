package com.branchdiff.gui;

/** A clickable file reference: version ("ours" / "theirs" / "base"), repo-relative path, 1-based line (0 = none). */
record Loc(String side, String path, int line) {
    /** parses {@code path:line} (or a bare path); null for null / blank */
    static Loc parse(String side, String location) {
        if (location == null || location.isBlank()) return null;
        String s = location.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp); // "path:12 (base)"
        int c = s.lastIndexOf(':');
        if (c > 0) {
            try {
                return new Loc(side, s.substring(0, c), Integer.parseInt(s.substring(c + 1)));
            } catch (NumberFormatException ignored) {
                // not a line number
            }
        }
        return new Loc(side, s, 0);
    }

    @Override
    public String toString() {
        return side + ": " + path + (line > 0 ? ":" + line : "");
    }
}
