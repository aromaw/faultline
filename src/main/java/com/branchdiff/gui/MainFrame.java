package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;
import com.branchdiff.gui.GitQueries.Branch;
import com.branchdiff.gui.GitQueries.CommitRow;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The faultline window. The header walks through the four steps (1 project, 2 branches, 3 commits, 4 Analyze); the left
 * side lists the mainline commits and, after an analysis, the conflicts; the right side shows the selected hunk (or a
 * guide while there is none); the status bar shows progress, Cancel and the heap.
 */
final class MainFrame {
    final JFrame frame = new JFrame("faultline");
    private final Prefs prefs;
    private final JButton openButton = new JButton("Open Project\u2026", Ui.icon(Ui.Glyph.FOLDER));
    private final JLabel project = Ui.label("No project open", "large");
    private final JLabel projectPath = Ui.muted(" ");
    final BranchPicker target = new BranchPicker("target branch", b -> branchChanged());
    final BranchPicker mainline = new BranchPicker("mainline branch", b -> branchChanged());
    private final JLabel picked = Ui.label("No commits picked", "large");
    private final JLabel pickedHint = Ui.muted("check them in the list below");
    private final JButton analyze = new JButton("Analyze", Ui.icon(Ui.Glyph.PLAY, Color.WHITE));
    private final JButton cancel = new JButton("Cancel", Ui.icon(Ui.Glyph.STOP, Ui.RED));
    private final JButton export = new JButton("Export Report \u25BE", Ui.icon(Ui.Glyph.EXPORT));
    private final JButton theme = new JButton(Ui.icon(Ui.Glyph.THEME));
    private final JButton reload = new JButton("Reload", Ui.icon(Ui.Glyph.RELOAD));
    private final JCheckBox maven = new JCheckBox("Use Maven classpath");
    private final Ui.StepIcon[] steps = {new Ui.StepIcon(1), new Ui.StepIcon(2), new Ui.StepIcon(3), new Ui.StepIcon(4)};
    private final JLabel[] stepTitles = new JLabel[4];
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Open a git repository to start (" + shortcutText() + "O)");
    private final JLabel memory = new JLabel();
    final CommitsPanel commits = new CommitsPanel(this::updateActions);
    private ResultsPanel results;
    final JTabbedPane left = new JTabbedPane();
    private final JPanel commitsHolder = new JPanel(new CardLayout());
    private final JPanel right = new JPanel(new CardLayout());
    private final JPanel welcome = new JPanel(new BorderLayout());
    private final JPanel conflictsHolder = new JPanel(new BorderLayout());
    private GitRunner git;
    private Path repo;
    private boolean loadingBranches;
    /** in-flight background jobs (automation waits for 0) */
    final AtomicInteger busy = new AtomicInteger();
    private volatile SwingWorker<AnalysisJob.Result, String> running;
    private final AtomicBoolean cancelFlag = new AtomicBoolean();
    volatile String lastError;
    private boolean oomShown;
    private javax.swing.Icon logo;

