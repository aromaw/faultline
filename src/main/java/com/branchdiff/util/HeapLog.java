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
