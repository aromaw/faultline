package com.branchdiff.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Persistent cache of {@link FileSummaries file summaries}, keyed by the git blob id of the file's content, so a file
 * is parsed once however many versions (fork point, base, ours, theirs) and runs contain it.
 *
 * <p>Layout: {@code <dir>/summaries/<build key>/<2 hex>/<38 hex>}, one small file per blob, like git's loose objects.
 * The build key is the summary format, the faultline version and the build id, so another build never reads an
 * entry it did not write (entries of other builds are pruned first, being the least recently used).
 *
 * <p>Why one file per entry: an entry is written once and never changed, so a write is "write a temp file, rename
 * it over the name" (atomic on POSIX file systems and NTFS), which needs no lock and no index file that concurrent
 * runs would have to share; a damaged entry costs one re-parse and never the rest of the cache. Entries are a few
 * hundred bytes to a few KB; reading 9,000 of them (yudao, warm) takes well under a second spread over the parse
 * threads. Nothing is kept in memory: each entry is read, checked and decoded into the index once.
 *
 * <p>Entry: magic {@code FLS1}, the 20-byte blob id, the payload length, the payload, CRC-32 of the payload. A
 * short, damaged or mismatching entry (a crash between write and rename, a full disk, a bug) is ignored, deleted and
 * written again. Size is bounded: when this process has written entries and the cache was not checked for a day (or
 * this process wrote a tenth of the limit), a background thread deletes the least recently used entries until the
 * cache is below 80 % of its limit (default 512 MB; entries are touched on read at most once a day).
 *
 * <p>Configuration: {@code -Dfaultline.cacheDir} / {@code FAULTLINE_CACHE_DIR} (default {@code ~/.faultline/cache}),
 * {@code -Dfaultline.cache=off} / {@code FAULTLINE_CACHE=off} (or {@code --no-cache}) to disable it,
 * {@code -Dfaultline.cacheMaxMb} / {@code FAULTLINE_CACHE_MAX_MB}; {@code faultline cache info|clear|prune}.
 */
public final class SummaryCache {
    private static final byte[] MAGIC = "FLS1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = MAGIC.length + 20 + 4;
    private static final long DAY_MS = 24L * 3600 * 1000;
    static final long DEFAULT_MAX_MB = 512;

    private static volatile SummaryCache defaultCache;

