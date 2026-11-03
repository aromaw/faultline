package com.branchdiff.gui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only RSyntaxTextArea code views, themed like IntelliJ IDEA (light) or Darcula. */
final class CodeViews {
    private static final List<WeakReference<RSyntaxTextArea>> AREAS = new ArrayList<>();
    private static Font font;
    static boolean dark;

    private CodeViews() {
    }

    static RSyntaxTextArea area() {
        RSyntaxTextArea a = new RSyntaxTextArea();
        a.setEditable(false);
        a.setCodeFoldingEnabled(false);
        a.setHighlightCurrentLine(false);
        a.setAntiAliasingEnabled(true);
        a.setAnimateBracketMatching(false);
        a.setBracketMatchingEnabled(false);
        a.setMarkOccurrences(false);
        a.setTabSize(4);
        a.getCaret().setVisible(false);
        AREAS.add(new WeakReference<>(a));
        applyTheme(a);
        return a;
    }

    static RTextScrollPane scroll(RSyntaxTextArea a) {
        RTextScrollPane sp = new RTextScrollPane(a, true);
        sp.setFoldIndicatorEnabled(false);
        sp.setBorder(null);
        return sp;
    }

    static String syntaxFor(String path) {
        if (path == null) return SyntaxConstants.SYNTAX_STYLE_NONE;
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (p.endsWith(".xml") || p.endsWith(".xsd") || p.endsWith(".pom")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (p.endsWith(".yml") || p.endsWith(".yaml")) return SyntaxConstants.SYNTAX_STYLE_YAML;
        if (p.endsWith(".properties")) return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        if (p.endsWith(".sql")) return SyntaxConstants.SYNTAX_STYLE_SQL;
        if (p.endsWith(".js") || p.endsWith(".ts") || p.endsWith(".mjs")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (p.endsWith(".vue") || p.endsWith(".html") || p.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (p.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (p.endsWith(".css")) return SyntaxConstants.SYNTAX_STYLE_CSS;
        if (p.endsWith(".kt") || p.endsWith(".kts")) return SyntaxConstants.SYNTAX_STYLE_KOTLIN;
        if (p.endsWith(".gradle") || p.endsWith(".groovy")) return SyntaxConstants.SYNTAX_STYLE_GROOVY;
        if (p.endsWith(".md")) return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    static void retheme() {
        for (WeakReference<RSyntaxTextArea> r : new ArrayList<>(AREAS)) {
            RSyntaxTextArea a = r.get();
            if (a == null) AREAS.remove(r);
            else applyTheme(a);
        }
    }

    private static void applyTheme(RSyntaxTextArea a) {
        String res = dark ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml" : "/org/fife/ui/rsyntaxtextarea/themes/idea.xml";
        try (InputStream in = CodeViews.class.getResourceAsStream(res)) {
            if (in != null) Theme.load(in, font()).apply(a);
        } catch (IOException ignored) {
            // keep the default colors
        }
        if (dark) a.setBackground(new Color(0x2B2B2B));
        a.setFont(font());
    }

    static Font font() {
        if (font == null) {
            // macOS falls back to a CJK font per glyph for physical fonts; elsewhere only the logical "Monospaced"
            // font does, so it is used there (commit messages and comments are often Chinese)
            boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
            for (String f : mac ? new String[] {"JetBrains Mono", "Menlo", "SF Mono"} : new String[0]) {
                Font c = new Font(f, Font.PLAIN, 13);
                if (c.getFamily().equals(f)) {
                    font = c;
                    break;
                }
            }
            if (font == null) font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }
        return font;
    }

    // IntelliJ IDEA diff / merge colors (light, Darcula)
    static Color conflict() {
        return dark ? new Color(0x6E3D3A) : new Color(0xFFD9D2);
    }

    static Color changed() {
        return dark ? new Color(0x34465A) : new Color(0xDCE6FA);
    }

    static Color inserted() {
        return dark ? new Color(0x36513A) : new Color(0xD7EFD2);
    }

    static Color deleted() {
        return dark ? new Color(0x4A4D50) : new Color(0xE4E4E4);
    }

    static Color focus() {
        return dark ? new Color(0x5A4A1E) : new Color(0xFFF3B0);
    }
}
