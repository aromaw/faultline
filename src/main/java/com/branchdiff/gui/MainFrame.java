package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;
import com.branchdiff.gui.GitQueries.Branch;
import com.branchdiff.gui.GitQueries.CommitRow;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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

/** The faultline window: toolbar (project, target, mainline, Analyze), commit list, conflict tree and hunk detail. */
final class MainFrame {
    final JFrame frame = new JFrame("faultline");
    private final Prefs prefs;
    private final JLabel project = new JLabel("No project");
    private final JComboBox<String> target = new JComboBox<>();
    private final JComboBox<String> mainline = new JComboBox<>();
    private final JButton analyze = new JButton("Analyze");
    private final JButton cancel = new JButton("Cancel");
    private final JButton export = new JButton("Export");
    private final JButton theme = new JButton();
    private final JCheckBox maven = new JCheckBox("Maven classpath");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Open a git repository to start (File > Open, " + shortcutText() + "O)");
    private final JLabel memory = new JLabel();
    final CommitsPanel commits = new CommitsPanel(this::updateActions);
    private ResultsPanel results;
    final JTabbedPane left = new JTabbedPane();
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

    MainFrame(Prefs prefs) {
        this.prefs = prefs;
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setJMenuBar(menuBar());
        frame.add(toolbar(), BorderLayout.NORTH);

        left.putClientProperty("JTabbedPane.tabType", "underlined");
        left.addTab("Commits", commits);
        conflictsHolder.add(new JLabel("  Run Analyze to see the conflicted files and hunks"), BorderLayout.NORTH);
        left.addTab("Conflicts", conflictsHolder);
        left.addChangeListener(e -> showRight());

        buildWelcome();
        right.add(welcome, "welcome");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.38);
        split.setDividerLocation(560);
        split.setBorder(null);
        frame.add(split, BorderLayout.CENTER);
        frame.add(statusBar(), BorderLayout.SOUTH);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(new Dimension(Math.min(1500, screen.width * 9 / 10), Math.min(940, screen.height * 9 / 10)));
        frame.setLocationByPlatform(true);
        updateActions();
        new Timer(2000, e -> updateMemory()).start();
        updateMemory();
        target.addActionListener(e -> branchChanged());
        mainline.addActionListener(e -> branchChanged());
    }

    // ------------------------------------------------------------------ layout

    private JMenuBar menuBar() {
        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open Project...");
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
            JMenuItem it = new JMenuItem("Export " + f.toUpperCase() + " Report...");
            it.addActionListener(e -> exportReport(f));
            file.add(it);
        }
        mb.add(file);
        JMenu run = new JMenu("Analyze");
        JMenuItem a = new JMenuItem("Analyze Selected Commits");
        a.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mod));
        a.addActionListener(e -> analyze());
        run.add(a);
        JMenuItem c = new JMenuItem("Cancel Analysis");
        c.addActionListener(e -> cancel());
        run.add(c);
        JMenuItem reload = new JMenuItem("Reload Branches and Commits");
        reload.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, mod));
        reload.addActionListener(e -> {
            if (repo != null) openProject(repo);
        });
        run.add(reload);
        mb.add(run);
        JMenu view = new JMenu("View");
        JMenuItem t = new JMenuItem("Toggle Light / Dark Theme");
        t.addActionListener(e -> toggleTheme());
        view.add(t);
        JMenuItem find = new JMenuItem("Filter Commits");
        find.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, mod));
        find.addActionListener(e -> {
            left.setSelectedIndex(0);
            commits.filter.requestFocusInWindow();
        });
        view.add(find);
        mb.add(view);
        return mb;
    }

    private JComponent toolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JButton open = new JButton("Open...");
        open.setToolTipText("Open a git repository (" + shortcutText() + "O)");
        open.addActionListener(e -> chooseProject());
        tb.add(open);
        tb.add(Box.createHorizontalStrut(8));
        project.setFont(project.getFont().deriveFont(Font.BOLD));
        tb.add(project);
        tb.addSeparator(new Dimension(18, 20));
        tb.add(new JLabel("Target (tenant): "));
        target.setPrototypeDisplayValue("origin/feature/some-long-branch");
        target.setToolTipText("the branch the commits are cherry-picked onto (--onto)");
        tb.add(target);
        tb.add(Box.createHorizontalStrut(10));
        tb.add(new JLabel("Mainline: "));
        mainline.setPrototypeDisplayValue("origin/feature/some-long-branch");
        mainline.setToolTipText("the branch the commits come from; the fork point is merge-base(target, mainline)");
        tb.add(mainline);
        tb.addSeparator(new Dimension(18, 20));
        maven.setToolTipText("resolve both Maven classpaths (mvn dependency:build-classpath): slower, fewer UNCERTAIN items");
        tb.add(maven);
        tb.add(Box.createHorizontalStrut(6));
        analyze.putClientProperty("JButton.buttonType", "default");
        analyze.setToolTipText("Simulate the cherry-pick offline (git merge-tree) and explain each conflict (" + shortcutText() + "Enter)");
        analyze.addActionListener(e -> analyze());
        tb.add(analyze);
        cancel.addActionListener(e -> cancel());
        tb.add(cancel);
        tb.add(Box.createHorizontalGlue());
        export.setToolTipText("Write the HTML / Markdown / JSON report of the last analysis");
        export.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            for (String f : List.of("html", "md", "json")) {
                JMenuItem it = new JMenuItem(f.toUpperCase() + " report...");
                it.addActionListener(a -> exportReport(f));
                m.add(it);
            }
            m.show(export, 0, export.getHeight());
        });
        tb.add(export);
        tb.add(Box.createHorizontalStrut(6));
        theme.addActionListener(e -> toggleTheme());
        updateThemeButton();
        tb.add(theme);
        return tb;
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                javax.swing.UIManager.getColor("Component.borderColor")), BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        p.add(status, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        progress.setPreferredSize(new Dimension(160, 12));
        progress.setVisible(false);
        east.add(progress);
        memory.setToolTipText("Java heap used / max. Click to run the garbage collector.");
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

    private void buildWelcome() {
        welcome.removeAll();
        JPanel box = new JPanel();
        box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));
        JLabel title = new JLabel("faultline");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        box.add(title);
        box.add(new JLabel("Explains cherry-pick conflicts between a tenant branch and mainline commits."));
        box.add(Box.createVerticalStrut(12));
        box.add(new JLabel("1. Open a git repository.   2. Pick the target (tenant) and mainline branches."));
        box.add(new JLabel("3. Check or select the mainline commits to pick (they are applied oldest first)."));
        box.add(new JLabel("4. Analyze: an offline simulation with git merge-tree; the worktree, index and refs are not touched."));
        box.add(Box.createVerticalStrut(18));
        List<String> rec = prefs.recentProjects();
        if (!rec.isEmpty()) {
            JLabel r = new JLabel("Recent projects");
            r.setFont(r.getFont().deriveFont(Font.BOLD));
            box.add(r);
            for (String p : rec) {
                JButton b = new JButton(p);
                b.putClientProperty("JButton.buttonType", "borderless");
                b.addActionListener(e -> openProject(Path.of(p)));
                box.add(b);
            }
        }
        welcome.add(new JScrollPane(box) {
            {
                setBorder(null);
            }
        }, BorderLayout.CENTER);
        welcome.revalidate();
    }

    private void showRight() {
        CardLayout cl = (CardLayout) right.getLayout();
        cl.show(right, results != null && results.result() != null ? "results" : "welcome");
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
                    buildWelcome();
                    project.setText(repo.getFileName() != null ? repo.getFileName().toString() : repoKey);
                    project.setToolTipText(repoKey);
                    frame.setTitle("faultline - " + repoKey);
                    String ml = pickExisting(bs, prefs.branch(repoKey, "mainline"), GitQueries.guessMainline(bs));
                    String tg = pickExisting(bs, prefs.branch(repoKey, "target"), GitQueries.guessTarget(bs, (String) r[2], ml));
                    loadingBranches = true;
                    DefaultComboBoxModel<String> tm = new DefaultComboBoxModel<>(), mm = new DefaultComboBoxModel<>();
                    for (Branch b : bs) {
                        tm.addElement(b.name());
                        mm.addElement(b.name());
                    }
                    target.setModel(tm);
                    mainline.setModel(mm);
                    target.setSelectedItem(tg);
                    mainline.setSelectedItem(ml);
                    loadingBranches = false;
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
        if (tg != null) target.setSelectedItem(tg);
        if (ml != null) mainline.setSelectedItem(ml);
        loadingBranches = false;
        branchChanged();
    }

    private void branchChanged() {
        if (loadingBranches || git == null) return;
        prefs.setBranch(repo.toString(), "target", (String) target.getSelectedItem());
        prefs.setBranch(repo.toString(), "mainline", (String) mainline.getSelectedItem());
        loadCommits();
    }

    private static final int MAX_COMMITS = 5000;

    void loadCommits() {
        String tg = (String) target.getSelectedItem(), ml = (String) mainline.getSelectedItem();
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
            JOptionPane.showMessageDialog(frame, "Check or select the mainline commits to pick first.", "faultline",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String tg = (String) target.getSelectedItem(), ml = (String) mainline.getSelectedItem();
        String spec = PickOrder.pickSpec(picks);
        boolean useMaven = maven.isSelected();
        GitRunner g = git;
        cancelFlag.set(false);
        if (results != null) results.setResult(null); // let the previous result be collected first
        progress.setIndeterminate(true);
        progress.setVisible(true);
        status("Analyzing " + picks.size() + " commit(s) onto " + tg + " ...");
        running = new SwingWorker<AnalysisJob.Result, String>() {
            @Override
            protected AnalysisJob.Result doInBackground() {
                return AnalysisJob.run(g.repo(), tg, ml, spec, useMaven, this::publishPhase, cancelFlag::get);
            }

            void publishPhase(String s) {
                publish(s);
            }

            @Override
            protected void process(List<String> chunks) {
                if (!cancelFlag.get()) status("Analyzing: " + chunks.get(chunks.size() - 1) + " ...");
            }

            @Override
            protected void done() {
                running = null;
                progress.setVisible(false);
                try {
                    AnalysisJob.Result r = get();
                    showResult(r);
                    status("Analysis finished in " + r.millis() + " ms: " + r.report().conflicts.summary.get("conflictHunks")
                            + " conflict hunk(s) in " + r.report().conflicts.files.size() + " file(s)");
                } catch (CancellationException e) {
                    status("Analysis cancelled");
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof CancellationException) status("Analysis cancelled");
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
            results = new ResultsPanel();
            conflictsHolder.removeAll();
            conflictsHolder.add(new JScrollPane(results.tree), BorderLayout.CENTER);
            right.add(results.detail, "results");
        }
        results.setResult(r);
        left.setSelectedIndex(1);
        showRight();
        results.selectHunk(null);
        conflictsHolder.revalidate();
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
        analyze.setEnabled(idle && git != null);
        cancel.setEnabled(!idle);
        export.setEnabled(idle && results != null && results.result() != null);
        int n = commits.chosen().size();
        analyze.setText(n > 0 ? "Analyze " + n + (n == 1 ? " commit" : " commits") : "Analyze");
    }

    private void updateMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        memory.setText(used + " of " + (rt.maxMemory() >> 20) + "M");
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
    }

    static void applyLaf(boolean dark) {
        if (dark) FlatDarculaLaf.setup();
        else FlatIntelliJLaf.setup();
        CodeViews.dark = dark;
    }

    private void updateThemeButton() {
        theme.setText(prefs.dark() ? "Light theme" : "Dark theme");
    }

    private void status(String s) {
        status.setText(s);
    }

    private void error(String what, Throwable e) {
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