    /** root dir (the one the user configures); null when disabled */
    private final Path root;
    /** {@code root/summaries/<build key>} */
    private final Path dir;
    private final long maxBytes;
    private final Set<String> knownDirs = ConcurrentHashMap.newKeySet();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), writes = new AtomicLong(),
            rejected = new AtomicLong(), writeFailures = new AtomicLong(), bytesWritten = new AtomicLong();
    private final AtomicBoolean pruneStarted = new AtomicBoolean();
    private volatile boolean writable = true;

    private SummaryCache(Path root, long maxBytes) {
        this.root = root;
        this.dir = root == null ? null : root.resolve("summaries").resolve(buildKey());
        this.maxBytes = maxBytes;
    }

    /** a cache in {@code root} bounded to {@code maxBytes} (of disk blocks) */
    public static SummaryCache at(Path root, long maxBytes) {
        return new SummaryCache(root.toAbsolutePath().normalize(), maxBytes);
    }

    /** no cache: every file is parsed */
    public static SummaryCache disabled() {
        return new SummaryCache(null, 0);
    }

    /** the cache {@link CodeIndex#build(com.branchdiff.model.Snapshot, com.github.javaparser.resolution.TypeSolver, boolean)} uses */
    public static SummaryCache getDefault() {
        SummaryCache c = defaultCache;
        if (c == null) {
            synchronized (SummaryCache.class) {
                if (defaultCache == null) defaultCache = fromEnvironment();
                c = defaultCache;
            }
        }
        return c;
    }

    /** replaces the default cache (--no-cache, tests); null goes back to the configured one */
    public static synchronized void setDefault(SummaryCache c) {
        defaultCache = c;
    }

    /** the cache configured by system properties and environment variables */
    public static SummaryCache fromEnvironment() {
        String on = setting("faultline.cache", "FAULTLINE_CACHE");
        if (on != null && (on.equalsIgnoreCase("off") || on.equals("0") || on.equalsIgnoreCase("false")
                || on.equalsIgnoreCase("no"))) {
            return disabled();
        }
        return at(configuredRoot(), configuredMaxBytes());
    }

    /** the configured cache directory (also when the cache is disabled) */
    public static Path configuredRoot() {
        String d = setting("faultline.cacheDir", "FAULTLINE_CACHE_DIR");
        if (d != null) return Path.of(d);
        return Path.of(System.getProperty("user.home"), ".faultline", "cache");
    }

    /** the configured size limit in bytes */
    public static long configuredMaxBytes() {
        String m = setting("faultline.cacheMaxMb", "FAULTLINE_CACHE_MAX_MB");
        long mb = DEFAULT_MAX_MB;
        if (m != null) {
            try {
                mb = Math.max(1, Long.parseLong(m.trim()));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return mb * 1024 * 1024;
    }

    private static String setting(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? null : v.trim();
    }

    public boolean enabled() {
        return dir != null;
    }

    /** the directory the user configures, null when disabled */
    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ keys

    private static volatile String buildKey;

    /** summary format + faultline version + build id: entries of another build are never read */
    static String buildKey() {
        String k = buildKey;
        if (k == null) {
            Properties p = new Properties();
            try (InputStream in = SummaryCache.class.getResourceAsStream("/faultline-version.properties")) {
                if (in != null) p.load(in);
            } catch (IOException ignored) {
                // unknown build
            }
            String v = p.getProperty("version", "dev");
            String b = p.getProperty("build", "");
            if (v.startsWith("${")) v = "dev";
            if (b.startsWith("${")) b = "";
            k = ("f" + FileSummaries.FORMAT + "-" + v + (b.isEmpty() ? "" : "-" + b)).replaceAll("[^A-Za-z0-9._-]", "_");
            buildKey = k;
        }
        return k;
    }

    private static final ThreadLocal<MessageDigest> SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** git's blob id of {@code content} (SHA-1 of "blob <length>\0" + the UTF-8 bytes) */
    public static byte[] blobId(String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        MessageDigest md = SHA1.get();
        md.reset();
        md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return md.digest(data);
    }

    Path entryPath(byte[] blobId) {
        String hex = HexFormat.of().formatHex(blobId);
        return dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    // ------------------------------------------------------------------ entries

    /** the payload stored for {@code blobId}, null when absent or damaged (a damaged entry is deleted) */
    byte[] get(byte[] blobId) {
        if (dir == null) return null;
        Path p = entryPath(blobId);
        byte[] b;
        try {
            b = Files.readAllBytes(p);
        } catch (NoSuchFileException e) {
            misses.incrementAndGet();
            return null;
        } catch (IOException | RuntimeException e) {
            misses.incrementAndGet();
            return null;
        }
        byte[] payload = unwrap(b, blobId);
        if (payload == null) {
            reject(blobId);
            return null;
        }
        hits.incrementAndGet();
        touch(p);
        return payload;
    }

    /** a payload that did not decode: drop the entry, it is written again */
    void reject(byte[] blobId) {
        rejected.incrementAndGet();
        if (dir == null) return;
        try {
            Files.deleteIfExists(entryPath(blobId));
        } catch (IOException | RuntimeException ignored) {
            // overwritten by the next put
        }
    }

    static byte[] unwrap(byte[] b, byte[] blobId) {
        if (b.length < HEADER + 4) return null;
        for (int i = 0; i < MAGIC.length; i++) if (b[i] != MAGIC[i]) return null;
        for (int i = 0; i < 20; i++) if (b[MAGIC.length + i] != blobId[i]) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        int len = bb.getInt(MAGIC.length + 20);
        if (len < 0 || len != b.length - HEADER - 4) return null;
        CRC32 crc = new CRC32();
        crc.update(b, HEADER, len);
        if ((int) crc.getValue() != bb.getInt(HEADER + len)) return null;
        byte[] payload = new byte[len];
        System.arraycopy(b, HEADER, payload, 0, len);
        return payload;
    }

    static byte[] wrap(byte[] blobId, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER + payload.length + 4);
        bb.put(MAGIC).put(blobId).putInt(payload.length).put(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        bb.putInt((int) crc.getValue());
        return bb.array();
    }

    /** stores {@code payload} for {@code blobId}: written to a temp file, then renamed over the entry's name */
    void put(byte[] blobId, byte[] payload) {
        if (dir == null || !writable) return;
        Path p = entryPath(blobId);
        Path parent = p.getParent();
        Path tmp = null;
        try {
            if (knownDirs.add(parent.toString())) Files.createDirectories(parent);
            tmp = parent.resolve(p.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-"
                    + Long.toHexString(ThreadLocalRandom.current().nextLong()));
            byte[] data = wrap(blobId, payload);
            Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            writes.incrementAndGet();
            bytesWritten.addAndGet(blocks(data.length));
        } catch (IOException | RuntimeException e) {
            knownDirs.remove(parent.toString());
            // a read-only or full disk: stop trying after a few failures, the analysis goes on without the cache
            if (writeFailures.incrementAndGet() >= 20) writable = false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // left for prune
                }
            }
        }
    }

    /** marks an entry as recently used (at most once a day, it costs a write of the inode) */
    private void touch(Path p) {
        try {
            long now = System.currentTimeMillis();
            if (now - Files.getLastModifiedTime(p).toMillis() > DAY_MS) Files.setLastModifiedTime(p, FileTime.fromMillis(now));
        } catch (IOException | RuntimeException ignored) {
            // read-only cache: LRU order is approximate
        }
    }

    /** disk space of a file of this size (whole 4 KB blocks) */
    static long blocks(long size) {
        return (size + 4095) / 4096 * 4096;
    }

    // ------------------------------------------------------------------ size bound

    /**
     * Prunes in the background when this process wrote entries and the cache was not checked for a day, or this
     * process wrote a tenth of the limit since the last check. Called after an index is built.
     */
    void maybePruneAsync() {
        if (dir == null || writes.get() == 0) return;
        Path marker = root.resolve("summaries").resolve(".last-prune");
        boolean due;
        try {
            due = !Files.exists(marker) || System.currentTimeMillis() - Files.getLastModifiedTime(marker).toMillis() > DAY_MS
                    || bytesWritten.get() > maxBytes / 10;
        } catch (IOException e) {
            due = true;
        }
        if (!due || !pruneStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                prune();
            } finally {
                bytesWritten.set(0);
                pruneStarted.set(false);
            }
        }, "faultline-cache-prune");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** deletes stale temp files and, when the cache is over its limit, the least recently used entries */
    public PruneResult prune() {
        if (root == null) return new PruneResult(0, 0, 0);
        Path summaries = root.resolve("summaries");
        record E(Path path, long size, long mtime) {
        }
        List<E> entries = new ArrayList<>();
        long[] total = {0};
        int[] removed = {0};
        long now = System.currentTimeMillis();
        try {
            Files.createDirectories(summaries);
            Files.walkFileTree(summaries, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    String n = f.getFileName().toString();
                    if (n.startsWith(".")) return FileVisitResult.CONTINUE;
                    long mtime = a.lastModifiedTime().toMillis();
                    if (n.contains(".tmp-")) {
                        if (now - mtime > 3600_000L && delete(f)) removed[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    entries.add(new E(f, blocks(a.size()), mtime));
                    total[0] += blocks(a.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
            long size = total[0];
            if (size > maxBytes) {
                entries.sort((x, y) -> Long.compare(x.mtime, y.mtime));
                long target = maxBytes / 10 * 8;
                for (E e : entries) {
                    if (size <= target) break;
                    if (delete(e.path)) {
                        size -= e.size;
                        removed[0]++;
                    }
                }
            }
            removeEmptyDirs(summaries);
            Path marker = summaries.resolve(".last-prune");
            Files.write(marker, new byte[0]);
            Files.setLastModifiedTime(marker, FileTime.fromMillis(now));
            return new PruneResult(entries.size(), size, removed[0]);
        } catch (IOException | RuntimeException e) {
            return new PruneResult(entries.size(), total[0], removed[0]);
        }
    }

    /** entries seen, disk bytes left, files deleted */
    public record PruneResult(int entries, long bytes, int removed) {
    }

    private static boolean delete(Path f) {
        try {
            return Files.deleteIfExists(f);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void removeEmptyDirs(Path summaries) throws IOException {
        Files.walkFileTree(summaries, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) {
                if (!d.equals(summaries)) {
                    try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
                        if (!s.iterator().hasNext()) Files.delete(d);
                    } catch (IOException ignored) {
                        // not empty or in use
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** deletes every summary of every build ({@code <dir>/summaries}); other files in the directory are kept */
    public static long clear(Path root) throws IOException {
        Path summaries = root.resolve("summaries");
        if (!Files.exists(summaries)) return 0;
        long[] n = {0};
        Files.walkFileTree(summaries, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                n[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
        return n[0];
    }

    /** entries and disk bytes per build key, for {@code faultline cache info} */
    public static List<String> info(Path root) throws IOException {
        List<String> res = new ArrayList<>();
        Path summaries = root.resolve("summaries");
        if (!Files.isDirectory(summaries)) return res;
        try (DirectoryStream<Path> builds = Files.newDirectoryStream(summaries, Files::isDirectory)) {
            for (Path b : builds) {
                long[] t = {0, 0};
                Files.walkFileTree(b, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        t[0]++;
                        t[1] += blocks(a.size());
                        return FileVisitResult.CONTINUE;
                    }
                });
                res.add(b.getFileName() + (b.getFileName().toString().equals(buildKey()) ? " (this build)" : "") + ": "
                        + t[0] + " entries, " + (t[1] + 512 * 1024) / (1024 * 1024) + " MB");
            }
        }
        return res;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** "hits / misses / written / rejected", for --verbose and tests */
    public String stats() {
        if (dir == null) return "disabled";
        return hits.get() + " hits, " + misses.get() + " misses, " + writes.get() + " written, " + rejected.get()
                + " damaged" + (writable ? "" : ", not writable");
    }

    long hits() {
        return hits.get();
    }

    long misses() {
        return misses.get();
    }

    long writes() {
        return writes.get();
    }

    long rejected() {
        return rejected.get();
    }
}
