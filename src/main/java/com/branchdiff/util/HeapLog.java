package com.branchdiff.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

/**
 * Measurement aid: with {@code -Dfaultline.heapLog=true}, {@link #mark} runs a full GC and prints the live heap (and
 * the largest live heap seen so far) to stderr, so a run shows what each phase keeps. Off by default: a full GC per
 * phase costs time.
 */
public final class HeapLog {
    private static final boolean ON = Boolean.getBoolean("faultline.heapLog");
    private static long maxLive;

    private HeapLog() {
    }

    public static boolean enabled() {
        return ON;
    }

    /** live heap after a full GC, in MB, printed with {@code phase}; no-op unless enabled */
    public static synchronized void mark(String phase) {
        if (!ON) return;
        long live = liveBytes();
        maxLive = Math.max(maxLive, live);
        System.err.printf("[heap] %-28s live %4d MB (max %4d MB)%n", phase, live >> 20, maxLive >> 20);
        if (HISTO > 0) histogram();
    }

    /** {@code -Dfaultline.heapHisto=N}: with the heap log, also print the N largest classes of the live heap */
    private static final int HISTO = Integer.getInteger("faultline.heapHisto", 0);

    private static void histogram() {
        try {
            Object out = ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[] {new String[0]}, new String[] {String[].class.getName()});
            String[] lines = String.valueOf(out).split("\n");
            for (int i = 0; i < Math.min(lines.length, HISTO + 3); i++) System.err.println("[heap]   " + lines[i]);
        } catch (Exception | Error e) {
            System.err.println("[heap]   no class histogram: " + e);
        }
    }

    /** the heap in use after {@link System#gc()} */
    public static long liveBytes() {
        System.gc();
        long used = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP && p.getUsage() != null) used += p.getUsage().getUsed();
        }
        return used;
    }
}
