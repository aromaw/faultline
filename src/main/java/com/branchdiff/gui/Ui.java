package com.branchdiff.gui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Small shared look helpers: 16 px line icons drawn with Java2D in the theme's colors (no image files, sharp on HiDPI),
 * severity badges and text styles. Everything follows the current FlatLaf theme, light or dark.
 */
final class Ui {
    private Ui() {
    }

    static final Color RED = new Color(0xE5493A), ORANGE = new Color(0xE0912F), GREEN = new Color(0x4FA45E),
            BLUE = new Color(0x3592C4), ACCENT = new Color(0x3D7BF7), FAULT = new Color(0xFF9F43);

    static boolean dark() {
        Color bg = UIManager.getColor("Panel.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) < 3 * 128;
    }

    static Color fg() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : Color.DARK_GRAY;
    }

    static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    /** FlatLaf typography "large", "small", "mini" (relative to the system font); for headings use {@link #heading} */
    static <T extends JComponent> T style(T c, String styleClass) {
        c.putClientProperty("FlatLaf.styleClass", styleClass);
        return c;
    }

    static JLabel label(String text, String styleClass) {
        return style(new JLabel(text), styleClass);
    }

    /**
     * a bold heading {@code delta} points above the label font. (FlatLaf's h1..h4 style classes look up a semibold
     * font family, which makes the JDK scan every installed font: ~150 ms of start-up on Linux.)
     */
    static JLabel heading(String text, float delta) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Font f = UIManager.getFont("Label.font");
                if (f != null) setFont(f.deriveFont(Font.BOLD, f.getSize2D() + delta));
            }
        };
        return l;
    }

    static JLabel muted(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(muted());
            }
        };
        l.setForeground(muted());
        return l;
    }

    static JComponent pad(JComponent c, int t, int l, int b, int r) {
        c.setBorder(BorderFactory.createEmptyBorder(t, l, b, r));
        return c;
    }

    static String hex(Color c) {
        if (c == null) return "#888888";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static Color severity(String s) {
        if (s == null) return null;
        return switch (s) {
            case "HIGH", "MISSING", "CHANGED" -> RED;
            case "MEDIUM", "DIFFERS", "UNKNOWN" -> ORANGE;
            case "LOW", "PRESENT", "IN_PICK" -> GREEN;
            case "HTTP", "SCHEDULED", "LISTENER", "STARTUP", "MAIN" -> BLUE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ badge

    /** a rounded "pill" with a short upper-case text, like IDEA's inspection / VCS labels */
    static final class Badge implements Icon {
        private final String text;
        private final Color color;
        private final int minWidth;

        Badge(String text, Color color) {
            this(text, color, 0);
        }

        Badge(String text, Color color, int minWidth) {
            this.text = text;
            this.color = color;
            this.minWidth = minWidth;
        }

        private Font font() {
            Font f = UIManager.getFont("Label.font");
            return (f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(Font.BOLD, f != null ? f.getSize2D() - 2.5f : 10f);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getIconWidth() - 4, h = getIconHeight();
            g2.setColor(dark() ? color.darker() : color);
            g2.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
            g2.setFont(font());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);
            g2.drawString(text, x + (w - fm.stringWidth(text)) / 2f, y + (h - fm.getHeight()) / 2f + fm.getAscent());
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            FontMetrics fm = new JLabel().getFontMetrics(font());
            return Math.max(minWidth, fm.stringWidth(text) + 12) + 4;
        }

        @Override
        public int getIconHeight() {
            return new JLabel().getFontMetrics(font()).getHeight() + 2;
        }
    }

    // ------------------------------------------------------------------ line icons

    enum Glyph { FOLDER, BRANCH, PLAY, STOP, EXPORT, THEME, RELOAD, CHECK, SEARCH, COMMIT, CLEAR, ARROW_DOWN }

    /** a 16 x 16 line icon in the label color ({@code color} null) or a fixed color */
    static Icon icon(Glyph glyph) {
        return icon(glyph, null);
    }

    static Icon icon(Glyph glyph, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color col = color != null ? color : (c != null && !c.isEnabled() ? muted() : fg());
                g2.setColor(col);
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2, glyph);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    private static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case FOLDER -> {
                Path2D p = new Path2D.Float();
                p.moveTo(1.5, 4);
                p.lineTo(6, 4);
                p.lineTo(7.5, 5.5);
                p.lineTo(14.5, 5.5);
                p.lineTo(14.5, 13);
                p.lineTo(1.5, 13);
                p.closePath();
                g.draw(p);
            }
            case BRANCH -> {
                g.drawOval(3, 1, 3, 3);
                g.drawOval(3, 12, 3, 3);
                g.drawOval(10, 4, 3, 3);
                g.drawLine(4, 4, 4, 12);
                Path2D p = new Path2D.Float();
                p.moveTo(11.5, 7.2);
                p.curveTo(11.5, 10, 5, 9, 4.6, 12);
                g.draw(p);
            }
            case PLAY -> {
                Path2D p = new Path2D.Float();
                p.moveTo(4, 2.5);
                p.lineTo(13.5, 8);
                p.lineTo(4, 13.5);
                p.closePath();
                g.fill(p);
            }
            case STOP -> g.fillRoundRect(3, 3, 10, 10, 3, 3);
            case EXPORT -> {
                g.drawLine(8, 2, 8, 10);
                g.drawLine(5, 5, 8, 2);
                g.drawLine(11, 5, 8, 2);
                Path2D p = new Path2D.Float();
                p.moveTo(3, 8);
                p.lineTo(3, 14);
                p.lineTo(13, 14);
                p.lineTo(13, 8);
                g.draw(p);
            }
            case THEME -> {
                g.drawOval(2, 2, 12, 12);
                Path2D p = new Path2D.Float();
                p.moveTo(8, 2);
                p.curveTo(15.8, 2, 15.8, 14, 8, 14);
                p.closePath();
                g.fill(p);
            }
            case RELOAD -> {
                g.drawArc(2, 2, 12, 12, 60, 270);
                Path2D p = new Path2D.Float();
                p.moveTo(10.5, 1);
                p.lineTo(11.5, 3.6);
                p.lineTo(8.8, 4.6);
                g.draw(p);
            }
            case CHECK -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 8.5);
                p.lineTo(6.5, 12);
                p.lineTo(13, 4.5);
                g.draw(p);
            }
            case SEARCH -> {
                g.drawOval(2, 2, 9, 9);
                g.drawLine(10, 10, 14, 14);
            }
            case COMMIT -> {
                g.drawOval(5, 5, 6, 6);
                g.drawLine(1, 8, 5, 8);
                g.drawLine(11, 8, 15, 8);
            }
            case CLEAR -> {
                g.drawLine(4, 4, 12, 12);
                g.drawLine(12, 4, 4, 12);
            }
            case ARROW_DOWN -> {
                Path2D p = new Path2D.Float();
                p.moveTo(4.5, 6.5);
                p.lineTo(8, 10);
                p.lineTo(11.5, 6.5);
                g.draw(p);
            }
        }
    }

    /** a numbered step marker: accent circle with the number, or a green check once the step is done */
    static final class StepIcon implements Icon {
        private final int n;
        boolean done;
        boolean active;

        StepIcon(int n) {
            this.n = n;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int s = getIconWidth();
            if (done) {
                g2.setColor(GREEN);
                g2.fillOval(x, y, s, s);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Float();
                p.moveTo(x + s * 0.28, y + s * 0.52);
                p.lineTo(x + s * 0.45, y + s * 0.69);
                p.lineTo(x + s * 0.74, y + s * 0.34);
                g2.draw(p);
            } else {
                g2.setColor(active ? ACCENT : muted());
                if (active) g2.fillOval(x, y, s, s);
                else {
                    g2.setStroke(new BasicStroke(1.3f));
                    g2.drawOval(x + 1, y + 1, s - 2, s - 2);
                }
                Font f = UIManager.getFont("Label.font");
                g2.setFont((f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(Font.BOLD, s * 0.6f));
                FontMetrics fm = g2.getFontMetrics();
                String t = String.valueOf(n);
                g2.setColor(active ? Color.WHITE : muted());
                g2.drawString(t, x + (s - fm.stringWidth(t)) / 2f, y + (s - fm.getHeight()) / 2f + fm.getAscent());
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }
    }
}
