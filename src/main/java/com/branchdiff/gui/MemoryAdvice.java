package com.branchdiff.gui;

/** What the GUI tells the user about the Java heap: the limit, and how to raise it after an OutOfMemoryError. */
public final class MemoryAdvice {
    private MemoryAdvice() {
    }

    /** a heap size to suggest after running out with {@code maxBytes}: twice the limit, at least 2 GB, in whole GB */
    public static String suggestedXmx(long maxBytes) {
        long gb = (maxBytes * 2 + (1L << 30) - 1) >> 30;
        return Math.max(2, gb) + "g";
    }

    /**
     * The heap still in use after the last garbage collection (what the app really holds), not the used heap,
     * which also counts garbage not collected yet and so climbs towards the limit even when little is in use.
     * Before the first collection of a pool, its current use counts.
     */
    public static long liveHeapBytes() {
        long sum = 0;
        for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() != java.lang.management.MemoryType.HEAP) continue;
            java.lang.management.MemoryUsage after = p.getCollectionUsage();
            java.lang.management.MemoryUsage now = p.getUsage();
            long u = after != null && after.getMax() != 0 && after.getCommitted() > 0 ? after.getUsed() : now == null ? 0 : now.getUsed();
            // a pool shrinks between collections only when collected; its current use bounds the last value
            sum += now == null ? u : Math.min(u, now.getUsed());
        }
        return sum;
    }

    public static String limitText(long maxBytes) {
        long mb = maxBytes >> 20;
        return mb >= 1024 ? String.format("%.1f GB", mb / 1024.0) : mb + " MB";
    }

    /** the dialog text after an OutOfMemoryError (plain text, one paragraph per line) */
    public static String outOfMemory(long maxBytes) {
        String x = suggestedXmx(maxBytes);
        return "faultline ran out of memory (Java heap space, limit " + limitText(maxBytes) + ").\n"
                + "The analysis was stopped and its data was released, so you can keep working.\n\n"
                + "Try fewer commits at once, or start faultline with a larger heap:\n"
                + "    FAULTLINE_XMX=" + x + " scripts/faultline gui\n"
                + "    java -Xmx" + x + " -jar branch-diff-tool.jar gui\n\n"
                + "The launcher's default is a quarter of the physical memory (at least 1 GB).";
    }

    /** a status bar hint when the JVM was started with a small heap (plain java -jar on a small default), else null */
    public static String smallHeapHint(long maxBytes) {
        if (maxBytes >= 900L << 20) return null;
        return "Java heap limit is only " + limitText(maxBytes) + "; large projects may need more (start with -Xmx2g or scripts/faultline)";
    }
}
