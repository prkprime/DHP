package org.eclipse.mat.dhp.core.memory;

import java.io.File;

/**
 * Pre-flight resource estimator that predicts peak JVM heap memory and
 * database disk space requirements based on Pass 1 heap dump discovery.
 */
public final class ResourceEstimator {

    public record ResourceEstimate(
            long hprofSizeBytes,
            int uniqueObjectCount,
            int classCount,
            int gcRootCount,
            long estimatedPeakHeapBytes,
            long recommendedMinHeapBytes,
            String recommendedXmx,
            long estimatedDiskBytes,
            String banner
    ) {}

    private ResourceEstimator() {}

    /**
     * Estimates RAM and disk resources based on Pass 1 metadata.
     *
     * @param hprofSizeBytes size of the .hprof file on disk
     * @param uniqueObjectCount number of distinct objects discovered
     * @param classCount number of classes discovered
     * @param gcRootCount number of GC roots discovered
     * @return ResourceEstimate with computed limits and display banner
     */
    public static ResourceEstimate estimate(long hprofSizeBytes, int uniqueObjectCount, int classCount, int gcRootCount) {
        long n = Math.max(1, uniqueObjectCount);

        // 1. Memory Calculation (JVM Heap)
        // - Address-to-ID mapping: 8 bytes per object (SortedAddressToIdMap primitive long[])
        // - Dominator Tree phase (the peak memory phase):
        //     * CSR Graph head pointers: (N + 2) * 4 bytes
        //     * CSR Graph edges: ~2.5 * N * 4 bytes = 10 * N bytes
        //     * Lengauer-Tarjan scratch arrays (semi, idom, ancestor, label, dfs/vertex): 5 * N * 4 bytes = 20 * N bytes
        //     * Retained sizes: N * 8 bytes
        //     * Class & String descriptors + DFS stack: ~50 MB
        // Total algorithm footprint: N * (8 + 4 + 10 + 20 + 8) = N * 50 bytes + 50 MB
        long baseHeapBytes = (n * 50L) + (50L * 1024 * 1024);

        // Add 35% safety margin for JVM runtime overhead, thread stacks, and G1 GC regions
        long estimatedPeakHeapBytes = (long) (baseHeapBytes * 1.35);

        // Recommended -Xmx rounded up to nearest sensible power/gigabyte boundary
        long recommendedMinHeapBytes = roundUpHeap(estimatedPeakHeapBytes);
        String recommendedXmx = formatXmx(recommendedMinHeapBytes);

        // 2. Disk Space Calculation (SQLite Database + WAL + B-Tree Indexes)
        // - dhp_objects: N * 40 bytes
        // - dhp_outbound_references & dhp_inbound_references: ~2.5 * N * 20 bytes = 50 * N bytes
        // - dhp_dominator_tree: N * 16 bytes
        // - B-Tree indexes: ~1.2x table size
        // - Total DB file: ~ N * 230 bytes
        // - WAL buffer headroom during bulk indexing: +50%
        long baseDiskBytes = (n * 230L) + (classCount * 1024L) + (100L * 1024 * 1024);
        // Also ensure it is scaled if HPROF file has massive payloads
        long estimatedDiskBytes = Math.max(baseDiskBytes, (long) (hprofSizeBytes * 1.25));

        String banner = buildBanner(hprofSizeBytes, uniqueObjectCount, classCount, gcRootCount,
                estimatedPeakHeapBytes, recommendedXmx, estimatedDiskBytes);

        return new ResourceEstimate(
                hprofSizeBytes,
                uniqueObjectCount,
                classCount,
                gcRootCount,
                estimatedPeakHeapBytes,
                recommendedMinHeapBytes,
                recommendedXmx,
                estimatedDiskBytes,
                banner
        );
    }

    private static long roundUpHeap(long bytes) {
        long mb = bytes / (1024 * 1024);
        if (mb <= 512) return 512L * 1024 * 1024;
        if (mb <= 1024) return 1024L * 1024 * 1024;
        if (mb <= 2048) return 2048L * 1024 * 1024;
        if (mb <= 4096) return 4096L * 1024 * 1024;
        if (mb <= 8192) return 8192L * 1024 * 1024;
        if (mb <= 16384) return 16384L * 1024 * 1024;
        // Round up to next 4GB multiple
        long gb = (mb + 4095) / 4096;
        return gb * 4096L * 1024 * 1024;
    }

    private static String formatXmx(long bytes) {
        long mb = bytes / (1024 * 1024);
        if (mb >= 1024 && mb % 1024 == 0) {
            return "-Xmx" + (mb / 1024) + "g";
        }
        return "-Xmx" + mb + "m";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    private static String buildBanner(long hprofSizeBytes, int uniqueObjectCount, int classCount, int gcRootCount,
                                      long estimatedPeakHeap, String recommendedXmx, long estimatedDisk) {
        StringBuilder sb = new StringBuilder();
        String line = "********************************************************************************";
        sb.append(line).append("\n");
        sb.append("*                   DHP PRE-FLIGHT RESOURCE ESTIMATE                           *\n");
        sb.append(line).append("\n");
        sb.append(String.format("* Discovered Objects:       %,d\n", uniqueObjectCount));
        sb.append(String.format("* Discovered Classes:       %,d\n", classCount));
        sb.append(String.format("* Discovered GC Roots:      %,d\n", gcRootCount));
        sb.append(String.format("* HPROF Dump File Size:     %s\n", formatSize(hprofSizeBytes)));
        sb.append("*\n");
        sb.append(String.format("* Estimated Peak JVM Heap:  %s\n", formatSize(estimatedPeakHeap)));
        sb.append(String.format("* RECOMMENDED MINIMUM HEAP: %s (with 35%% safety headroom)\n", recommendedXmx));
        sb.append(String.format("* RECOMMENDED FREE DISK:    %s (Database + Indexes + WAL)\n", formatSize(estimatedDisk)));
        sb.append(line);
        return sb.toString();
    }
}
