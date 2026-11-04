package com.branchdiff.gui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/**
 * The bundled JetBrains Mono Nerd Font (regular and bold; SIL Open Font License, {@code fonts/OFL.txt}): the window's
 * font unless View › Nerd Font is switched off. It has no CJK glyphs, so every font is made through
 * {@link javax.swing.text.StyleContext}, which adds the platform's fallback fonts (Chinese commit messages and
 * comments still render).
 */
final class NerdFont {
    /** the family name inside the font files */
    static final String FAMILY = "JetBrainsMono NF";
    private static final String[] FILES = {"JetBrainsMonoNerdFont-Regular.ttf", "JetBrainsMonoNerdFont-Bold.ttf"};
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
