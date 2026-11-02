package com.branchdiff.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Read-only access to a git repository. Never checks out, never writes refs, never touches the worktree:
 * only rev-parse / merge-base / ls-tree / cat-file are used.
 */
public class GitRunner {
    private final Path repo;

    public GitRunner(Path repo) {
        this.repo = repo;
    }

    public Path repo() {
        return repo;
    }

    public String run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new GitException("git timed out: " + String.join(" ", args));
            }
            if (p.exitValue() != 0) {
                throw new GitException("git " + String.join(" ", args) + " failed (" + p.exitValue() + "): "
                        + new String(err, StandardCharsets.UTF_8).trim());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitException("cannot run git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted", e);
        }
    }

    public String resolveCommit(String ref) {
        try {
            return run("rev-parse", "--verify", "--quiet", ref + "^{commit}").trim();
        } catch (GitException e) {
            throw new GitException("cannot resolve ref '" + ref + "' to a commit in " + repo);
        }
    }

    public String mergeBase(String a, String b) {
        return run("merge-base", a, b).trim();
    }

    /** Lists blobs (path -> blob sha) under {@code dir} (repo-relative, may be empty) at {@code commit}. */
    public Map<String, String> listBlobs(String commit, String dir) {
        List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "-z", "--full-tree", commit));
        if (dir != null && !dir.isEmpty()) {
            args.add("--");
            args.add(dir);
        }
        String out = run(args.toArray(new String[0]));
        Map<String, String> res = new LinkedHashMap<>();
        for (String entry : out.split("\0")) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            String[] meta = entry.substring(0, tab).split(" ");
            if (!"blob".equals(meta[1])) continue;
            res.put(entry.substring(tab + 1), meta[2]);
        }
        return res;
    }

    /** Reads many blobs with a single {@code git cat-file --batch} process. */
    public Map<String, String> readBlobs(Map<String, String> pathToSha) {
        Map<String, String> res = new LinkedHashMap<>();
        if (pathToSha.isEmpty()) return res;
        try {
            Process p = new ProcessBuilder("git", "-C", repo.toString(), "cat-file", "--batch").start();
            List<String> paths = new ArrayList<>(pathToSha.keySet());
            Thread writer = new Thread(() -> {
                try (OutputStream os = p.getOutputStream()) {
                    for (String path : paths) {
                        os.write((pathToSha.get(path) + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // reader side will fail
                }
            });
            writer.start();
            InputStream in = p.getInputStream();
            for (String path : paths) {
                String header = readLine(in);
                String[] parts = header.split(" ");
                if (parts.length < 3 || "missing".equals(parts[1])) {
                    throw new GitException("cat-file failed for " + path + ": " + header);
                }
                int size = Integer.parseInt(parts[2]);
                byte[] data = in.readNBytes(size);
                in.read(); // trailing LF
                res.put(path, new String(data, StandardCharsets.UTF_8));
            }
            writer.join();
            p.waitFor(1, TimeUnit.MINUTES);
            return res;
        } catch (IOException e) {
            throw new GitException("cannot run git cat-file: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') buf.write(c);
        return buf.toString(StandardCharsets.UTF_8);
    }

    public static class GitException extends RuntimeException {
        public GitException(String msg) {
            super(msg);
        }

        public GitException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
