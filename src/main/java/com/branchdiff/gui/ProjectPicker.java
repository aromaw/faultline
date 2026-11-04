package com.branchdiff.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The sidebar while no project is open: an "Open Folder…" button, the recent projects as one-click rows (name and
 * path; missing folders are left out), and a note that a folder can be dropped onto the window.
 */
final class ProjectPicker extends JPanel {
    private final Prefs prefs;
    private final Consumer<Path> open;
    private final DefaultListModel<Path> recents = new DefaultListModel<>();
    private final JList<Path> list = new JList<>(recents);
    private final JComponent recentBox;

    ProjectPicker(Prefs prefs, Runnable choose, Consumer<Path> open) {
        super(new GridBagLayout());
        this.prefs = prefs;
        this.open = open;
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(BorderFactory.createEmptyBorder(24, 20, 24, 20));

        JLabel title = Ui.heading("Open a project", 4);
        JLabel sub = Ui.muted("A git repository with both branches.");
        JButton openBtn = new JButton("Open Folder…", Ui.icon(Ui.Glyph.FOLDER, Color.WHITE));
        openBtn.putClientProperty("FlatLaf.style", "font: bold; arc: 10; margin: 6,16,6,18; iconTextGap: 8;"
                + "background: " + Ui.hex(Ui.ACCENT) + "; foreground: #FFFFFF; borderColor: " + Ui.hex(Ui.ACCENT));
        openBtn.setToolTipText("Choose a folder (Ctrl/⌘+O)");
        openBtn.addActionListener(e -> choose.run());
        JLabel drop = Ui.muted("or drop a folder onto this window");
        Ui.style(drop, "small");

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        list.setOpaque(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) open.accept(recents.get(i));
            }
        });
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) list.setSelectedIndex(i);
                else list.clearSelection();
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && list.getSelectedValue() != null) open.accept(list.getSelectedValue());
            }
        });
        JPanel rb = new JPanel(new BorderLayout(0, 6));
        JLabel recentTitle = Ui.label("Recent projects", "small");
        recentTitle.setForeground(Ui.muted());
        rb.add(recentTitle, BorderLayout.NORTH);
        rb.add(list, BorderLayout.CENTER);
        recentBox = rb;

        for (JComponent c : new JComponent[] {title, sub, openBtn, drop, rb}) c.setAlignmentX(0f);
        col.add(title);
        col.add(Box.createVerticalStrut(4));
        col.add(sub);
        col.add(Box.createVerticalStrut(16));
        col.add(openBtn);
        col.add(Box.createVerticalStrut(6));
        col.add(drop);
        col.add(Box.createVerticalStrut(28));
        col.add(rb);
        col.setMaximumSize(new Dimension(460, Integer.MAX_VALUE));
        // at the top of the sidebar, not in its middle
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.anchor = java.awt.GridBagConstraints.NORTHWEST;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        add(col, c);
        reload();
        // the list follows the preferences each time the panel is shown again (e.g. after a project failed to open)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload();
        });
    }

    void reload() {
        recents.clear();
        for (String p : prefs.recentProjects()) {
            Path path = Path.of(p);
            if (Files.isDirectory(path)) recents.addElement(path);
        }
        recentBox.setVisible(!recents.isEmpty());
        revalidate();
    }

    /** one recent project: folder icon, name in bold, the path below it */
    private static final class Row extends JPanel implements ListCellRenderer<Path> {
        private final JLabel name = new JLabel();
        private final JLabel path = new JLabel();

        Row() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JPanel text = new JPanel(new BorderLayout(0, 1));
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(path, BorderLayout.SOUTH);
            add(new JLabel(Ui.icon(Ui.Glyph.FOLDER, Ui.ACCENT)), BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Path> l, Path p, int index, boolean selected, boolean focus) {
            Font f = UIManager.getFont("Label.font");
            name.setFont(f.deriveFont(Font.BOLD));
            name.setText(p.getFileName() != null ? p.getFileName().toString() : p.toString());
            name.setForeground(Ui.fg());
            path.setFont(f.deriveFont(f.getSize2D() - 1.5f));
            path.setForeground(Ui.muted());
            path.setText(MainFrame.shortPath(p.toString()));
            setToolTipText(p.toString());
            setOpaque(selected);
            setBackground(selected ? Ui.alpha(Ui.ACCENT, Ui.dark() ? 0.25f : 0.10f) : null);
            return this;
        }
    }
}
