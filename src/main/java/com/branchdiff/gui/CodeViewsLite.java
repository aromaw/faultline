package com.branchdiff.gui;

import java.awt.Font;

/** fonts that do not need RSyntaxTextArea (kept apart so the start-up path does not load it) */
final class CodeViewsLite {
    private static Font mono;

    private CodeViewsLite() {
    }

    private static boolean monoNerd;

    static Font mono() {
        if (mono != null && monoNerd != NerdFont.enabled) mono = null; // View › Nerd Font was switched
        monoNerd = NerdFont.enabled;
        if (mono == null && NerdFont.enabled) mono = NerdFont.font(Font.PLAIN, 12);
        if (mono == null) {
            // macOS falls back to a CJK font per glyph for physical fonts; elsewhere only the logical "Monospaced"
            // font does, so it is used there (commit messages and comments are often Chinese)
            boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
            for (String f : mac ? new String[] {"JetBrains Mono", "Menlo", "SF Mono"} : new String[0]) {
                Font c = new Font(f, Font.PLAIN, 12);
                if (c.getFamily().equals(f)) {
                    mono = c;
                    break;
                }
            }
            if (mono == null) mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        }
        return mono;
    }
}
