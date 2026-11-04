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
 * Access to a git repository. Never checks out, never writes refs, never touches the worktree or the index. Besides
 * read commands (rev-parse / merge-base / ls-tree / cat-file / log / blame / diff), the conflict mode's offline
 * simulation uses {@code merge-tree --write-tree} and {@code commit-tree}, which only add unreferenced objects to the
 * object database (garbage-collected later).
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
        return run(Map.of(), args);
    }

    /** {@link #run(String...)} with extra environment variables */
    public String run(Map<String, String> env, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            pb.environment().putAll(env);
            Process p = pb.start();
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

    /** Output and exit code of a git command that may legitimately fail (e.g. merge-tree exits 1 on conflicts). */
    public record Result(int exit, String out, String err) {
    }

    /** git subcommand -> {calls, nanoseconds}, for --verbose ({@link #stats()}) */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> STATS = new java.util.concurrent.ConcurrentHashMap<>();

    /** "log 412 calls 61.2 s, cat-file ..." since the last {@link #resetStats()}, slowest first */
    public static String stats() {
        List<Map.Entry<String, long[]>> es = new ArrayList<>(STATS.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> e : es) {
            parts.add(String.format(java.util.Locale.ROOT, "%s %d calls %.1f s", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e9));
        }
        return parts.isEmpty() ? "no calls" : String.join(", ", parts);
    }

    public static void resetStats() {
        STATS.clear();
    }

    private static String subcommand(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c") || args[i].equals("-C")) i++;
            else if (!args[i].startsWith("-")) return args[i];
        }
        return "?";
    }

    public Result exec(String... args) {
        long t0 = System.nanoTime();
        try {
            return execTimed(args);
        } finally {
            long[] st = STATS.computeIfAbsent(subcommand(args), k -> new long[2]);
            synchronized (st) {
                st[0]++;
                st[1] += System.nanoTime() - t0;
            }
        }
    }

    private Result execTimed(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).start();
            p.getOutputStream().close();
            byte[][] err = new byte[1][];
            Thread t = new Thread(() -> {
                try {
                    err[0] = p.getErrorStream().readAllBytes();
                } catch (IOException e) {
                    err[0] = new byte[0];
                }
            });
            t.start();
            byte[] out = p.getInputStream().readAllBytes();
            t.join();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new GitException("git timed out: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err[0], StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GitException("cannot run git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted", e);
        }
    }

    /** null when {@code ref} does not resolve */
    public String tryResolve(String ref) {
        Result r = exec("rev-parse", "--verify", "--quiet", ref);
        return r.exit() == 0 ? r.out().trim() : null;
    }

    /** content of {@code path} at {@code rev}, null when it does not exist there (or is not a file) */
    public String show(String rev, String path) {
        return catFile(rev + ":" + path);
    }

    /** content of blob {@code sha}, null when there is no such blob */
    public String blob(String sha) {
        return catFile(sha);
    }

    /**
     * One long-running {@code git cat-file --batch} serves every {@link #show} / {@link #blob}: a conflict analysis
     * of 10 picks read ~11,000 file versions, and a process per read took half a minute. Same answers as
     * {@code git cat-file blob <spec>}: null for a missing object or one that is not a blob.
     */
    private final CatFile catFile = new CatFile();

    private String catFile(String spec) {
        if (spec.indexOf('\n') >= 0 || spec.indexOf('\r') >= 0) {
            Result r = exec("cat-file", "blob", spec); // cannot be written as one line of the batch protocol
            return r.exit() == 0 ? r.out() : null;
        }
        long t0 = System.nanoTime();
        try {
            return catFile.read(repo, spec);
        } finally {
            long[] st = STATS.computeIfAbsent("cat-file --batch", k -> new long[2]);
            synchronized (st) {
                st[0]++;
                st[1] += System.nanoTime() - t0;
            }
        }
    }

    /** stops the cat-file process (it also stops when this runner is garbage-collected, or with the JVM) */
    public void close() {
        catFile.close();
    }

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    /** the batch process; registered with {@link #CLEANER} so that an unused runner does not leave git running */
    private final class CatFile {
        private final State state = new State();

        CatFile() {
            CLEANER.register(GitRunner.this, state);
        }

        String read(Path repo, String spec) {
            synchronized (state) {
                for (int attempt = 0; ; attempt++) {
                    try {
                        if (state.p == null || !state.p.isAlive()) state.start(repo);
                        state.out.write((spec + "\n").getBytes(StandardCharsets.UTF_8));
                        state.out.flush();
                        String header = readLine(state.in);
                        if (header.isEmpty()) throw new IOException("cat-file --batch ended");
                        String[] parts = header.split(" ");
                        if (parts.length != 3) return null; // "<spec> missing" / "ambiguous"
                        int size = Integer.parseInt(parts[2]);
                        byte[] data = state.in.readNBytes(size);
                        if (data.length != size || state.in.read() != '\n') throw new IOException("short read for " + spec);
                        return "blob".equals(parts[1]) ? new String(data, StandardCharsets.UTF_8) : null;
                    } catch (IOException | NumberFormatException e) {
                        state.run();
                        if (attempt > 0) {
                            Result r = exec("cat-file", "blob", spec);
                            return r.exit() == 0 ? r.out() : null;
                        }
                    }
                }
            }
        }

        void close() {
            synchronized (state) {
                state.run();
            }
        }
    }

    private static final class State implements Runnable {
        Process p;
        OutputStream out;
        InputStream in;

        void start(Path repo) throws IOException {
            p = new ProcessBuilder("git", "-C", repo.toString(), "cat-file", "--batch")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            out = new java.io.BufferedOutputStream(p.getOutputStream());
            in = new java.io.BufferedInputStream(p.getInputStream(), 1 << 16);
        }

        @Override
        public void run() {
            if (p == null) return;
            try {
                out.close(); // git exits at the end of its input
            } catch (IOException ignored) {
                // already gone
            }
            p.destroy();
            p = null;
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
    /**
     * Blob contents by sha, shared by every read while someone still uses them: the versions of one analysis (fork
     * point, base, ours, theirs) are mostly the same files, which are then read and held once.
     */
    private static final Map<String, java.lang.ref.WeakReference<String>> BLOBS = new java.util.HashMap<>();

    private static synchronized String sharedBlob(String sha) {
        java.lang.ref.WeakReference<String> r = BLOBS.get(sha);
        return r == null ? null : r.get();
    }

    private static synchronized String shareBlob(String sha, String content) {
        String old = sharedBlob(sha);
        if (old != null) return old;
        if (BLOBS.size() > 4096 && BLOBS.size() % 4096 == 0) BLOBS.values().removeIf(w -> w.get() == null);
        BLOBS.put(sha, new java.lang.ref.WeakReference<>(content));
        return content;
    }

    public Map<String, String> readBlobs(Map<String, String> pathToSha) {
        Map<String, String> res = new LinkedHashMap<>();
        if (pathToSha.isEmpty()) return res;
        Map<String, String> missing = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pathToSha.entrySet()) {
            String s = sharedBlob(e.getValue());
            res.put(e.getKey(), s); // keeps the order; filled below when null
            if (s == null) missing.put(e.getKey(), e.getValue());
        }
        if (!missing.isEmpty()) {
            Map<String, String> read = readBlobsFromGit(missing);
            read.forEach((path, content) -> res.put(path, shareBlob(missing.get(path), content)));
        }
        return res;
    }

    private Map<String, String> readBlobsFromGit(Map<String, String> pathToSha) {
        Map<String, String> res = new LinkedHashMap<>();
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
