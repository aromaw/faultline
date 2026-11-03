package com.branchdiff.gui;

import com.branchdiff.gui.BranchFilter.Row;
import com.branchdiff.gui.GitQueries.Branch;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A branch selector like IDEA's branch popup: a button showing the branch; clicking it (or typing while it has focus)
 * opens a popup with a search field. Typing filters local and remote branches (case-insensitive substring, every word
 * must match), Up / Down move, Enter chooses, Escape closes.
 */
final class BranchPicker extends JButton {
    private List<Branch> branches = List.of();
    private String selected;
    private final String what;
    private final Consumer<String> onChoose;
    /** the open popup (automation and tests type into it) */
    JPopupMenu popup;
    JTextField search;
    JList<Row> list;

    BranchPicker(String what, Consumer<String> onChoose) {
        this.what = what;
        this.onChoose = onChoose;
        setIcon(Ui.icon(Ui.Glyph.BRANCH));
        setHorizontalAlignment(SwingConstants.LEFT);
        setHorizontalTextPosition(SwingConstants.RIGHT);
        setText("(no branch)");
        addActionListener(e -> open(""));
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '_' || c == '.') open(String.valueOf(c));
            }
        });
        refreshText();
    }

    void setBranches(List<Branch> bs) {
        branches = List.copyOf(bs);
        setEnabled(!branches.isEmpty());
    }

    void setSelected(String name) {
        selected = name;
        refreshText();
    }

    String selected() {
        return selected;
    }

    private void refreshText() {
        setText((selected != null ? selected : "Choose " + what) + "  \u25BE");
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(190, Math.min(320, d.width)), d.height);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    void open(String initial) {
        if (branches.isEmpty()) return;
        search = new JTextField(initial);
        search.putClientProperty("JTextField.placeholderText", "Search " + what + " (local and remote)");
        search.putClientProperty("JTextField.leadingIcon", Ui.icon(Ui.Glyph.SEARCH));
        search.putClientProperty("JTextField.showClearButton", true);
        DefaultListModel<Row> model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new Renderer());
        list.setVisibleRowCount(14);
        JLabel empty = Ui.muted("No branch matches");
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel center = new JPanel(new BorderLayout());
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        center.add(sp, BorderLayout.CENTER);

        Runnable refilter = () -> {
            List<Row> rows = BranchFilter.rows(branches, search.getText());
            model.clear();
            for (Row r : rows) model.addElement(r);
            center.removeAll();
            center.add(rows.isEmpty() ? empty : sp, BorderLayout.CENTER);
            center.revalidate();
            center.repaint();
            int i = BranchFilter.initialRow(rows, search.getText().isBlank() ? selected : null);
            if (i >= 0) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
            }
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter.run();
            }
        });
        bind(search, "DOWN", () -> move(1));
        bind(search, "UP", () -> move(-1));
        bind(search, "PAGE_DOWN", () -> move(10));
        bind(search, "PAGE_UP", () -> move(-10));
        bind(search, "ENTER", this::chooseSelected);
        bind(search, "ESCAPE", this::close);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && !list.getModel().getElementAt(i).isHeader()) {
                    list.setSelectedIndex(i);
                    chooseSelected();
                }
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && !list.getModel().getElementAt(i).isHeader()) list.setSelectedIndex(i);
            }
        });

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel title = Ui.label("Select " + what, "medium");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(title, BorderLayout.NORTH);
        north.add(search, BorderLayout.CENTER);
        p.add(north, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        p.add(Ui.muted("\u2191\u2193 move   Enter select   Esc close   " + branches.size() + " branches"), BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(Math.max(380, getWidth()), 420));
        popup = new JPopupMenu();
        popup.setLayout(new BorderLayout());
        popup.add(p, BorderLayout.CENTER);
        refilter.run();
        popup.show(this, 0, getHeight() + 2);
        SwingUtilities.invokeLater(() -> {
            search.requestFocusInWindow();
            search.setCaretPosition(search.getText().length());
        });
    }

    private void move(int delta) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) rows.add(list.getModel().getElementAt(i));
        int cur = list.getSelectedIndex(), dir = delta > 0 ? 1 : -1, next = cur;
        for (int k = 0; k < Math.abs(delta); k++) {
            int n = BranchFilter.nextBranchRow(rows, next, dir);
            if (n < 0) break;
            next = n;
        }
        if (next >= 0 && next != cur) {
            list.setSelectedIndex(next);
            list.ensureIndexIsVisible(next);
        }
    }

    void chooseSelected() {
        Row r = list == null ? null : list.getSelectedValue();
        if (r == null || r.isHeader()) return;
        close();
        if (!r.branch().name().equals(selected)) {
            setSelected(r.branch().name());
            onChoose.accept(selected);
        }
        requestFocusInWindow();
    }

    void close() {
        if (popup != null) popup.setVisible(false);
    }

    private static void bind(JComponent c, String key, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key + "-branch");
        c.getActionMap().put(key + "-branch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
            Row r = (Row) value;
            if (r.isHeader()) {
                super.getListCellRendererComponent(l, r.header(), index, false, false);
                setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
                setForeground(Ui.muted());
                setIcon(null);
                setBorder(BorderFactory.createEmptyBorder(index == 0 ? 2 : 8, 6, 2, 6));
                return this;
            }
            super.getListCellRendererComponent(l, highlight(r.branch().name(), search.getText(), sel), index, sel, false);
            boolean current = r.branch().name().equals(selected);
            setIcon(current ? Ui.icon(Ui.Glyph.CHECK, sel ? getForeground() : Ui.GREEN) : Ui.icon(Ui.Glyph.BRANCH, sel ? getForeground() : Ui.muted()));
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(3, 14, 3, 6));
            return this;
        }
    }

    /** the name with the matched parts in bold (HTML) */
    static String highlight(String name, String query, boolean selected) {
        String[] words = BranchFilter.words(query);
        if (words.length == 0) return name;
        boolean[] hit = new boolean[name.length()];
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String w : words) {
            int i = lower.indexOf(w);
            while (i >= 0) {
                for (int k = i; k < i + w.length(); k++) hit[k] = true;
                i = lower.indexOf(w, i + 1);
            }
        }
        StringBuilder sb = new StringBuilder("<html>");
        String color = selected ? "" : " color='" + Ui.hex(Ui.ACCENT) + "'";
        for (int i = 0; i < name.length(); i++) {
            boolean start = hit[i] && (i == 0 || !hit[i - 1]), end = hit[i] && (i == name.length() - 1 || !hit[i + 1]);
            if (start) sb.append("<b><font").append(color).append('>');
            sb.append(ResultsPanel.esc(String.valueOf(name.charAt(i))));
            if (end) sb.append("</font></b>");
        }
        return sb.append("</html>").toString();
    }
}
