package com.branchdiff.gui;

import com.branchdiff.conflict.ConflictModel;
import com.branchdiff.conflict.ConflictModel.BlameRun;
import com.branchdiff.conflict.ConflictModel.ChecklistItem;
import com.branchdiff.conflict.ConflictModel.CommitNeed;
import com.branchdiff.conflict.ConflictModel.ConflictFile;
import com.branchdiff.conflict.ConflictModel.EntryPoint;
import com.branchdiff.conflict.ConflictModel.Event;
import com.branchdiff.conflict.ConflictModel.Hunk;
import com.branchdiff.conflict.ConflictModel.Member;
import com.branchdiff.conflict.ConflictModel.Need;
import com.branchdiff.conflict.ConflictModel.Orphan;
import com.branchdiff.report.ContextModel;
import com.branchdiff.report.ContextModel.TreeNode;
import com.branchdiff.report.Report;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Conflict tree (files -> hunks) and the detail of the selected hunk: merge view, explanation tabs, source view. */
final class ResultsPanel {
    final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("no analysis")));
    final JPanel detail = new JPanel(new BorderLayout());
    private final DiffView diff = new DiffView();
    private final SourceView source = new SourceView();
    final JTabbedPane tabs = new JTabbedPane();
    private final JLabel summary = new JLabel(" ");
    private AnalysisJob.Result result;
    private Hunk current;
    private ConflictFile currentFile;
    private final JCheckBox onlyThisHunk = new JCheckBox("Only steps of this hunk", false);

    ResultsPanel() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(0);
        tree.addTreeSelectionListener(e -> onSelect());
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        JSplitPane bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, source);
        bottom.setResizeWeight(0.6);
        bottom.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diff, bottom);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        summary.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        detail.add(summary, BorderLayout.NORTH);
        detail.add(split, BorderLayout.CENTER);
        onlyThisHunk.addActionListener(e -> {
            if (current != null) showHunk(currentFile, current);
        });
    }

    void setResult(AnalysisJob.Result r) {
        this.result = r;
        current = null;
        currentFile = null;
        source.setResult(r);
        diff.clear();
        tabs.removeAll();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("conflicts");
        if (r != null && r.report().conflicts != null) {
            ConflictModel c = r.report().conflicts;
            for (ConflictFile f : c.files) {
                DefaultMutableTreeNode fn = new DefaultMutableTreeNode(f);
                for (Hunk h : f.hunks) fn.add(new DefaultMutableTreeNode(h));
                root.add(fn);
            }
            summary.setText(c.summary.get("conflictHunks") + " conflict hunk(s) in " + c.files.size() + " file(s), "
                    + c.cleanFiles.size() + " file(s) merged cleanly, " + c.checklist.size() + " checklist step(s), "
                    + c.bringAlong.size() + " mainline commit(s) to consider  -  analysis " + r.millis() + " ms");
        } else {
            summary.setText(r == null ? " " : "no conflict model (see the exported report)");
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    AnalysisJob.Result result() {
        return result;
    }

    /** selects hunk {@code id} (e.g. "SysRoleController.java#1"), or the first hunk when null / unknown */
    void selectHunk(String id) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        DefaultMutableTreeNode first = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode f = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < f.getChildCount(); j++) {
                DefaultMutableTreeNode h = (DefaultMutableTreeNode) f.getChildAt(j);
                if (first == null) first = h;
                if (id != null && id.equals(((Hunk) h.getUserObject()).id)) {
                    select(h);
                    return;
                }
            }
        }
        if (first != null) select(first);
    }

    private void select(DefaultMutableTreeNode n) {
        TreePath p = new TreePath(n.getPath());
        tree.setSelectionPath(p);
        java.awt.Rectangle b = tree.getPathBounds(p);
        if (b != null) tree.scrollRectToVisible(new java.awt.Rectangle(0, b.y, 1, b.height)); // keep the handles visible
    }

    void refreshTheme() {
        diff.refresh();
        source.refreshTheme();
        if (current != null) showHunk(currentFile, current);
    }

    private void onSelect() {
        TreePath p = tree.getSelectionPath();
        if (p == null || result == null) return;
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) p.getLastPathComponent();
        Object o = n.getUserObject();
        if (o instanceof Hunk h) {
            showHunk((ConflictFile) ((DefaultMutableTreeNode) n.getParent()).getUserObject(), h);
        } else if (o instanceof ConflictFile f && n.getChildCount() > 0) {
            select((DefaultMutableTreeNode) n.getChildAt(0));
        }
    }

    // ------------------------------------------------------------------ hunk detail

    private void showHunk(ConflictFile f, Hunk h) {
        int tab = Math.max(0, tabs.getSelectedIndex());
        current = h;
        currentFile = f;
        String[] texts = result.contents().get(f.viewKey);
        if (texts == null) texts = new String[3];
        String headline = "<html><b>" + esc(h.id) + "</b>  " + esc(f.kind) + (h.step > 1 || hasSteps() ? "  step " + h.step : "")
                + "  <span style='color:gray'>" + esc(f.path) + "</span></html>";
        diff.show(f.path, headline, texts, new ConflictModel.Range[] {h.ours, h.base, h.theirs});
        tabs.removeAll();
        tabs.addTab("Why", why(f, h));
        tabs.addTab("Take theirs", takeTheirs(h));
        tabs.addTab("Take ours", takeOurs(h));
        tabs.addTab("Checklist", checklist(h));
        tabs.addTab("Dependency chain", chain(h));
        tabs.setSelectedIndex(Math.min(tab, tabs.getTabCount() - 1));
    }

    private boolean hasSteps() {
        return result.report().conflicts.picks.size() > 1;
    }

    private JComponent why(ConflictFile f, Hunk h) {
        StringBuilder sb = new StringBuilder();
        sb.append(nz(h.why));
        if (f.explanation != null) sb.append("\n\nFile: ").append(f.explanation);
        sb.append("\n\nMembers - ours: ").append(members(h.members.get("ours"))).append("; base: ").append(members(h.members.get("base")))
                .append("; theirs: ").append(members(h.members.get("theirs")));
        for (String n : h.notes) sb.append("\nNote: ").append(n);
        List<Object[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (Event e : h.mainline) addEvent(rows, actions, "mainline", e, f.path);
        for (Event e : h.tenant) addEvent(rows, actions, "tenant", e, f.path);
        for (BlameRun b : h.baseBlame) {
            rows.add(new Object[] {"base lines", b.boundary() ? "(before fork)" : b.commit().shortSha(), b.boundary() ? "" : day(b.commit().date()),
                    "L" + b.start() + (b.end() > b.start() ? "-" + b.end() : ""), "blame", b.boundary() ? "" : b.commit().subject()});
            actions.add(b.boundary() ? () -> source.open(new Loc("base", f.path, b.start())) : () -> source.showCommit(b.commit().sha(), f.path));
        }
        for (Event e : f.fileHistory) addEvent(rows, actions, "file history", e, f.path);
        return withHeader(sb.toString(), table(new String[] {"Side", "Commit", "Date", "Change", "Member", "Subject"}, rows, actions));
    }

    private void addEvent(List<Object[]> rows, List<Runnable> actions, String side, Event e, String path) {
        String member = e.member != null ? label(e.member) : "";
        if (e.from != null && e.to != null && !e.from.equals(e.to)) member = e.from + " -> " + e.to;
        rows.add(new Object[] {side, e.commit.shortSha(), day(e.commit.date()), e.kind + (e.detail != null ? " (" + e.detail + ")" : ""),
                member, e.commit.subject() + "  - " + e.commit.author()});
        actions.add(() -> source.showCommit(e.commit.sha(), path));
    }

    private JComponent takeTheirs(Hunk h) {
        StringBuilder sb = new StringBuilder(nz(h.takeTheirs.summary));
        for (String m : h.takeTheirs.movedImports) sb.append("\nMoved import: ").append(m);
        List<Object[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<Need> needs = new ArrayList<>(h.takeTheirs.needs);
        needs.sort(java.util.Comparator.comparing((Need n) -> "PRESENT".equals(n.ours))); // gaps first, stable
        for (Need n : needs) {
            StringBuilder intro = new StringBuilder();
            for (ConflictModel.Commit c : n.introducedBy) intro.append(intro.length() > 0 ? ", " : "").append(c.shortSha()).append(' ').append(c.subject());
            rows.add(new Object[] {n.node != null && node(n.node) != null ? label(n.node) : n.symbol, n.line > 0 ? n.line : "", n.ours + (n.uncertain ? " (?)" : ""), nz(n.origin),
                    n.tenantName == null ? "" : n.tenantName + (n.tenantLocation != null ? " " + n.tenantLocation : ""), intro.toString(), nz(n.detail)});
            Loc l = n.theirsLocation != null ? Loc.parse("theirs", n.theirsLocation)
                    : n.line > 0 ? new Loc("theirs", h.path, n.line) : Loc.parse("ours", n.oursLocation);
            Loc tenant = Loc.parse("ours", n.tenantLocation != null ? n.tenantLocation : n.oursLocation);
            actions.add(() -> source.open(l != null ? l : tenant));
        }
        return withHeader(sb.toString(), table(new String[] {"Symbol", "Line", "In tenant", "Origin", "Tenant has", "Introduced by", "Detail"}, rows, actions));
    }

    private JComponent takeOurs(Hunk h) {
        StringBuilder sb = new StringBuilder(nz(h.takeOurs.summary));
        for (String l : h.takeOurs.lost) sb.append("\nLoses: ").append(l);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode eps = new DefaultMutableTreeNode(new Item("Entry points (" + h.takeOurs.entryPoints.size() + ")", null));
        for (EntryPoint e : h.takeOurs.entryPoints) {
            DefaultMutableTreeNode en = new DefaultMutableTreeNode(new Item(e.type + "  " + nz(e.route)
                    + (e.permission != null ? "   " + e.permission : "") + "   " + shortLoc(nz(e.location)), Loc.parse("ours", e.location), e.type));
            for (int i = 0; i < e.path.size(); i++) {
                String k = e.path.get(i);
                en.add(new DefaultMutableTreeNode(new Item((i == 0 ? "" : "-> ") + label(k), nodeLoc(k, "ours"))));
            }
            eps.add(en);
        }
        root.add(eps);
        if (!h.takeOurs.orphans.isEmpty()) {
            DefaultMutableTreeNode on = new DefaultMutableTreeNode(new Item("Left dangling if you keep ours (" + h.takeOurs.orphans.size() + ")", null));
            for (Orphan o : h.takeOurs.orphans) {
                on.add(new DefaultMutableTreeNode(new Item(label(o.usedBy()) + " uses " + label(o.node()) + "  - " + nz(o.note()),
                        Loc.parse("theirs", o.location()))));
            }
            root.add(on);
        }
        DefaultMutableTreeNode imp = new DefaultMutableTreeNode(new Item("Callers in the tenant (reverse impact)", null));
        for (TreeNode t : h.takeOurs.impact) imp.add(treeNode(t, "ours"));
        root.add(imp);
        return withHeader(sb.toString(), itemTree(root));
    }

    private JComponent checklist(Hunk h) {
        ConflictModel c = result.report().conflicts;
        List<Object[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (ChecklistItem i : c.checklist) {
            boolean mine = i.hunks().contains(h.id);
            if (onlyThisHunk.isSelected() && !mine) continue;
            rows.add(new Object[] {(mine ? "> " : "") + i.step(), i.action(), nz(i.target()), nz(i.why()), String.join(", ", i.hunks()),
                    String.join(", ", i.commits())});
            Loc l = i.node() != null ? nodeLoc(i.node(), "theirs") : null;
            if (l == null && i.node() != null) l = nodeLoc(i.node(), "ours");
            Loc fl = l;
            String commit = i.commits().isEmpty() ? null : i.commits().get(0);
            actions.add(() -> {
                if (fl != null) source.open(fl);
                else if (commit != null) source.showCommit(commit, null);
            });
        }
        JPanel p = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2));
        top.add(onlyThisHunk);
        top.add(new JLabel("Steps marked > touch this hunk. Do them in order: ADAPT / BRING / CONSIDER_COMMIT before RESOLVE."));
        p.add(top, BorderLayout.NORTH);
        p.add(table(new String[] {"Step", "Action", "Target", "Why", "Hunks", "Commits"}, rows, actions), BorderLayout.CENTER);
        return p;
    }

    private JComponent chain(Hunk h) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode needs = new DefaultMutableTreeNode(new Item("Taking theirs needs (dependency trees, depth "
                + (result.report().context != null ? result.report().context.maxDepth : 4) + ")", null));
        for (TreeNode t : h.takeTheirs.trees) needs.add(treeNode(t, "theirs"));
        root.add(needs);
        DefaultMutableTreeNode users = new DefaultMutableTreeNode(new Item("Used by in the tenant (reverse impact)", null));
        for (TreeNode t : h.takeOurs.impact) users.add(treeNode(t, "ours"));
        root.add(users);
        List<CommitNeed> along = result.report().conflicts.bringAlong;
        if (!along.isEmpty()) {
            DefaultMutableTreeNode cn = new DefaultMutableTreeNode(new Item("Mainline commits the pick depends on (oldest first)", null));
            for (CommitNeed n : along) {
                cn.add(new DefaultMutableTreeNode(new Item(n.commit().shortSha() + "  " + day(n.commit().date()) + "  " + n.commit().subject()
                        + "   provides " + String.join(", ", n.provides()) + (n.inTenant() ? "  (in tenant)" : ""), new Loc("commit", n.commit().sha(), 0))));
            }
            root.add(cn);
        }
        return itemTree(root);
    }

    private DefaultMutableTreeNode treeNode(TreeNode t, String side) {
        ContextModel.Node n = node(t.node);
        String status = n != null && n.ours != null ? n.ours : null;
        String text = label(t.node) + (t.at != null ? "   at " + shortLoc(t.at) : "") + (t.uncertain ? "  (?)" : "") + (t.cycle ? "  (cycle)" : "")
                + (t.truncated ? "  (...)" : "") + (t.omitted > 0 ? "  (+" + t.omitted + " more)" : "");
        Loc l = nodeLoc(t.node, side);
        if (l == null) l = Loc.parse(side, t.at);
        DefaultMutableTreeNode d = new DefaultMutableTreeNode(new Item(text, l, "theirs".equals(side) ? status : null));
        for (TreeNode c : t.children) d.add(treeNode(c, side));
        return d;
    }

    private ContextModel.Node node(String key) {
        Report r = result.report();
        return r.context == null || key == null ? null : r.context.nodes.get(key);
    }

    private String label(String key) {
        ContextModel.Node n = node(key);
        return n != null && n.label != null ? n.label : String.valueOf(key);
    }

    private Loc nodeLoc(String key, String side) {
        ContextModel.Node n = node(key);
        if (n == null) return null;
        ContextModel.Loc l = n.loc.get(side);
        if (l == null) return null;
        return new Loc(side, l.path(), l.line() > 0 ? l.line() : l.start());
    }

    // ------------------------------------------------------------------ widgets

    private JComponent itemTree(DefaultMutableTreeNode root) {
        JTree t = new JTree(new DefaultTreeModel(root));
        t.setRootVisible(false);
        t.setShowsRootHandles(true);
        t.setCellRenderer(new Renderer());
        for (int i = 0; i < t.getRowCount() && i < 200; i++) t.expandRow(i);
        t.addTreeSelectionListener(e -> {
            TreePath p = t.getSelectionPath();
            if (p == null) return;
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof Item it && it.loc() != null) openLoc(it.loc());
        });
        return new JScrollPane(t);
    }

    private void openLoc(Loc l) {
        if ("commit".equals(l.side())) source.showCommit(l.path(), null);
        else source.open(l);
    }

    private JComponent table(String[] cols, List<Object[]> rows, List<Runnable> actions) {
        JTable t = new JTable(new AbstractTableModel() {
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
            public Object getValueAt(int r, int c) {
                return rows.get(r)[c];
            }
        });
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = t.getSelectedRow();
            if (r >= 0 && r < actions.size()) actions.get(r).run();
        });
        for (int c = 0; c < cols.length; c++) {
            int w = switch (cols[c]) {
                case "Side", "Line", "Step", "Date", "Commit", "In tenant" -> 90;
                case "Action", "Origin", "Hunks", "Change" -> 170;
                default -> 260;
            };
            t.getColumnModel().getColumn(c).setPreferredWidth(w);
        }
        return new JScrollPane(t);
    }

    private static JComponent withHeader(String text, JComponent body) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setRows(Math.min(6, Math.max(2, text.split("\n").length + text.length() / 140)));
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(a, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(null);
            }
        }, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static String members(List<Member> ms) {
        if (ms == null) return "(file absent)";
        if (ms.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Member m : ms) sb.append(sb.length() > 0 ? ", " : "").append(m.label());
        return sb.toString();
    }

    private static String day(String iso) {
        return iso == null || iso.length() < 10 ? String.valueOf(iso) : iso.substring(0, 10);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ rendering

    static Color severityColor(String s) {
        if (s == null) return null;
        return switch (s) {
            case "HIGH", "MISSING", "CHANGED" -> new Color(0xE5493A);
            case "MEDIUM", "DIFFERS", "UNKNOWN" -> new Color(0xE8A33D);
            case "LOW", "PRESENT" -> new Color(0x59A869);
            case "HTTP", "SCHEDULED", "LISTENER", "STARTUP", "MAIN" -> new Color(0x3592C4);
            default -> null;
        };
    }

    /** a small filled circle, like IDEA's inspection severity markers */
    record Dot(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 2, y + 2, 8, 8);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    private final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            String gray = sel ? "" : " style='color:" + hex(UIManager.getColor("Label.disabledForeground")) + "'";
            if (o instanceof ConflictFile f) {
                HunkSeverity s = HunkSeverity.of(f);
                String name = f.path.substring(f.path.lastIndexOf('/') + 1);
                setText("<html>" + esc(name) + " <span" + gray + ">" + esc(f.kind) + (hasStepsSafe() ? " · step " + f.step : "")
                        + " · " + f.hunks.size() + "</span></html>");
                setToolTipText(f.path);
                setIcon(new Dot(severityColor(s.name())));
            } else if (o instanceof Hunk h) {
                HunkSeverity s = HunkSeverity.of(parentFile(value), h);
                String mem = members(h.members.get("ours") != null && !h.members.get("ours").isEmpty() ? h.members.get("ours") : h.members.get("theirs"));
                setText("<html>#" + h.index + " <b>" + s + "</b> <span" + gray + ">ours " + lines(h.ours) + " · " + esc(shorten(mem, 60)) + "</span></html>");
                setToolTipText(h.why);
                setIcon(new Dot(severityColor(s.name())));
            } else if (o instanceof Item it) {
                setText(it.text());
                Color c = severityColor(it.badge());
                setIcon(c != null ? new Dot(c) : null);
                setToolTipText(it.loc() != null ? it.loc().toString() : null);
            }
            return this;
        }

        private ConflictFile parentFile(Object node) {
            Object p = ((DefaultMutableTreeNode) ((DefaultMutableTreeNode) node).getParent()).getUserObject();
            return p instanceof ConflictFile f ? f : null;
        }
    }

    /** "a/b/C.java:12" -> "C.java:12" (the full path is in the tooltip) */
    static String shortLoc(String loc) {
        int slash = loc.lastIndexOf('/');
        return slash < 0 ? loc : loc.substring(slash + 1);
    }

    static String lines(ConflictModel.Range r) {
        if (r == null) return "-";
        return r.empty() ? "insert at " + r.start() : r.start() == r.end() ? "L" + r.start() : "L" + r.start() + "-" + r.end();
    }

    private boolean hasStepsSafe() {
        return result != null && result.report().conflicts != null && result.report().conflicts.picks.size() > 1;
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String hex(Color c) {
        if (c == null) return "#888888";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** for automation: selects the tab with this title */
    void selectTab(String title, Consumer<JComponent> then) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).equals(title)) {
                tabs.setSelectedIndex(i);
                if (then != null) then.accept((JComponent) tabs.getComponentAt(i));
                return;
            }
        }
    }

    Map<String, String[]> contents() {
        return result == null ? Map.of() : result.contents();
    }
}
