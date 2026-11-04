package com.branchdiff.gui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/**
 * Unhandled exceptions of the GUI, appended to {@code ~/.faultline/logs/gui.log} (with the version, Java and OS), so
 * a crash leaves something to send even when the app was not started from a terminal. The file is restarted when it
 * grows over 1 MB. The launcher puts JVM crash reports (hs_err) in the same folder.
 */
final class CrashLog {
    private CrashLog() {
    }

    static String version() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = CrashLog.class.getResourceAsStream("/faultline-version.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // unknown build
        }
        return p.getProperty("version", "dev") + " (build " + p.getProperty("build", "?") + ")";
    }

    static Path dir() {
        return Path.of(System.getProperty("user.home"), ".faultline", "logs");
    }

    /** the log file, or null when it cannot be written */
    static synchronized Path write(Thread t, Throwable e) {
        try {
            Path f = dir().resolve("gui.log");
            Files.createDirectories(f.getParent());
            if (Files.exists(f) && Files.size(f) > 1 << 20) Files.delete(f);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String text = "=== " + OffsetDateTime.now() + "  faultline " + version()
                    + "  Java " + System.getProperty("java.version") + "  " + System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\n"
                    + "thread " + t.getName() + "\n" + sw + "\n";
            Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return f;
        } catch (Exception | Error ignored) {
            return null;
        }
    }
}
