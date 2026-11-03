package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;
import com.branchdiff.gui.GitQueries.CommitRow;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The mainline commits the target lacks, newest first like IDEA's Git log: a filter box, multi-selection (shift /
 * ctrl / cmd) and a Pick checkbox column. Space toggles the checkbox of the selected rows. Whatever the click order,
 * {@link #chosen()} is handed to {@link PickOrder} for the apply order.
 */
final class CommitsPanel extends JPanel {
    private final List<CommitRow> rows = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();
    private final Model model = new Model();
    final JTable table = new JTable(model);
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    final JTextField filter = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final JTextArea details = new JTextArea();
    private final Runnable onChange;
    private GitRunner git;
    private int detailGen;

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Pick", "Hash", "Message", "Author", "Date"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            CommitRow cr = rows.get(r);
            return switch (c) {
                case 0 -> checked.contains(cr.sha());
                case 1 -> cr.shortSha();
                case 2 -> cr.subject();
                case 3 -> cr.author();
                default -> cr.day();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c != 0) return;
            if (Boolean.TRUE.equals(v)) checked.add(rows.get(r).sha());
            else checked.remove(rows.get(r).sha());
            fireTableCellUpdated(r, 0);
            changed();
        }
    }

    CommitsPanel(Runnable onChange) {
        super(new BorderLayout());
        this.onChange = onChange;
        filter.putClientProperty("JTextField.placeholderText", "Filter by message, author or sha  (author:name, sha:abc)");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        table.setRowSorter(sorter);
        for (int i = 0; i < 5; i++) sorter.setSortable(i, false); // keep the log order
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        int[] widths = {44, 90, 420, 120, 90};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "togglePick");
        table.getActionMap().put("togglePick", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleSelected();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                changed();
                showDetails();
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        top.add(filter, BorderLayout.CENTER);
        JButton check = new JButton("Check selected");
        check.setToolTipText("Toggle the Pick checkbox of the selected rows (Space)");
        check.addActionListener(e -> toggleSelected());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            checked.clear();
            model.fireTableDataChanged();
            changed();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(check);
        buttons.add(clear);
        top.add(buttons, BorderLayout.EAST);

        details.setEditable(false);
        details.setFont(CodeViewsLite.mono());
        details.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.72);
        split.setBorder(null);
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    void setCommits(GitRunner git, List<CommitRow> commits, String target, String mainline, boolean truncated) {
        this.git = git;
        rows.clear();
        rows.addAll(commits);
        checked.clear();
        model.fireTableDataChanged();
        details.setText(commits.isEmpty() ? "No mainline commits missing from " + target + "." : "");
        changed();
        status.setToolTipText("git log --no-merges " + target + ".." + mainline);
        this.truncated = truncated;
        this.range = target + ".." + mainline;
        changed();
    }

    private boolean truncated;
    private String range = "";

    private void applyFilter() {
        CommitFilter f = new CommitFilter(filter.getText());
        sorter.setRowFilter(f.isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends Model, ? extends Integer> e) {
                return f.matches(rows.get(e.getIdentifier()));
            }
        });
        changed();
    }

    private void toggleSelected() {
        List<CommitRow> sel = selected();
        if (sel.isEmpty()) return;
        boolean allChecked = sel.stream().allMatch(c -> checked.contains(c.sha()));
        for (CommitRow c : sel) {
            if (allChecked) checked.remove(c.sha());
            else checked.add(c.sha());
        }
        model.fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
        changed();
    }

    List<CommitRow> selected() {
        List<CommitRow> res = new ArrayList<>();
        for (int v : table.getSelectedRows()) res.add(rows.get(table.convertRowIndexToModel(v)));
        return res;
    }

    /** the checked commits, or the selected rows when nothing is checked; in apply order */
    List<CommitRow> chosen() {
        List<CommitRow> res = new ArrayList<>();
        if (!checked.isEmpty()) {
            for (CommitRow c : rows) if (checked.contains(c.sha())) res.add(c);
        } else {
            res.addAll(selected());
        }
        return PickOrder.applyOrder(res);
    }

    /** checks the given commits (full shas) and selects their rows (automation and --pick) */
    void check(Set<String> shas) {
        checked.clear();
        table.clearSelection();
        for (int i = 0; i < rows.size(); i++) {
            if (!shas.contains(rows.get(i).sha())) continue;
            checked.add(rows.get(i).sha());
            int v = table.convertRowIndexToView(i);
            if (v >= 0) table.addRowSelectionInterval(v, v);
        }
        model.fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
        changed();
    }

    private void changed() {
        List<CommitRow> ch = chosen();
        String how = checked.isEmpty() ? (ch.isEmpty() ? "" : " (selected)") : " (checked)";
        status.setText(table.getRowCount() + " of " + rows.size() + " commits " + range + (truncated ? " (newest only)" : "")
                + "  -  " + ch.size() + " to pick" + how + (ch.size() > 1 ? ", applied oldest first: "
                + String.join(" -> ", ch.stream().map(c -> c.shortSha().substring(0, 8)).toList()) : ""));
        if (onChange != null) onChange.run();
    }

    private void showDetails() {
        int lead = table.getSelectionModel().getLeadSelectionIndex();
        if (git == null || lead < 0 || lead >= table.getRowCount()) return;
        CommitRow c = rows.get(table.convertRowIndexToModel(lead));
        int gen = ++detailGen;
        GitRunner g = git;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    return g.run("-c", "core.quotePath=false", "show", "--stat=120", "--format=%H%nAuthor: %an <%ae>%nDate:   %aI%n%n%w(0,4,4)%B", c.sha());
                } catch (RuntimeException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                if (gen != detailGen) return;
                try {
                    details.setText(get());
                    details.setCaretPosition(0);
                } catch (Exception ignored) {
                    // keep the old text
                }
            }
        }.execute();
    }
}
