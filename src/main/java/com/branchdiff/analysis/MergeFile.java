package com.branchdiff.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Predicts textual conflicts with {@code git merge-file -p} on temp copies (no repository access, nothing written
 * to the user's repo).
 */
public final class MergeFile {
    private MergeFile() {
    }

    /** @return number of conflict hunks (0 = clean), or -1 if git merge-file failed */
    public static int conflicts(String base, String ours, String theirs) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("branchdiff-merge");
            Path b = dir.resolve("base"), o = dir.resolve("ours"), t = dir.resolve("theirs");
            Files.writeString(b, base == null ? "" : base, StandardCharsets.UTF_8);
            Files.writeString(o, ours == null ? "" : ours, StandardCharsets.UTF_8);
            Files.writeString(t, theirs == null ? "" : theirs, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("git", "merge-file", "-p", "-q", o.toString(), b.toString(), t.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            int code = p.exitValue();
            return code >= 0 && code < 128 ? code : -1;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            if (dir != null) {
                for (String n : new String[]{"base", "ours", "theirs"}) dir.resolve(n).toFile().delete();
                dir.toFile().delete();
            }
        }
    }
}