    MainFrame(Prefs prefs) {
        this.prefs = prefs;
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        AppIcon.install(frame);
        frame.setJMenuBar(menuBar());
        frame.add(header(), BorderLayout.NORTH);

        left.putClientProperty("JTabbedPane.tabType", "underlined");
        left.putClientProperty("JTabbedPane.tabHeight", 34);
        commitsHolder.add(emptyState(Ui.Glyph.FOLDER, "No project open",
                "Open a git repository to list the mainline commits that are not on the target branch yet.",
                "Open Project\u2026", this::chooseProject), "empty");
        commitsHolder.add(commits, "commits");
        left.addTab("Commits", Ui.icon(Ui.Glyph.COMMIT), commitsHolder, "Mainline commits that are not on the target branch");
        showNoAnalysis();
        left.addTab("Conflicts", Ui.icon(Ui.Glyph.BRANCH), conflictsHolder, "Conflicted files and hunks of the last analysis");
        left.addChangeListener(e -> showRight());

        right.add(welcome, "welcome"); // filled by updateActions()
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.4);
        split.setDividerLocation(600);
        split.setBorder(null);
        frame.add(split, BorderLayout.CENTER);
        frame.add(statusBar(), BorderLayout.SOUTH);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(new Dimension(Math.min(1500, screen.width * 9 / 10), Math.min(940, screen.height * 9 / 10)));
        frame.setLocationByPlatform(true);
        updateActions();
        new Timer(2000, e -> updateMemory()).start();
        updateMemory();
        String hint = MemoryAdvice.smallHeapHint(Runtime.getRuntime().maxMemory());
        if (hint != null) status(hint);
    }

    // ------------------------------------------------------------------ layout

    private JMenuBar menuBar() {
        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open Project\u2026", Ui.icon(Ui.Glyph.FOLDER));
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, mod));
        open.addActionListener(e -> chooseProject());
        file.add(open);
        JMenu recent = new JMenu("Recent Projects");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                recent.removeAll();
                for (String p : prefs.recentProjects()) {
                    JMenuItem it = new JMenuItem(p);
                    it.addActionListener(a -> openProject(Path.of(p)));
                    recent.add(it);
                }
                if (recent.getItemCount() == 0) recent.add(new JMenuItem("(none)")).setEnabled(false);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        file.add(recent);
        file.addSeparator();
        for (String f : List.of("html", "md", "json")) {
            JMenuItem it = new JMenuItem("Export " + f.toUpperCase() + " Report\u2026");
            it.addActionListener(e -> exportReport(f));
            file.add(it);
        }
        mb.add(file);
        JMenu branches = new JMenu("Branches");
        JMenuItem tg = new JMenuItem("Choose Target Branch\u2026");
        tg.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, mod));
        tg.addActionListener(e -> target.open(""));
        branches.add(tg);
        JMenuItem mlItem = new JMenuItem("Choose Mainline Branch\u2026");
        mlItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, mod));
        mlItem.addActionListener(e -> mainline.open(""));
        branches.add(mlItem);
        JMenuItem rl = new JMenuItem("Reload Branches and Commits", Ui.icon(Ui.Glyph.RELOAD));
        rl.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, mod));
        rl.addActionListener(e -> reloadProject());
        branches.add(rl);
        mb.add(branches);
        JMenu run = new JMenu("Analyze");
        JMenuItem a = new JMenuItem("Analyze Picked Commits", Ui.icon(Ui.Glyph.PLAY, Ui.GREEN));
        a.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mod));
        a.addActionListener(e -> analyze());
        run.add(a);
        JMenuItem c = new JMenuItem("Cancel Analysis", Ui.icon(Ui.Glyph.STOP, Ui.RED));
        c.addActionListener(e -> cancel());
        run.add(c);
        mb.add(run);
        JMenu view = new JMenu("View");
        JMenuItem t = new JMenuItem("Toggle Light / Dark Theme", Ui.icon(Ui.Glyph.THEME));
        t.addActionListener(e -> toggleTheme());
        view.add(t);
        JMenuItem find = new JMenuItem("Filter Commits", Ui.icon(Ui.Glyph.SEARCH));
        find.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, mod));
        find.addActionListener(e -> {
            left.setSelectedIndex(0);
            commits.filter.requestFocusInWindow();
        });
        view.add(find);
        mb.add(view);
        return mb;
    }

    /** the step header: 1 project, 2 branches, 3 commits, 4 Analyze, then Export and Theme on the right */
    private JComponent header() {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14)));
            }
        };
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.VERTICAL;

        openButton.setToolTipText("Open a git repository (" + shortcutText() + "O)");
        openButton.addActionListener(e -> chooseProject());
        project.setFont(project.getFont().deriveFont(Font.BOLD));
        JPanel s1 = row(openButton, stack(project, projectPath));

        target.setToolTipText("<html><b>Target (tenant) branch</b>: the branch the commits are cherry-picked onto (<code>--onto</code>).<br>"
                + "Click or start typing to search local and remote branches (" + shortcutText() + "T)</html>");
        mainline.setToolTipText("<html><b>Mainline branch</b>: where the commits come from; the fork point is merge-base(target, mainline).<br>"
                + "Click or start typing to search local and remote branches (" + shortcutText() + "M)</html>");
        JPanel s2 = row(labelled("Pick onto (target)", target), arrow(), labelled("Commits from (mainline)", mainline));

        JPanel s3 = row(stack(picked, pickedHint));

        styleAnalyze();
        analyze.addPropertyChangeListener("enabled", e -> styleAnalyze());
        analyze.setToolTipText("<html>Simulate the cherry-pick offline (git merge-tree) and explain every conflict (" + shortcutText()
                + "Enter).<br>The worktree, index and refs are not touched.</html>");
        analyze.addActionListener(e -> analyze());
        maven.setToolTipText("Also resolve both Maven classpaths (mvn dependency:build-classpath): slower, fewer UNCERTAIN items");
        Ui.style(maven, "small");
        JPanel s4 = row(stack(analyze, maven));

        JComponent[] contents = {s1, s2, s3, s4};
        String[] titles = {"Project", "Branches", "Commits", "Analyze"};
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                c.gridx = 2 * i - 1;
                c.insets = new Insets(0, 10, 0, 10);
                // a chevron between the steps instead of a separator bar: the header reads as one flow
                JLabel chevron = Ui.muted("\u203A");
                chevron.setFont(chevron.getFont().deriveFont(20f));
                p.add(chevron, c);
            }
            c.gridx = 2 * i;
            c.insets = new Insets(0, 0, 0, 0);
            JPanel step = new JPanel(new BorderLayout(0, 4));
            stepTitles[i] = new JLabel(titles[i]);
            stepTitles[i].setIcon(steps[i]);
            stepTitles[i].setIconTextGap(6);
            stepTitles[i].setFont(stepTitles[i].getFont().deriveFont(Font.BOLD));
            step.add(stepTitles[i], BorderLayout.NORTH);
            step.add(contents[i], BorderLayout.CENTER);
            p.add(step, c);
        }
        c.gridx = 8;
        c.weightx = 1;
        p.add(Box.createHorizontalGlue(), c);
        c.weightx = 0;
        c.gridx = 9;
        c.anchor = GridBagConstraints.NORTHEAST;
        c.fill = GridBagConstraints.NONE;
        export.setToolTipText("Write the HTML, Markdown or JSON report of the last analysis");
        export.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            for (String f : List.of("html", "md", "json")) {
                JMenuItem it = new JMenuItem((f.equals("md") ? "Markdown" : f.toUpperCase()) + " report\u2026");
                it.addActionListener(a -> exportReport(f));
                m.add(it);
            }
            m.show(export, 0, export.getHeight());
        });
        theme.addActionListener(e -> toggleTheme());
        updateThemeButton();
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.add(export);
        tools.add(theme);
        p.add(tools, c);
        return p;
    }

    /** the primary button: accent filled while enabled, the theme's normal disabled look otherwise (readable) */
    private void styleAnalyze() {
        String base = "font: bold +1; arc: 10; margin: 6,16,6,18; iconTextGap: 8;";
        analyze.putClientProperty("FlatLaf.style", analyze.isEnabled() ? base
                + "background: #3D7BF7; foreground: #FFFFFF; hoverBackground: #346BDB; pressedBackground: #2D5EC2;"
                + "borderColor: #2F67D8; focusedBorderColor: #9DBBFB" : base);
        analyze.setIcon(Ui.icon(Ui.Glyph.PLAY, analyze.isEnabled() ? Color.WHITE : null));
    }

    /**
     * Components side by side, top-aligned, never wrapping: a FlowLayout wraps when the window is narrower than the
     * header, which pushed the project path below the header's bottom edge.
     */
    private static JPanel row(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(8));
            cs[i].setAlignmentY(0f);
            p.add(cs[i]);
        }
        return p;
    }

    private static JPanel stack(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent x : cs) {
            x.setAlignmentX(0f);
            p.add(x);
        }
        return p;
    }

    private static JComponent labelled(String caption, JComponent c) {
        return stack(Ui.style(Ui.muted(caption), "small"), c);
    }

    private static JComponent arrow() {
        JLabel a = Ui.heading("\u2190", 3);
        a.setForeground(Ui.muted());
        a.setToolTipText("commits from the mainline are picked onto the target");
        a.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        return a;
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Component.borderColor")), BorderFactory.createEmptyBorder(3, 10, 3, 8)));
            }
        };
        p.add(status, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        progress.setPreferredSize(new Dimension(180, 14));
        progress.setVisible(false);
        east.add(progress);
        Ui.style(cancel, "small");
        cancel.setToolTipText("Stop the running analysis (it stops at the next phase)");
        cancel.addActionListener(e -> cancel());
        cancel.setVisible(false);
        east.add(cancel);
        memory.setToolTipText("<html>Java heap in use after the last garbage collection / the heap limit.<br>"
                + "The process may hold more until the collector gives it back. Click to collect now.<br>"
                + "Raise the limit with FAULTLINE_XMX=6g.</html>");
        memory.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                System.gc();
                updateMemory();
            }
        });
        east.add(memory);
        p.add(east, BorderLayout.EAST);
        return p;
    }

    /** an empty-state panel: icon, title, one explaining line and an optional action button */
    static JComponent emptyState(Ui.Glyph glyph, String title, String text, String action, Runnable onAction) {
        JPanel box = new JPanel(new GridBagLayout());
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        JLabel icon = new JLabel(new ScaledIcon(Ui.icon(glyph, null), 3));
        icon.setForeground(Ui.muted());
        JLabel t = Ui.heading(title, 3);
        // a wrapping text area, not an HTML label: HTML would load the Swing HTML kit on the start-up path
        JTextArea d = new JTextArea(text, 0, 30) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Ui.muted());
                setFont(UIManager.getFont("Label.font"));
            }
        };
        d.setEditable(false);
        d.setFocusable(false);
        d.setOpaque(false);
        d.setLineWrap(true);
        d.setWrapStyleWord(true);
        d.setMaximumSize(new Dimension(380, 200));
        for (JComponent x : new JComponent[] {icon, t, d}) {
            x.setAlignmentX(0.5f);
            inner.add(x);
            inner.add(Box.createVerticalStrut(8));
        }
        if (action != null) {
            JButton b = new JButton(action);
            b.setAlignmentX(0.5f);
            b.addActionListener(e -> onAction.run());
            inner.add(Box.createVerticalStrut(6));
            inner.add(b);
        }
        box.add(inner);
        return box;
    }

    /** a line icon drawn bigger, in the muted color (empty states) */
    private record ScaledIcon(javax.swing.Icon base, int factor) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.translate(x, y);
            g2.scale(factor, factor);
            JLabel tint = new JLabel();
            tint.setEnabled(false);
            base.paintIcon(tint, g2, 0, 0);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return base.getIconWidth() * factor;
        }

        @Override
        public int getIconHeight() {
            return base.getIconHeight() * factor;
        }
    }

    private void showNoAnalysis() {
        conflictsHolder.removeAll();
        conflictsHolder.add(emptyState(Ui.Glyph.PLAY, "No analysis yet",
                "Pick the mainline commits in the Commits tab and press Analyze. The conflicted files and hunks appear here.",
                null, null), BorderLayout.CENTER);
        conflictsHolder.revalidate();
        conflictsHolder.repaint();
    }

    /** the right side while no hunk is shown: what to do next, with the steps done so far checked */
    private void buildWelcome() {
        welcome.removeAll();
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(36, 44, 36, 44));
        if (logo == null) {
            java.awt.Image img = AppIcon.load(64);
            if (img != null) logo = new javax.swing.ImageIcon(img); // waits for the (small) image
        }
        JLabel title = Ui.heading("faultline", 12);
        title.setIcon(logo);
        title.setIconTextGap(14);
        box.add(title);
        box.add(Box.createVerticalStrut(6));
        box.add(Ui.style(Ui.muted("Explains why cherry-picking mainline commits onto a tenant branch conflicts, and what each side needs."), "large"));
        box.add(Box.createVerticalStrut(24));
        int n = commits.chosen().size();
        String[][] s = {
                {"Open a git repository", git == null ? "File \u203A Open Project\u2026 or a recent project below" : "Opened " + repo},
                {"Choose the target and the mainline branch", "click a branch button and type to search, e.g. \u201Corigin dev\u201D"},
                {"Check the mainline commits to pick", n == 0 ? "click the Pick box, or select rows and press Space" : n + (n == 1 ? " commit" : " commits") + " picked, applied oldest first"},
                {"Analyze", "an offline simulation with git merge-tree: the worktree, index and refs are not touched"}};
        boolean[] done = stepsDone();
        for (int i = 0; i < 4; i++) {
            Ui.StepIcon si = new Ui.StepIcon(i + 1);
            si.done = done[i];
            si.active = !done[i] && (i == 0 || done[i - 1]);
            JLabel t = Ui.label(s[i][0], "large");
            t.setIcon(si);
            t.setIconTextGap(12);
            if (si.active) t.setFont(t.getFont().deriveFont(Font.BOLD));
            box.add(t);
            JLabel d = Ui.muted(s[i][1]);
            d.setBorder(BorderFactory.createEmptyBorder(2, 32, 14, 0));
            box.add(d);
        }
        List<String> rec = prefs.recentProjects();
        if (!rec.isEmpty() && git == null) {
            box.add(Box.createVerticalStrut(10));
            box.add(Ui.heading("Recent projects", 0));
            box.add(Box.createVerticalStrut(4));
            for (String p : rec) {
                JButton b = new JButton(p, Ui.icon(Ui.Glyph.FOLDER));
                b.putClientProperty("JButton.buttonType", "borderless");
                b.setToolTipText("Open " + p);
                b.addActionListener(e -> openProject(Path.of(p)));
                box.add(b);
            }
        }
        for (java.awt.Component x : box.getComponents()) ((JComponent) x).setAlignmentX(0f);
        JScrollPane sp = new JScrollPane(box);
        sp.setBorder(null);
        welcome.add(sp, BorderLayout.CENTER);
        welcome.revalidate();
        welcome.repaint();
    }

    private boolean[] stepsDone() {
        boolean p = git != null;
        boolean b = p && target.selected() != null && mainline.selected() != null && !target.selected().equals(mainline.selected());
        boolean c = b && !commits.chosen().isEmpty();
        boolean a = c && results != null && results.result() != null;
        return new boolean[] {p, b, c, a};
    }

    private void showRight() {
        CardLayout cl = (CardLayout) right.getLayout();
        cl.show(right, results != null && results.result() != null ? "results" : "welcome");
    }

    void reloadProject() {
        if (repo != null) openProject(repo);
    }

    // ------------------------------------------------------------------ project / branches / commits

    void chooseProject() {
        JFileChooser fc = new JFileChooser(repo != null ? repo.toFile() : new File(System.getProperty("user.home")));
        fc.setDialogTitle("Open git repository");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) openProject(fc.getSelectedFile().toPath());
    }

    void openProject(Path dir) {
        status("Opening " + dir + " ...");
        background(new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                GitRunner g = new GitRunner(dir.toAbsolutePath().normalize());
                String top = g.run("rev-parse", "--show-toplevel").trim();
                GitRunner root = new GitRunner(Path.of(top));
                List<Branch> bs = GitQueries.branches(root);
                return new Object[] {root, bs, GitQueries.currentBranch(root)};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                try {
                    Object[] r = get();
                    git = (GitRunner) r[0];
                    repo = git.repo();
                    List<Branch> bs = (List<Branch>) r[1];
                    String repoKey = repo.toString();
                    prefs.addRecent(repoKey);
                    project.setText(repo.getFileName() != null ? repo.getFileName().toString() : repoKey);
                    project.setToolTipText(repoKey);
                    projectPath.setText(shortPath(repoKey));
                    openButton.setText("Change…");
                    openButton.setToolTipText("Open another git repository (" + shortcutText() + "O)");
                    frame.setTitle("faultline - " + repoKey);
                    String ml = pickExisting(bs, prefs.branch(repoKey, "mainline"), GitQueries.guessMainline(bs));
                    String tg = pickExisting(bs, prefs.branch(repoKey, "target"), GitQueries.guessTarget(bs, (String) r[2], ml));
                    loadingBranches = true;
                    target.setBranches(bs);
                    mainline.setBranches(bs);
                    target.setSelected(tg);
                    mainline.setSelected(ml);
                    loadingBranches = false;
                    ((CardLayout) commitsHolder.getLayout()).show(commitsHolder, "commits");
                    updateActions();
                    status(bs.size() + " branches in " + repoKey);
                    loadCommits();
                } catch (Exception e) {
                    error("Not a git repository: " + dir, e);
                }
            }
        });
    }

    private static String pickExisting(List<Branch> bs, String preferred, String fallback) {
        if (preferred != null) for (Branch b : bs) if (b.name().equals(preferred)) return preferred;
        return fallback;
    }

    void setBranches(String tg, String ml) {
        loadingBranches = true;
        if (tg != null) target.setSelected(tg);
        if (ml != null) mainline.setSelected(ml);
        loadingBranches = false;
        branchChanged();
    }

    private void branchChanged() {
        if (loadingBranches || git == null) return;
        prefs.setBranch(repo.toString(), "target", target.selected());
        prefs.setBranch(repo.toString(), "mainline", mainline.selected());
        loadCommits();
    }

    private static final int MAX_COMMITS = 5000;

    void loadCommits() {
        String tg = target.selected(), ml = mainline.selected();
        if (git == null || tg == null || ml == null) return;
        GitRunner g = git;
        status("Reading git log " + tg + ".." + ml + " ...");
        background(new SwingWorker<List<CommitRow>, Void>() {
            @Override
            protected List<CommitRow> doInBackground() {
                return GitQueries.missingCommits(g, tg, ml, MAX_COMMITS);
            }

            @Override
            protected void done() {
                try {
                    List<CommitRow> rows = get();
                    commits.setCommits(g, rows, tg, ml, rows.size() >= MAX_COMMITS);
                    status(rows.size() + " mainline commits are not in " + tg);
                    left.setSelectedIndex(0);
                } catch (Exception e) {
                    error("git log " + tg + ".." + ml + " failed", e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ analysis

    void analyze() {
        if (git == null) {
            chooseProject();
            return;
        }
        if (running != null) return;
        List<CommitRow> picks = commits.chosen();
        if (picks.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Check the Pick box of the mainline commits to analyze first\n"
                    + "(or select rows and press Space).", "faultline", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String tg = target.selected(), ml = mainline.selected();
        String spec = PickOrder.pickSpec(picks);
        boolean useMaven = maven.isSelected();
        GitRunner g = git;
        cancelFlag.set(false);
        if (results != null) results.setResult(null); // let the previous result be collected first
        if (results != null) showNoAnalysisYet();
        progress.setIndeterminate(true);
        progress.setStringPainted(true);
        progress.setString("starting");
        progress.setVisible(true);
        cancel.setVisible(true);
        status("Analyzing " + picks.size() + " commit(s) onto " + tg + " ...");
        running = new SwingWorker<AnalysisJob.Result, String>() {
            @Override
            protected AnalysisJob.Result doInBackground() {
                try {
                    return AnalysisJob.run(g.repo(), tg, ml, spec, useMaven, this::publishPhase, cancelFlag::get);
                } catch (OutOfMemoryError e) {
                    releaseMemory(); // before anything else allocates: the caches hold most of what is left
                    throw e;
                }
            }

            void publishPhase(String s) {
                publish(s);
            }

            @Override
            protected void process(List<String> chunks) {
                if (cancelFlag.get()) return;
                String phase = chunks.get(chunks.size() - 1);
                progress.setString(phase);
                status("Analyzing: " + phase + " …");
            }

            @Override
            protected void done() {
                running = null;
                progress.setVisible(false);
                cancel.setVisible(false);
                try {
                    AnalysisJob.Result r = get();
                    showResult(r);
                    status("Analysis finished in " + r.millis() + " ms: " + r.report().conflicts.summary.get("conflictHunks")
                            + " conflict hunk(s) in " + r.report().conflicts.files.size() + " file(s)");
                } catch (CancellationException e) {
                    status("Analysis cancelled");
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof CancellationException) status("Analysis cancelled");
                    else if (e.getCause() instanceof OutOfMemoryError) outOfMemory();
                    else error("Analysis failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                updateActions();
                busy.decrementAndGet();
                // the analysis' ASTs are garbage now; collect them so the heap (and RSS) shrinks back
                Timer t = new Timer(300, ev -> new Thread(System::gc, "faultline-gc").start());
                t.setRepeats(false);
                t.start();
            }
        };
        busy.incrementAndGet();
        running.execute();
        updateActions();
    }

    void cancel() {
        if (running == null) return;
        cancelFlag.set(true);
        status("Cancelling (stops at the next analysis phase) ...");
        cancel.setEnabled(false);
    }

    private void showResult(AnalysisJob.Result r) {
        if (results == null) {
            results = new ResultsPanel(prefs);
            right.add(results.detail, "results");
        }
        results.setResult(r);
        conflictsHolder.removeAll();
        int hunks = r.report().conflicts.files.stream().mapToInt(f -> f.hunks.size()).sum();
        if (r.report().conflicts.files.isEmpty()) {
            conflictsHolder.add(emptyState(Ui.Glyph.CHECK, "No conflicts",
                    "The picked commits apply cleanly onto " + target.selected() + ". Export the report to review what the picks depend on.",
                    null, null), BorderLayout.CENTER);
        } else {
            JScrollPane sp = new JScrollPane(results.tree);
            sp.setBorder(null);
            conflictsHolder.add(sp, BorderLayout.CENTER);
        }
        left.setTitleAt(1, "Conflicts (" + hunks + ")");
        left.setSelectedIndex(1);
        showRight();
        results.selectHunk(null);
        conflictsHolder.revalidate();
        conflictsHolder.repaint();
    }

    /** forget the shown result (before a new analysis or after running out of memory) */
    private void showNoAnalysisYet() {
        results.setResult(null);
        left.setTitleAt(1, "Conflicts");
        showNoAnalysis();
        showRight();
    }

    /** drops every cache an analysis may have filled, so the heap is free again */
    static void releaseMemory() {
        com.branchdiff.index.AstCache.clear();
        com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();
    }

    /** after an OutOfMemoryError: free everything, tell the user how to raise the limit, stay usable */
    void outOfMemory() {
        cancelFlag.set(true); // an analysis still running stops at its next phase
        if (running == null) {
            progress.setVisible(false);
            cancel.setVisible(false);
        }
        if (results != null) showNoAnalysisYet();
        releaseMemory();
        System.gc();
        long max = Runtime.getRuntime().maxMemory();
        String msg = MemoryAdvice.outOfMemory(max);
        lastError = "out of memory (heap limit " + MemoryAdvice.limitText(max) + ")";
        status("Out of memory: the analysis was stopped. Start faultline with FAULTLINE_XMX=" + MemoryAdvice.suggestedXmx(max) + " for more heap.");
        updateActions();
        updateMemory();
        if (GuiMain.automated) {
            System.err.println("[faultline gui] " + msg);
            return;
        }
        if (oomShown) return;
        oomShown = true;
        JTextArea text = new JTextArea(msg);
        text.setEditable(false);
        text.setOpaque(false);
        text.setFont(UIManager.getFont("Label.font"));
        JOptionPane.showMessageDialog(frame, text, "faultline: out of memory", JOptionPane.WARNING_MESSAGE);
        oomShown = false;
    }

    ResultsPanel results() {
        return results;
    }

    void exportReport(String format) {
        if (results == null || results.result() == null) {
            JOptionPane.showMessageDialog(frame, "Run an analysis first.", "faultline", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(repo != null ? repo.toFile() : null);
        fc.setSelectedFile(new File("faultline-report." + format));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        if (Files.exists(out) && JOptionPane.showConfirmDialog(frame, out + " exists. Overwrite?", "faultline",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        exportTo(format, out);
    }

    void exportTo(String format, Path out) {
        AnalysisJob.Result r = results.result();
        status("Writing " + out + " ...");
        background(new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                AnalysisJob.export(r, format, out);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    status("Report written to " + out);
                } catch (Exception e) {
                    error("Export failed", e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void background(SwingWorker<?, ?> w) {
        busy.incrementAndGet();
        w.addPropertyChangeListener(e -> {
            if ("state".equals(e.getPropertyName()) && e.getNewValue() == SwingWorker.StateValue.DONE) {
                SwingUtilities.invokeLater(busy::decrementAndGet);
            }
        });
        w.execute();
    }

    private void updateActions() {
        boolean idle = running == null;
        int n = commits.chosen().size();
        analyze.setEnabled(idle && git != null && n > 0);
        cancel.setEnabled(!idle);
        export.setEnabled(idle && results != null && results.result() != null);
        reload.setEnabled(idle && git != null);
        analyze.setText(!idle ? "Analyzing…" : n > 0 ? "Analyze " + n + (n == 1 ? " commit" : " commits") : "Analyze");
        picked.setText(n == 0 ? "No commits picked" : n + (n == 1 ? " commit picked" : " commits picked"));
        pickedHint.setText(git == null ? "open a project first" : n == 0 ? "check them in the list below" : "applied oldest first");
        boolean[] done = stepsDone();
        for (int i = 0; i < 4; i++) {
            steps[i].done = done[i];
            steps[i].active = !done[i] && (i == 0 || done[i - 1]);
            if (stepTitles[i] != null) {
                stepTitles[i].setForeground(steps[i].active || done[i] ? Ui.fg() : Ui.muted());
                stepTitles[i].repaint();
            }
        }
        if (results == null || results.result() == null) buildWelcome();
    }

    static String shortPath(String p) {
        String home = System.getProperty("user.home");
        if (home != null && p.startsWith(home)) p = "~" + p.substring(home.length());
        return p.length() > 30 ? "…" + p.substring(p.length() - 29) : p;
    }

    private void updateMemory() {
        long max = Runtime.getRuntime().maxMemory();
        long live = MemoryAdvice.liveHeapBytes();
        memory.setText("Memory " + MemoryAdvice.limitText(live) + " of " + MemoryAdvice.limitText(max));
        memory.setForeground(live > max * 0.85 ? Ui.ORANGE : Ui.muted());
    }

    void toggleTheme() {
        boolean dark = !prefs.dark();
        prefs.setDark(dark);
        applyLaf(dark);
        FlatLaf.updateUI();
        CodeViews.dark = dark;
        CodeViews.retheme();
        if (results != null) results.refreshTheme();
        updateThemeButton();
        updateActions();
    }

    /**
     * FlatLaf's macOS-style themes (on every platform) with one accent colour and the shared modern defaults of
     * {@link Ui#modernDefaults}: rounded controls, thin overlay-style scroll bars, tables without grid lines.
     */
    static void applyLaf(boolean dark) {
        FlatLaf.setGlobalExtraDefaults(java.util.Map.of("@accentColor", Ui.hex(Ui.ACCENT)));
        if (dark) FlatMacDarkLaf.setup();
        else FlatMacLightLaf.setup();
        Ui.modernDefaults(dark);
        CodeViews.dark = dark;
    }

    private void updateThemeButton() {
        // a short label: "Light Theme" / "Dark Theme" made the header wider than a 1440 px laptop screen
        theme.setText(prefs.dark() ? "Light" : "Dark");
        theme.setToolTipText("Switch to the " + (prefs.dark() ? "light" : "dark") + " theme (View › Toggle Light / Dark Theme)");
    }

    private void status(String s) {
        status.setText(s);
    }

    private void error(String what, Throwable e) {
        if (e instanceof OutOfMemoryError) {
            outOfMemory();
            return;
        }
        String msg = what + (e != null && e.getMessage() != null ? ": " + e.getMessage() : "");
        lastError = msg;
        status(msg);
        if (!GuiMain.automated) JOptionPane.showMessageDialog(frame, msg, "faultline", JOptionPane.ERROR_MESSAGE);
        else System.err.println("[faultline gui] " + msg);
    }

    private static String shortcutText() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? "\u2318" : "Ctrl+";
    }

    boolean isAnalyzing() {
        return running != null;
    }
}
