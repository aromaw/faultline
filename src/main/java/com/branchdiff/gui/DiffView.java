package com.branchdiff.gui;

import com.branchdiff.conflict.ConflictModel.Range;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IDEA-like merge view of one conflict hunk: ours | base | theirs (or ours | theirs), whole files, the hunk in the
 * conflict color, lines each side changed relative to base in the change / insert colors, scrolled to the hunk and
 * scrolling together relative to it.
 */
final class DiffView extends JPanel {
    private final Pane ours = new Pane("Ours", "tenant", Ui.BLUE), base = new Pane("Base", "pick parent", null),
            theirs = new Pane("Theirs", "the pick", new java.awt.Color(0x8B5CF6));
    private final JPanel columns = new JPanel(new GridLayout(1, 3, 1, 0));
    private final JToggleButton threeWay = new JToggleButton("3-way: Ours | Base | Theirs", true);
    private final JToggleButton twoWay = new JToggleButton("2-way: Ours | Theirs");
    private final JCheckBox sync = new JCheckBox("Sync scroll", true);
    private final JLabel title = new JLabel(" ");
    private String[] texts = new String[3];
    private Range[] ranges = new Range[3];
    private String path;
    private boolean adjusting;

    private final class Pane {
        final RSyntaxTextArea area = CodeViews.area();
        final RTextScrollPane scroll = CodeViews.scroll(area);
        final JLabel header = new JLabel();
        final JPanel panel = new JPanel(new BorderLayout());
        final String name, role;
        final java.awt.Color color;
        Range range;
        boolean absent;

