package com.branchdiff.gui;

import com.branchdiff.git.GitRunner;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * {@code faultline gui [--repo <dir>] [--onto <target>] [--mainline <branch>] [--pick <c1,c2|A..B>] [--analyze]
 * [--hunk <id>] [--dark|--light]}: the desktop GUI. With {@code --repo} it opens that project directly; {@code --pick}
 * checks those commits and {@code --analyze} runs the analysis right away.
 * <p>
 * Automation (measurements, documentation screenshots): {@code --screenshots <dir>} saves PNGs of the window after
 * each step, {@code --measure} prints timings and RSS to stderr, {@code --exit} quits when the steps are done.
 */
public final class GuiMain {
    static volatile boolean automated;
    private static volatile long shownAtMs = -1;
    private static volatile long shownAtEpochMs = -1;

    private GuiMain() {
    }

    public static void main(String[] args) {
        Args a = Args.parse(args);
        automated = a.screenshots != null || a.measure || a.exit;
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "faultline");
        System.setProperty("apple.awt.application.appearance", "system");
        Prefs prefs = Prefs.load();
        if (a.dark != null) prefs.setDark(a.dark);
        MainFrame[] holder = new MainFrame[1];
        try {
            EventQueue.invokeAndWait(() -> {
                MainFrame.applyLaf(prefs.dark(), prefs.nerdFont());
                MainFrame m = new MainFrame(prefs);
                holder[0] = m;
                m.frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowOpened(WindowEvent e) {
                        // first paint has been scheduled; measure once it ran
                        SwingUtilities.invokeLater(() -> {
                            shownAtEpochMs = System.currentTimeMillis();
                            shownAtMs = 0;
                        });
                    }
                });
                m.frame.setVisible(true);
            });
        } catch (InterruptedException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
        MainFrame m = holder[0];
        installOomHandler(m);
        if (a.repo == null && !automated) return;
        Thread driver = new Thread(() -> drive(m, a), "faultline-gui-driver");
        driver.setDaemon(true);
        driver.start();
    }

    /** an OutOfMemoryError outside the analysis worker (e.g. while painting a huge file) gets the same advice */
    private static void installOomHandler(MainFrame m) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof OutOfMemoryError) {
                MainFrame.releaseMemory();
                SwingUtilities.invokeLater(m::outOfMemory);
            } else {
                java.nio.file.Path log = CrashLog.write(t, e);
                if (prev != null) prev.uncaughtException(t, e);
                else {
                    System.err.println("Exception in thread \"" + t.getName() + "\" " + e);
                    e.printStackTrace();
                }
                SwingUtilities.invokeLater(() -> m.unexpectedError(e, log));
            }
        });
    }

    private static void drive(MainFrame m, Args a) {
        try {
            waitFor(() -> shownAtMs >= 0, 30_000);
            if (a.measure) {
                // wall clock of the first paint; the caller subtracts its own launch time (ProcessHandle's start time
                // is only second-accurate on Linux), plus the JVM's own view of its start for reference
                measure("window-shown-epoch-ms", shownAtEpochMs);
                measure("window-shown-since-jvm-start-ms", shownAtEpochMs
                        - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
            }
            if (a.repo == null) return;
            String theme = Prefs.load().dark() ? "dark" : "light";
            settle();
            shot(m, a, "00-empty-" + theme);
            onEdt(() -> m.openProject(a.repo));
            idle(m);
            if (a.onto != null || a.mainline != null) {
                onEdt(() -> m.setBranches(a.onto, a.mainline));
                idle(m);
            }
            settle();
            shot(m, a, "01-project-opened-" + theme);
            if (a.branchSearch != null) {
                onEdt(() -> m.mainline.open(a.branchSearch));
                settle();
                shot(m, a, "01b-branch-search-" + theme);
                onEdt(m.mainline::close);
            }
            if (a.measure) {
                Thread.sleep(2000); // idle: project and commit list loaded, nothing forced
                measure("idle-rss-kb", rssKb("VmRSS"));
                measure("idle-heap-used-mb", usedHeapMb());
            }
            if (a.pick != null) {
                Set<String> shas = expand(new GitRunner(a.repo.toAbsolutePath().normalize()), a.pick);
                onEdt(() -> m.commits.check(shas));
                settle();
                shot(m, a, "02-commits-selected-" + theme);
            }
            if (a.analyze) {
                long hwmBefore = rssKb("VmHWM");
                long t0 = System.nanoTime();
                onEdt(m::analyze);
                waitFor(() -> !m.isAnalyzing() && m.busy.get() == 0, 600_000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (a.measure) {
                    measure("analysis-wall-ms", ms);
                    measure("peak-rss-kb", rssKb("VmHWM"));
                    measure("peak-rss-before-analysis-kb", hwmBefore);
                }
                if (m.lastError != null) {
                    System.err.println("[faultline gui] error: " + m.lastError);
                    settle();
                    shot(m, a, "03-error-" + theme);
                }
                if (m.results() != null && m.results().result() != null) {
                    onEdt(() -> m.results().selectHunk(a.hunk));
                    settle();
                    shot(m, a, "03-results-hunk-" + theme);
                    for (String tab : ResultsPanel.TABS) {
                        if (a.screenshots == null) break;
                        onEdt(() -> m.results().selectTab(tab, c -> selectFirstLocated(c)));
                        settle();
                        shot(m, a, "04-tab-" + tab.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + theme);
                    }
                    if (a.toggleTheme) {
                        onEdt(m::toggleTheme);
                        settle();
                        shot(m, a, "05-after-theme-toggle-" + (Prefs.load().dark() ? "dark" : "light"));
                    }
                    if (a.export != null) {
                        onEdt(() -> m.exportTo(a.export.toString().endsWith(".json") ? "json" : a.export.toString().endsWith(".md") ? "md" : "html", a.export));
                        idle(m);
                    }
                }
                if (a.measure) {
                    Thread.sleep(2000); // the GUI collects the analysis garbage 300 ms after it ends
                    measure("rss-after-analysis-kb", rssKb("VmRSS"));
                    measure("heap-used-after-analysis-mb", usedHeapMb());
                    System.gc();
                    measure("heap-used-after-gc-mb", usedHeapMb());
                }
            }
            if (a.thenRepo != null) second(m, a); // e.g. after an out-of-memory error: the window must still work
        } catch (Exception e) {
            System.err.println("[faultline gui] automation failed: " + e);
            e.printStackTrace();
        } finally {
            if (a.exit) {
                System.out.flush();
                System.err.flush();
                System.exit(0); // a normal exit, so -XX:ArchiveClassesAtExit can write the AppCDS archive
            }
        }
    }

    /** a second analysis in the same window (--then-repo, --then-pick, same branch names) */
    private static void second(MainFrame m, Args a) throws Exception {
        m.lastError = null;
        onEdt(() -> m.openProject(a.thenRepo));
        idle(m);
        if (a.onto != null || a.mainline != null) {
            onEdt(() -> m.setBranches(a.onto, a.mainline));
            idle(m);
        }
        Set<String> shas = expand(new GitRunner(a.thenRepo.toAbsolutePath().normalize()), a.thenPick);
        onEdt(() -> m.commits.check(shas));
        long t0 = System.nanoTime();
        onEdt(m::analyze);
        waitFor(() -> !m.isAnalyzing() && m.busy.get() == 0, 600_000);
        measure("second-analysis-wall-ms", (System.nanoTime() - t0) / 1_000_000);
        System.err.println("[faultline gui] second analysis: " + (m.lastError != null ? "error: " + m.lastError
                : m.results() != null && m.results().result() != null ? m.results().result().report().conflicts.files.size() + " conflicted file(s)" : "no result"));
        settle();
        shot(m, a, "06-second-analysis-" + (Prefs.load().dark() ? "dark" : "light"));
    }

    /** selects the first row / tree node with a location so the source view shows something */
    private static void selectFirstLocated(JComponent c) {
        javax.swing.JTable t = find(c, javax.swing.JTable.class);
        if (t != null && t.getRowCount() > 0) {
            t.setRowSelectionInterval(0, 0);
            return;
        }
        javax.swing.JTree tree = find(c, javax.swing.JTree.class);
        if (tree == null) return;
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object o = ((javax.swing.tree.DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
            if (o instanceof Item it && it.loc() != null) {
                tree.setSelectionRow(i);
                return;
            }
        }
    }

    private static <T> T find(java.awt.Component c, Class<T> type) {
        if (type.isInstance(c)) return type.cast(c);
        if (c instanceof java.awt.Container ct) {
            for (java.awt.Component ch : ct.getComponents()) {
                T r = find(ch, type);
                if (r != null) return r;
            }
        }
        return null;
    }

    static Set<String> expand(GitRunner git, String spec) {
        Set<String> res = new LinkedHashSet<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("..")) {
                for (String l : git.run("rev-list", "--no-merges", p).split("\n")) if (!l.isBlank()) res.add(l.trim());
            } else {
                res.add(git.run("rev-parse", "--verify", p + "^{commit}").trim());
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ automation helpers

    private static void onEdt(Runnable r) throws Exception {
        EventQueue.invokeAndWait(r);
    }

    private static void idle(MainFrame m) throws Exception {
        Thread.sleep(100);
        waitFor(() -> m.busy.get() == 0, 120_000);
        onEdt(() -> {
        });
    }

    private static void settle() throws Exception {
        Thread.sleep(700); // let background loads (commit details, source views) finish and paint
        onEdt(() -> {
        });
    }

    private static void waitFor(BooleanSupplier c, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
    }

    private static void shot(MainFrame m, Args a, String name) throws Exception {
        if (a.screenshots == null) return;
        Files.createDirectories(a.screenshots);
        BufferedImage[] img = new BufferedImage[1];
        onEdt(() -> {
            javax.swing.JRootPane rp = m.frame.getRootPane();
            BufferedImage b = new BufferedImage(rp.getWidth(), rp.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            rp.paintAll(g);
            // heavyweight popups (the branch search) are separate windows: paint them where they are on screen
            java.awt.Point origin = rp.getLocationOnScreen();
            for (java.awt.Window w : m.frame.getOwnedWindows()) {
                if (!w.isShowing() || w.getType() != java.awt.Window.Type.POPUP) continue;
                java.awt.Point p = w.getLocationOnScreen();
                Graphics2D wg = (Graphics2D) g.create(p.x - origin.x, p.y - origin.y, w.getWidth(), w.getHeight());
                w.paintAll(wg);
                wg.dispose();
            }
            g.dispose();
            img[0] = b;
        });
        Path f = a.screenshots.resolve(name + ".png");
        try {
            ImageIO.write(img[0], "png", f.toFile());
            System.err.println("[faultline gui] screenshot " + f);
        } catch (IOException e) {
            System.err.println("[faultline gui] screenshot failed: " + e);
        }
    }

    private static void measure(String what, long v) {
        System.err.println("[measure] " + what + "=" + v);
    }

    /** a /proc/self/status field in kB (Linux only; -1 elsewhere) */
    static long rssKb(String field) {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (l.startsWith(field + ":")) return Long.parseLong(l.replaceAll("\\D+", ""));
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        return -1;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    static final class Args {
        Path repo;
        String onto, mainline, pick, hunk, branchSearch, thenPick;
        Path thenRepo;
        boolean analyze, measure, exit, toggleTheme;
        Boolean dark;
        Path screenshots, export;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String s = args[i];
                switch (s) {
                    case "gui" -> {
                    }
                    case "--repo" -> a.repo = Path.of(args[++i]);
                    case "--onto", "--target" -> a.onto = args[++i];
                    case "--mainline" -> a.mainline = args[++i];
                    case "--pick" -> a.pick = args[++i];
                    case "--hunk" -> a.hunk = args[++i];
                    case "--branch-search" -> a.branchSearch = args[++i];
                    case "--then-repo" -> a.thenRepo = Path.of(args[++i]);
                    case "--then-pick" -> a.thenPick = args[++i];
                    case "--analyze" -> a.analyze = true;
                    case "--dark" -> a.dark = true;
                    case "--light" -> a.dark = false;
                    case "--screenshots" -> a.screenshots = Path.of(args[++i]);
                    case "--export" -> a.export = Path.of(args[++i]);
                    case "--measure" -> a.measure = true;
                    case "--exit" -> a.exit = true;
                    case "--toggle-theme" -> a.toggleTheme = true;
                    case "--no-cache" -> com.branchdiff.index.SummaryCache.setDefault(com.branchdiff.index.SummaryCache.disabled());
                    default -> {
                        if (!s.startsWith("-") && a.repo == null) a.repo = Path.of(s);
                        else throw new IllegalArgumentException("unknown gui option " + s);
                    }
                }
            }
            return a;
        }
    }
}
