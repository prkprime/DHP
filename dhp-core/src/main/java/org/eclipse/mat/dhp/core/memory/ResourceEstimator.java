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
            long estimatedTightHeapBytes,
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
        // Pass 2 stays the peak phase. Per object: sorted addresses 8 B, bucket table 4 B, object columns 20 B,
        // edge start pointers 4 B, membership bitsets ~0.25 B, plus about 4 B per stored outbound reference
        // (~2-4 references per object on real dumps) and a ~50 MB fixed baseline (strings, class records, roots,
        // JVM overhead). The dominator phase reuses the heap the edge log leaves behind and its scratch arrays
        // (~28 B/object) stay below the Pass 2 footprint on reference-heavy dumps when the inbound CSR is
        // file-backed, which the engine decides automatically once the reachable counts are known.
        long avgEdges = Long.getLong("dhp.avg.edges.per.object", 3L);
        long address = 8L * n;
        long buckets = 4L * n;
        long columns = 20L * n;
        long edgeStart = 4L * n;
        long bitsets = Math.max(16L * 1024 * 1024, n / 4);
        long misc = 50L * 1024 * 1024;
        long edgeLog = 4L * Math.max(1, avgEdges) * n;

        long fastHeapBytes = address + buckets + columns + edgeStart + bitsets + misc + edgeLog;
        long tightHeapBytes = address + buckets + edgeStart + bitsets + misc + edgeLog;

        // Add 35% safety margin for JVM runtime overhead, thread stacks, and G1 GC regions
        long estimatedPeakHeapBytes = (long) (fastHeapBytes * 1.35);
        long estimatedTightHeapBytes = (long) (tightHeapBytes * 1.35);

        // Recommended -Xmx rounded up to nearest sensible power/gigabyte boundary
        long recommendedMinHeapBytes = roundUpHeap(estimatedPeakHeapBytes);
        String recommendedXmx = formatXmx(recommendedMinHeapBytes);
        long recommendedTightXmx = roundUpHeap(estimatedTightHeapBytes);
        String recommendedTight = formatXmx(recommendedTightXmx);

        // 2. Disk Space Calculation (SQLite chunked blob store, measured: ~113 bytes per object at ~2.5 edges/object)
        // - object columns (address, class, size, file position, flags): ~28 bytes
        // - outbound + inbound reference lists: ~2 * (2.5 * 4 + 4) = 28 bytes
        // - dominator, retained size, dominated lists, class instance lists: ~28 bytes
        // - page fill overhead: ~1.3x
        long baseDiskBytes = (n * 135L) + (classCount * 1024L) + (100L * 1024 * 1024);
        // Bulk ingestion runs without a journal, so there is no WAL headroom to add.
        long estimatedDiskBytes = baseDiskBytes;

        String banner = buildBanner(hprofSizeBytes, uniqueObjectCount, classCount, gcRootCount,
                estimatedPeakHeapBytes, recommendedXmx, estimatedTightHeapBytes, recommendedTight, estimatedDiskBytes);

        return new ResourceEstimate(
                hprofSizeBytes,
                uniqueObjectCount,
                classCount,
                gcRootCount,
                estimatedPeakHeapBytes,
                recommendedMinHeapBytes,
                recommendedXmx,
                estimatedDiskBytes,
                estimatedTightHeapBytes,
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
                                      long estimatedPeakHeap, String recommendedXmx,
                                      long estimatedTightHeap, String recommendedTight, long estimatedDisk) {
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
        sb.append(String.format("* Estimated Peak JVM Heap:  %s (all structures in heap)\n", formatSize(estimatedPeakHeap)));
        sb.append(String.format("* RECOMMENDED MINIMUM HEAP: %s (with 35%% safety headroom)\n", recommendedXmx));
        sb.append(String.format("* In TIGHT mode:            %s -> %s (cold columns in temp files, auto-selected)\n",
                formatSize(estimatedTightHeap), recommendedTight));
        sb.append(String.format("* RECOMMENDED FREE DISK:    %s (chunked database)\n", formatSize(estimatedDisk)));
        sb.append(line);
        return sb.toString();
    }
}
