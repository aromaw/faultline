package com.branchdiff.gui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/**
 * The bundled Maple Mono NF CN (regular and bold; SIL Open Font License, {@code fonts/OFL.txt}): a monospace Nerd
 * Font with Chinese glyphs, the window's font unless View › Nerd Font is switched off. The Chinese glyphs have to be
 * in the font itself: Java on macOS does not fall back to other fonts for a font loaded from the jar, so the
 * JetBrains Mono Nerd Font showed Chinese commit messages as boxes there. Fonts are still made through
 * {@link javax.swing.text.StyleContext}, which adds the platform's fallback fonts where it can.
 */
final class NerdFont {
    /** the family name inside the font files */
    static final String FAMILY = "Maple Mono NF CN";
    private static final String[] FILES = {"MapleMono-NF-CN-Regular.ttf", "MapleMono-NF-CN-Bold.ttf"};
    private static Boolean registered;
    /** whether the window uses it (set by {@link MainFrame#applyLaf} from the preference) */
    static volatile boolean enabled;

    private NerdFont() {
    }

    /** registers the bundled faces once; false when they cannot be read (then the theme's own font is used) */
    static synchronized boolean register() {
        if (registered != null) return registered;
        boolean ok = true;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String f : FILES) {
            try (InputStream in = NerdFont.class.getResourceAsStream("fonts/" + f)) {
                if (in == null) {
                    ok = false;
                    continue;
                }
                ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
            } catch (Exception e) {
                ok = false;
            }
        }
        registered = ok;
        return ok;
    }

    /** the Nerd Font with fallback fonts for characters it lacks */
    static Font font(int style, int size) {
        return javax.swing.text.StyleContext.getDefaultStyleContext().getFont(FAMILY, style, size);
    }
}
