package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.util.function.Supplier;

/** Read-only source view for a clicked reference: a file of one version at a line, or a commit's diff of a file. */
final class SourceView extends JPanel {
    private final RSyntaxTextArea area = CodeViews.area();
    private final JLabel header = new JLabel("Select an item with a location to see its code");
    private AnalysisJob.Result result;
    private int generation;

    SourceView() {
        super(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(header, BorderLayout.NORTH);
        add(CodeViews.scroll(area), BorderLayout.CENTER);
    }

    void setResult(AnalysisJob.Result r) {
        this.result = r;
        this.last = null;
        header.setText("Select an item with a location to see its code");
        area.setText("");
    }

    void open(Loc loc) {
        if (loc == null || result == null) return;
        String sha = switch (loc.side()) {
            case "base" -> result.baseCommit();
            case "ours" -> result.oursCommit();
            default -> result.theirsCommit();
        };
        String title = loc.side() + " " + shortSha(sha) + "  " + loc.path() + (loc.line() > 0 ? ":" + loc.line() : "");
        load(title, CodeViews.syntaxFor(loc.path()), () -> {
            String t = result.file(loc.side(), loc.path());
            return t == null ? "(" + loc.path() + " does not exist in " + loc.side() + ")" : t;
        }, loc.line(), false);
    }

    void showCommit(String sha, String path) {
        if (result == null || sha == null) return;
        GitRunner git = result.git();
        load("commit " + shortSha(sha) + (path != null ? "  " + path : ""), SyntaxConstants.SYNTAX_STYLE_NONE, () -> {
            try {
                return path != null
                        ? git.run("-c", "core.quotePath=false", "show", "--stat", "--patch", "--format=commit %H%nAuthor: %an%nDate:   %aI%n%n    %s%n", sha, "--", path)
                        : git.run("-c", "core.quotePath=false", "show", "--stat", "--format=commit %H%nAuthor: %an%nDate:   %aI%n%n%w(0,4,4)%B", sha);
            } catch (RuntimeException e) {
                return e.getMessage();
            }
        }, 0, true);
    }

    private Runnable last;

    /** re-applies the highlight colors after a theme change */
    void refreshTheme() {
        if (last != null) last.run();
    }

    private void load(String title, String syntax, Supplier<String> text, int line, boolean diff) {
        last = () -> load(title, syntax, text, line, diff);
        int gen = ++generation;
        header.setText(title + "  (loading)");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return text.get();
            }

            @Override
            protected void done() {
                if (gen != generation) return;
                String t;
                try {
                    t = get();
                } catch (Exception e) {
                    t = String.valueOf(e.getMessage());
                }
                header.setText(title);
                area.setSyntaxEditingStyle(syntax);
                area.setText(t);
                area.removeAllLineHighlights();
                if (diff) {
                    String[] ls = t.split("\n", -1);
                    for (int i = 0; i < ls.length; i++) {
                        if (ls[i].startsWith("+") && !ls[i].startsWith("+++")) hl(i, CodeViews.inserted());
                        else if (ls[i].startsWith("-") && !ls[i].startsWith("---")) hl(i, CodeViews.conflict());
                        else if (ls[i].startsWith("@@")) hl(i, CodeViews.changed());
                    }
                }
                if (line > 0) hl(line - 1, CodeViews.focus());
                SwingUtilities.invokeLater(() -> scrollTo(line));
            }
        }.execute();
    }

    private void hl(int line0, java.awt.Color c) {
        if (line0 < 0 || line0 >= area.getLineCount()) return;
        try {
            area.addLineHighlight(line0, c);
        } catch (BadLocationException ignored) {
            // out of range
        }
    }

    private void scrollTo(int line) {
        try {
            int l = Math.max(0, Math.min(area.getLineCount() - 1, line - 1));
            area.setCaretPosition(area.getLineStartOffset(l));
            java.awt.geom.Rectangle2D r = area.modelToView2D(area.getLineStartOffset(l));
            if (r != null) {
                int h = area.getVisibleRect().height;
                area.scrollRectToVisible(new Rectangle(0, Math.max(0, (int) r.getY() - h / 3), 1, h));
            }
        } catch (BadLocationException ignored) {
            // empty text
        }
    }

    private static String shortSha(String s) {
        return s == null ? "" : s.substring(0, Math.min(10, s.length()));
    }
}