        Pane(String name, String role, java.awt.Color color) {
            this.name = name;
            this.role = role;
            this.color = color;
            styleHeader();
            setHeader(null);
            panel.add(header, BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            scroll.getVerticalScrollBar().addAdjustmentListener(e -> follow(this));
        }

        void styleHeader() {
            java.awt.Color line = color != null ? color : UIManager.getColor("Component.borderColor");
            header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, line),
                    BorderFactory.createEmptyBorder(4, 8, 3, 8)));
            header.setForeground(UIManager.getColor("Label.foreground"));
        }

        /** "Ours  tenant · L12-20" with the side name bold and the rest muted */
        void setHeader(String extra) {
            String gray = Ui.hex(Ui.muted());
            header.setText("<html><b>" + name + "</b>&nbsp;&nbsp;<span style='color:" + gray + "'>" + role
                    + (extra == null || extra.isEmpty() ? "" : " \u00B7 " + ResultsPanel.esc(extra)) + "</span></html>");
        }

        int anchorPx() {
            if (range == null) return 0;
            int line = Math.max(0, Math.min(area.getLineCount() - 1, range.start() - 1));
            try {
                java.awt.geom.Rectangle2D r = area.modelToView2D(area.getLineStartOffset(line));
                return r == null ? 0 : (int) r.getY();
            } catch (BadLocationException e) {
                return 0;
            }
        }
    }

    DiffView() {
        super(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        ButtonGroup g = new ButtonGroup();
        g.add(threeWay);
        g.add(twoWay);
        for (JToggleButton b : List.of(threeWay, twoWay)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.addActionListener(e -> layoutColumns());
            bar.add(b);
        }
        threeWay.setToolTipText("Show the tenant (ours), the pick's parent (base) and the pick (theirs) side by side");
        twoWay.setToolTipText("Show only the tenant (ours) and the pick (theirs)");
        sync.setToolTipText("Scroll the panes together, aligned at the conflict");
        bar.add(sync);
        title.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        bar.add(title);
        add(bar, BorderLayout.NORTH);
        add(columns, BorderLayout.CENTER);
        layoutColumns();
    }

    private void layoutColumns() {
        columns.removeAll();
        boolean three = threeWay.isSelected();
        columns.setLayout(new GridLayout(1, three ? 3 : 2, 1, 0));
        columns.add(ours.panel);
        if (three) columns.add(base.panel);
        columns.add(theirs.panel);
        columns.revalidate();
        columns.repaint();
        if (path != null) highlight();
    }

    /** texts / ranges: [ours, base, theirs]; a null text means the file does not exist on that side */
    void show(String path, String headline, String[] texts, Range[] ranges) {
        this.path = path;
        this.texts = texts;
        this.ranges = ranges;
        title.setText(headline);
        Pane[] panes = {ours, base, theirs};
        for (int i = 0; i < 3; i++) {
            Pane p = panes[i];
            p.absent = texts[i] == null;
            p.range = ranges[i];
            p.area.setSyntaxEditingStyle(p.absent ? "text/plain" : CodeViews.syntaxFor(path));
            p.area.setText(p.absent ? "(file absent on this side)" : texts[i]);
            p.setHeader(p.absent ? "file absent" : p.range == null ? "" : ResultsPanel.lines(p.range));
        }
        highlight();
    }

    void clear() {
        path = null;
        title.setText(" ");
        for (Pane p : new Pane[] {ours, base, theirs}) {
            p.area.setText("");
            p.setHeader(null);
            p.area.removeAllLineHighlights();
        }
    }

    /** re-applies the colors (after a theme change) */
    void refresh() {
        for (Pane p : new Pane[] {ours, base, theirs}) {
            p.styleHeader();
            p.setHeader(path == null ? null : p.absent ? "file absent" : p.range == null ? "" : ResultsPanel.lines(p.range));
        }
        if (path != null) highlight();
    }

    private void highlight() {
        Pane[] panes = {ours, base, theirs};
        for (Pane p : panes) p.area.removeAllLineHighlights();
        boolean three = threeWay.isSelected();
        if (three) {
            mark(base, ours, texts[1], texts[0]);
            mark(base, theirs, texts[1], texts[2]);
        } else {
            mark(ours, theirs, texts[0], texts[2]);
        }
        for (Pane p : panes) {
            if (p.absent || p.range == null) continue;
            if (p.range.empty()) {
                lineHighlight(p.area, p.range.start() - 1, CodeViews.focus());
            } else {
                for (int l = p.range.start(); l <= p.range.end(); l++) lineHighlight(p.area, l - 1, CodeViews.conflict());
            }
        }
        SwingUtilities.invokeLater(this::scrollToHunk);
    }

    /** colors the lines {@code right} changed relative to {@code left} (deleted lines are shaded on the left) */
    private void mark(Pane left, Pane right, String l, String r) {
        if (l == null || r == null) return;
        List<String> a = Arrays.asList(l.split("\n", -1)), b = Arrays.asList(r.split("\n", -1));
        if (a.size() > 20000 || b.size() > 20000) return; // keep huge generated files cheap
        for (AbstractDelta<String> d : DiffUtils.diff(a, b).getDeltas()) {
            int ls = d.getSource().getPosition(), ln = d.getSource().size();
            int rs = d.getTarget().getPosition(), rn = d.getTarget().size();
            Color rc = d.getType() == DeltaType.INSERT ? CodeViews.inserted() : CodeViews.changed();
            Color lc = d.getType() == DeltaType.DELETE ? CodeViews.deleted() : CodeViews.changed();
            for (int i = 0; i < rn; i++) lineHighlight(right.area, rs + i, rc);
            for (int i = 0; i < ln; i++) lineHighlight(left.area, ls + i, lc);
        }
    }

    private static void lineHighlight(RSyntaxTextArea a, int line0, Color c) {
        if (line0 < 0 || line0 >= a.getLineCount()) return;
        try {
            a.addLineHighlight(line0, c);
        } catch (BadLocationException ignored) {
            // line vanished
        }
    }

    private void scrollToHunk() {
        adjusting = true;
        try {
            for (Pane p : new Pane[] {ours, base, theirs}) {
                int target = Math.max(0, p.anchorPx() - p.scroll.getViewport().getHeight() / 4);
                p.scroll.getVerticalScrollBar().setValue(target);
            }
        } finally {
            adjusting = false;
        }
    }

    private void follow(Pane src) {
        if (adjusting || !sync.isSelected() || path == null) return;
        adjusting = true;
        try {
            int offset = src.scroll.getVerticalScrollBar().getValue() - src.anchorPx();
            for (Pane p : new Pane[] {ours, base, theirs}) {
                if (p == src) continue;
                JScrollBar sb = p.scroll.getVerticalScrollBar();
                sb.setValue(Math.max(0, p.anchorPx() + offset));
            }
        } finally {
            adjusting = false;
        }
    }

    List<JComponent> areas() {
        return new ArrayList<>(List.of(ours.area, base.area, theirs.area));
    }
}
