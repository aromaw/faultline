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
import com.branchdiff.conflict.ConflictModel.Suggestion;
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
    /** one line of chips (a FlowLayout wrapped its last item onto a second line that the panel then clipped) */
    private final JPanel summary = new JPanel();
    private AnalysisJob.Result result;
    private Hunk current;
    private ConflictFile currentFile;
    private final JCheckBox onlyThisHunk = new JCheckBox("Only steps of this hunk", false);

    private final Prefs prefs;
    /** the tabs and, once something was opened in it, the file / commit pane on their right */
    private final JSplitPane bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final javax.swing.JComponent hint;

    ResultsPanel(Prefs prefs) {
        this.prefs = prefs;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(0);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(e -> onSelect());
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        bottom.setLeftComponent(tabs);
        bottom.setRightComponent(null); // shown on the first click on a location or a commit (showSource)
        bottom.setResizeWeight(0.6);
        bottom.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diff, bottom);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        summary.setLayout(new javax.swing.BoxLayout(summary, javax.swing.BoxLayout.X_AXIS));
        summary.setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
        hint = hint();
        JPanel north = new JPanel(new BorderLayout());
        north.add(summary, BorderLayout.NORTH);
        north.add(hint, BorderLayout.SOUTH);
        hint.setVisible(!prefs.flag("hint.results.hidden"));
        detail.add(north, BorderLayout.NORTH);
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
        bottom.setRightComponent(null); // until something is opened in it
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
            summary.removeAll();
            chip(String.valueOf(c.summary.get("conflictHunks")), "conflict hunks", Ui.RED);
            chip(String.valueOf(c.files.size()), "conflicted files", Ui.ORANGE);
            chip(String.valueOf(c.cleanFiles.size()), "merged cleanly", Ui.GREEN);
            chip(String.valueOf(c.checklist.size()), "checklist steps", Ui.BLUE);
            chip(String.valueOf(c.bringAlong.size()), "commits to consider", Ui.muted());
        } else {
            summary.removeAll();
            if (r != null) summary.add(Ui.muted("no conflict model (see the exported report)"));
        }
        summary.revalidate();
        summary.repaint();
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    /** how to read the result, for a first-time user; "Hide" remembers that it is not needed */
    private javax.swing.JComponent hint() {
        // an info banner: a soft accent-tinted strip with rounded corners
        JPanel p = new Ui.Tinted(new BorderLayout(10, 0), () -> Ui.ACCENT, 12);
        p.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 6));
        JLabel text = new JLabel("<html>Pick a conflict on the left. <b>Ours</b> = target branch, <b>Base</b> = the pick's parent, "
                + "<b>Theirs</b> = the pick. The tabs explain the conflict; <b>Checklist</b> lists every step.</html>",
                Ui.icon(Ui.Glyph.INFO, Ui.ACCENT), javax.swing.SwingConstants.LEFT);
        text.setIconTextGap(10);
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 10));
        outer.add(p);
        javax.swing.JButton hide = new javax.swing.JButton("Hide");
        Ui.style(hide, "small");
        hide.putClientProperty("JButton.buttonType", "borderless");
        hide.setToolTipText("Do not show this hint again");
        hide.addActionListener(e -> {
            prefs.setFlag("hint.results.hidden", true);
            outer.setVisible(false);
            detail.revalidate();
        });
        p.add(text, BorderLayout.CENTER);
        p.add(hide, BorderLayout.EAST);
        return outer;
    }

    /** opens a location in the file pane, showing the pane first if it is still hidden */
    private void showSource(Loc l) {
        revealSource();
        source.open(l);
    }

    private void showCommit(String sha, String path) {
        revealSource();
        source.showCommit(sha, path);
    }

    private void revealSource() {
        if (bottom.getRightComponent() == source) return;
        bottom.setRightComponent(source);
        bottom.revalidate();
        javax.swing.SwingUtilities.invokeLater(() -> bottom.setDividerLocation(0.6));
    }

    private void chip(String n, String what, Color color) {
        summary.add(Ui.chip(n, what, color));
        summary.add(javax.swing.Box.createHorizontalStrut(6));
    }

    /** the tab titles of the hunk detail (automation iterates them) */
    static final String TAB_SUGGEST = "Suggestion", TAB_WHY = "Why it conflicts", TAB_THEIRS = "Take theirs \u2192 needs", TAB_OURS = "Keep ours \u2192 callers",
            TAB_CHECKLIST = "Checklist", TAB_CHAIN = "Dependency chain";
    static final String[] TABS = {TAB_SUGGEST, TAB_WHY, TAB_THEIRS, TAB_OURS, TAB_CHECKLIST, TAB_CHAIN};

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
        HunkSeverity sev = HunkSeverity.of(f, h);
        String headline = "<html><span style='color:" + Ui.hex(Ui.severity(sev.name())) + "'><b>" + sev + "</b></span>&nbsp;&nbsp;<b>" + esc(h.id) + "</b>  "
                + esc(f.kind) + (h.step > 1 || hasSteps() ? "  step " + h.step : "")
                + "  <span style='color:" + Ui.hex(Ui.muted()) + "'>" + esc(f.path) + "</span></html>";
        diff.show(f.path, headline, texts, new ConflictModel.Range[] {h.ours, h.base, h.theirs});
        tabs.removeAll();
        tabs.addTab(TAB_SUGGEST, null, suggestion(f, h), "How to resolve this hunk, and why");
        tabs.addTab(TAB_WHY, null, why(f, h), "What each side changed here, and the commits behind it");
        tabs.addTab(TAB_THEIRS, null, takeTheirs(h), "Symbols the mainline code needs, and whether the tenant has them");
        tabs.addTab(TAB_OURS, null, takeOurs(h), "Entry points and callers in the tenant that keeping ours affects");
        tabs.addTab(TAB_CHECKLIST, null, checklist(h), "Ordered steps to resolve all conflicts");
        tabs.addTab(TAB_CHAIN, null, chain(h), "What theirs depends on, and what depends on ours");
        tabs.setSelectedIndex(Math.min(tab, tabs.getTabCount() - 1));
    }

    private boolean hasSteps() {
        return result.report().conflicts.picks.size() > 1;
    }

    static Color choiceColor(String choice) {
        return switch (choice == null ? "" : choice) {
            case "THEIRS" -> Ui.BLUE;
            case "OURS" -> Ui.GREEN;
            case "BOTH" -> Ui.ORANGE;
            default -> Ui.RED;
        };
    }

    private JComponent suggestion(ConflictFile f, Hunk h) {
        Suggestion sg = h.suggestion;
        if (sg == null) return withHeader("Suggestion", "No suggestion for this hunk" + (h.notes.isEmpty() ? "." : ": " + String.join("; ", h.notes)),
                "", new JPanel());
        StringBuilder sb = new StringBuilder(sg.headline);
        if (!sg.reasons.isEmpty()) {
            sb.append("\n\nWhy:");
            for (String r : sg.reasons) sb.append("\n  \u2022 ").append(r);
        }
        if (!sg.followUps.isEmpty()) {
            sb.append("\n\nThen:");
            for (String r : sg.followUps) sb.append("\n  \u2022 ").append(r);
        }
        JTextArea a = new JTextArea(sb.toString());
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setFont(UIManager.getFont("Label.font"));
        a.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        int rows = 0;
        for (String l : sb.toString().split("\n", -1)) rows += 1 + l.length() / 110; // wrapped lines too
        a.setRows(Math.min(14, rows));

        JPanel head = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        head.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 8));
        head.add(Ui.heading("Suggested", 0));
        head.add(new JLabel(new Ui.Badge(com.branchdiff.conflict.Suggestions.label(sg.choice).toUpperCase(), choiceColor(sg.choice))));
        head.add(Ui.style(Ui.muted(sg.confidence.toLowerCase() + " confidence"), "small"));

        JPanel top = new JPanel(new BorderLayout());
        top.add(head, BorderLayout.NORTH);
        top.add(new JScrollPane(a, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(null);
            }
        }, BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        if (sg.resolution == null) {
            JLabel none = Ui.style(Ui.muted(f.kind.startsWith("DELETED_BY") ? "File-level conflict: no lines to paste, see the git command above."
                    : "No mechanical resolution: both sides change the same lines. Compare them in the merge view above."), "small");
            none.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
            top.add(none, BorderLayout.SOUTH);
            return p;
        }
        String text = String.join("\n", sg.resolution);
        JPanel bar = new JPanel(new BorderLayout());
        JLabel lt = Ui.style(Ui.muted("Resolved lines: replace this hunk's conflict block (ours " + lines(h.ours) + ") with them"
                + (sg.resolution.isEmpty() ? " (none: delete the block)" : "")), "small");
        lt.setFont(lt.getFont().deriveFont(java.awt.Font.BOLD));
        lt.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        javax.swing.JButton copy = new javax.swing.JButton("Copy");
        copy.setToolTipText("Copy the resolved lines to the clipboard");
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(sg.resolution.isEmpty() ? "" : text + "\n"), null);
            copy.setText("Copied");
            javax.swing.Timer t = new javax.swing.Timer(1200, ev -> copy.setText("Copy"));
            t.setRepeats(false);
            t.start();
        });
        bar.add(lt, BorderLayout.CENTER);
        bar.add(Ui.pad(copy, 0, 0, 4, 8), BorderLayout.EAST);
        top.add(bar, BorderLayout.SOUTH);
        org.fife.ui.rsyntaxtextarea.RSyntaxTextArea code = CodeViews.area();
        code.setSyntaxEditingStyle(CodeViews.syntaxFor(f.path));
        code.setText(text);
        code.setCaretPosition(0);
        p.add(CodeViews.scroll(code), BorderLayout.CENTER);
        return p;
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
            actions.add(b.boundary() ? () -> showSource(new Loc("base", f.path, b.start())) : () -> showCommit(b.commit().sha(), f.path));
        }
        for (Event e : f.fileHistory) addEvent(rows, actions, "file history", e, f.path);
        return withHeader("Why it conflicts", sb.toString(), "Commits that changed these lines (click a row to see the commit)",
                table(new String[] {"Side", "Commit", "Date", "Change", "Member", "Subject"}, rows, actions));
    }

    private void addEvent(List<Object[]> rows, List<Runnable> actions, String side, Event e, String path) {
        String member = e.member != null ? label(e.member) : "";
        if (e.from != null && e.to != null && !e.from.equals(e.to)) member = e.from + " -> " + e.to;
        rows.add(new Object[] {side, e.commit.shortSha(), day(e.commit.date()), e.kind + (e.detail != null ? " (" + e.detail + ")" : ""),
                member, e.commit.subject() + "  - " + e.commit.author()});
        actions.add(() -> showCommit(e.commit.sha(), path));
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
            actions.add(() -> showSource(l != null ? l : tenant));
        }
        return withHeader("If you take theirs", sb.toString(), "Dependencies of the mainline code (missing ones first; click to open)",
                table(new String[] {"Symbol", "Line", "In tenant", "Origin", "Tenant has", "Introduced by", "Detail"}, rows, actions));
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
        DefaultMutableTreeNode imp = new DefaultMutableTreeNode(new Item("Callers in the tenant that use this code (reverse impact)", null));
        for (TreeNode t : h.takeOurs.impact) imp.add(treeNode(t, "ours"));
        root.add(imp);
        return withHeader("If you keep ours", sb.toString(), "Affected entry points and callers in the tenant (click to open)", itemTree(root));
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
                if (fl != null) showSource(fl);
                else if (commit != null) showCommit(commit, null);
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
        DefaultMutableTreeNode needs = new DefaultMutableTreeNode(new Item("Dependencies: what taking theirs needs (depth "
                + (result.report().context != null ? result.report().context.maxDepth : 4) + ")", null));
        for (TreeNode t : h.takeTheirs.trees) needs.add(treeNode(t, "theirs"));
        root.add(needs);
        DefaultMutableTreeNode users = new DefaultMutableTreeNode(new Item("Callers: what uses ours in the tenant (reverse impact)", null));
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
        if ("commit".equals(l.side())) showCommit(l.path(), null);
        else showSource(l);
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

    private static JComponent withHeader(String title, String text, String listTitle, JComponent body) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setRows(Math.min(6, Math.max(2, text.split("\n").length + text.length() / 140)));
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        a.setFont(UIManager.getFont("Label.font"));
        JPanel top = new JPanel(new BorderLayout());
        JLabel t = Ui.heading(title, 0);
        t.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        top.add(t, BorderLayout.NORTH);
        top.add(new JScrollPane(a, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(null);
            }
        }, BorderLayout.CENTER);
        JLabel lt = Ui.style(Ui.muted(listTitle), "small");
        lt.setFont(lt.getFont().deriveFont(java.awt.Font.BOLD));
        lt.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        top.add(lt, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
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
                setIcon(new Dot(Ui.severity(s.name())));
            } else if (o instanceof Hunk h) {
                HunkSeverity s = HunkSeverity.of(parentFile(value), h);
                String mem = members(h.members.get("ours") != null && !h.members.get("ours").isEmpty() ? h.members.get("ours") : h.members.get("theirs"));
                setText("<html><b>#" + h.index + "</b> " + esc(shorten(mem, 60)) + " <span" + gray + ">ours " + lines(h.ours) + "</span></html>");
                setToolTipText(h.suggestion == null ? h.why : "Suggested: " + com.branchdiff.conflict.Suggestions.label(h.suggestion.choice)
                        + " (" + h.suggestion.confidence.toLowerCase() + " confidence). " + h.suggestion.headline);
                setIcon(new Ui.Badge(s.name(), Ui.severity(s.name()), 58));
            } else if (o instanceof Item it) {
                setText(it.text());
                Color c = Ui.severity(it.badge());
                setIcon(c != null ? new Ui.Badge(it.badge(), c) : null);
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
