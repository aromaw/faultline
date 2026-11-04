package com.branchdiff.gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The GUI's small preferences file ({@code ~/.faultline/gui.properties}, or the {@code faultline.prefs} system
 * property): recent projects, theme, and the last target / mainline per project.
 */
public final class Prefs {
    public static final int MAX_RECENT = 10;
    private final Path file;
    private final Properties p = new Properties();

    public Prefs(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
                // start with empty preferences
            }
        }
    }

    public static Prefs load() {
        String override = System.getProperty("faultline.prefs");
        Path f = override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".faultline", "gui.properties");
        return new Prefs(f);
    }

    public Path file() {
        return file;
    }

    public List<String> recentProjects() {
        List<String> res = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String v = p.getProperty("recent." + i);
            if (v != null && !v.isBlank() && !res.contains(v)) res.add(v);
        }
        return res;
    }

    /** moves {@code path} to the front of the recent list (at most {@link #MAX_RECENT} entries) and saves */
    public void addRecent(String path) {
        List<String> l = recentProjects();
        l.remove(path);
        l.add(0, path);
        for (int i = 0; i < MAX_RECENT; i++) {
            if (i < l.size()) p.setProperty("recent." + i, l.get(i));
            else p.remove("recent." + i);
        }
        save();
    }

    public boolean dark() {
        return "dark".equals(p.getProperty("theme"));
    }

    public void setDark(boolean dark) {
        p.setProperty("theme", dark ? "dark" : "light");
        save();
    }

    public String branch(String repo, String which) {
        return p.getProperty("project." + Integer.toHexString(repo.hashCode()) + "." + which);
    }

    public void setBranch(String repo, String which, String branch) {
        if (branch == null) return;
        p.setProperty("project." + Integer.toHexString(repo.hashCode()) + "." + which, branch);
        save();
    }

    /** whether the window uses the bundled Nerd Font (the default) or the theme's system font */
    public boolean nerdFont() {
        return !"system".equals(p.getProperty("font"));
    }

    public void setNerdFont(boolean nerd) {
        p.setProperty("font", nerd ? "nerd" : "system");
        save();
    }

    /** a remembered yes/no choice of the window (e.g. a dismissed hint) */
    public boolean flag(String name) {
        return "true".equals(p.getProperty("flag." + name));
    }

    public void setFlag(String name, boolean value) {
        p.setProperty("flag." + name, Boolean.toString(value));
        save();
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "faultline GUI");
            }
        } catch (IOException ignored) {
            // preferences are best effort
        }
    }
}
