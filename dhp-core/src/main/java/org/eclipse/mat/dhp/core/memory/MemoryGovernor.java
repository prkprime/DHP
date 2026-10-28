package org.eclipse.mat.dhp.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamically computes memory budgets and cache parameters based on JVM max memory,
 * available physical RAM, and user settings.
 */
public final class MemoryGovernor {
    private static final Logger log = LoggerFactory.getLogger(MemoryGovernor.class);

    /**
     * How the pipeline trades JVM heap for speed when the budget is smaller than the in-memory structures.
     *
     * <ul>
     * <li>{@link #FAST}: everything in the JVM heap (object columns, bucketed address map).</li>
     * <li>{@link #TIGHT}: the cold per-object columns (class, size, file position) live in memory-mapped
     * temporary files, and the dominator's inbound edge list is file-backed too.</li>
     * <li>{@link #SAVER}: TIGHT plus no bucket acceleration on the address map.</li>
     * <li>{@link #EXTREME}: SAVER plus the sorted address array itself in a memory-mapped file (0 heap).</li>
     * </ul>
     */
    public enum MemoryMode {
        FAST, TIGHT, SAVER, EXTREME
    }

    private final long totalAllocatedBytes;
    private final int batchSize;
    private final int workerThreads;
    private final long dbPageCacheBytes;
    private final MemoryMode forcedMode;

    public MemoryGovernor(Long userBudgetBytes, Integer userThreads) {
        long jvmMaxMemory = Runtime.getRuntime().maxMemory();
        long effectiveBudget = userBudgetBytes != null && userBudgetBytes > 0
                ? Math.min(userBudgetBytes, jvmMaxMemory)
                : (long) (jvmMaxMemory * 0.75); // default 75% of JVM -Xmx

        this.totalAllocatedBytes = effectiveBudget;

        int cores = Runtime.getRuntime().availableProcessors();
        this.workerThreads = userThreads != null && userThreads > 0 ? userThreads : Math.max(2, cores);

        // Dynamically compute batch size based on available memory:
        // E.g., for 1GB budget: batch size ~ 50,000 objects. For 8GB+: 500,000 objects.
        if (effectiveBudget < 512L * 1024 * 1024) {
            this.batchSize = 25_000;
        } else if (effectiveBudget < 2L * 1024 * 1024 * 1024) {
            this.batchSize = 100_000;
        } else if (effectiveBudget < 8L * 1024 * 1024 * 1024) {
            this.batchSize = 250_000;
        } else {
            this.batchSize = 500_000;
        }

        // Allocate ~30% of memory budget to database page cache / connection caches
        this.dbPageCacheBytes = (long) (effectiveBudget * 0.30);

        String mode = System.getProperty("dhp.memory.mode", "").toLowerCase();
        this.forcedMode = switch (mode) {
            case "fast" -> MemoryMode.FAST;
            case "tight" -> MemoryMode.TIGHT;
            case "saver" -> MemoryMode.SAVER;
            case "extreme" -> MemoryMode.EXTREME;
            default -> null;
        };

        log.info("Initialized MemoryGovernor: Budget={}MB, BatchSize={}, Threads={}, DbCache={}MB{}",
                effectiveBudget / (1024 * 1024), batchSize, workerThreads, dbPageCacheBytes / (1024 * 1024),
                forcedMode != null ? ", ForcedMode=" + forcedMode : "");
    }

    /**
     * Chooses the Pass 2 memory mode for an ingestion of {@code objectCount} objects under this budget.
     * The decision models the Pass 2 peak: the sorted address array (8 B/object), the bucket table (4 B/object),
     * the object columns (20 B/object, moved to temp files outside {@link MemoryMode#FAST}), the edge start pointers
     * (4 B/object), the edge log (about 4 B per stored outbound reference) and a small fixed baseline. The projected
     * per-object edge count is a heuristic (2-3 references per object is typical, 4-5 on reference-heavy dumps);
     * override it with {@code -Ddhp.avg.edges.per.object=N} when a dump is known to be reference-heavy.
     *
     * <p>The dominator phase gets its own, separate decision ({@link #inboundFileBacked(int, long)}) once the
     * reachable object and edge counts are known, so a conservative guess here never forces slow address lookups.
     */
    public MemoryMode chooseMode(int objectCount) {
        if (forcedMode != null) {
            return forcedMode;
        }
        long n = Math.max(1, objectCount);
        long avgEdges = Long.getLong("dhp.avg.edges.per.object", 4L);
        long address = 8L * n;
        long buckets = 4L * n;
        long columns = 20L * n;
        long edgeStart = 4L * n;
        long bitsets = Math.max(16L * 1024 * 1024, n / 4);
        long misc = 48L * 1024 * 1024; // strings, class records, roots, layouts, JVM baseline
        long edgeLog = 4L * Math.max(1, avgEdges) * n;

        long fast = address + buckets + columns + edgeStart + bitsets + misc + edgeLog;
        long tight = address + buckets + edgeStart + bitsets + misc + edgeLog;
        long saver = address + edgeStart + bitsets + misc + edgeLog;
        long extreme = edgeStart + bitsets + misc + edgeLog;

        if (totalAllocatedBytes >= fast * 11 / 10) return MemoryMode.FAST;
        if (totalAllocatedBytes >= tight * 11 / 10) return MemoryMode.TIGHT;
        if (totalAllocatedBytes >= saver * 11 / 10) return MemoryMode.SAVER;
        return MemoryMode.EXTREME;
    }

    /**
     * Whether the dominator phase should keep its inbound CSR (one 4-byte entry per reference) in a memory-mapped
     * temp file instead of the JVM heap. Decided at the start of Phase 3 from the real reachable object count
     * {@code n} and edge count {@code edges}, when the tightest phase (DFS over the outbound CSR while building the
     * inbound CSR) can be measured exactly: it needs about {@code inHead(4n) + outCSR(4n + 4E) + inCSR(4E)
     * + len-gauer-tarjan scratch (~20n)} bytes.
     */
    public boolean inboundFileBacked(int n, long edges) {
        if (forcedMode != null) {
            return forcedMode != MemoryMode.FAST;
        }
        long inHeapPeak = 28L * Math.max(0, n) + 8L * Math.max(0, edges);
        return totalAllocatedBytes < inHeapPeak * 5 / 4;
    }

    /**
     * Creates the optimal address-to-ID lookup map based on memory budget and object count.
     * Uses off-heap mmap if memory budget cannot accommodate sorted array in JVM heap,
     * or chunked array to avoid G1 humongous allocations.
     */
    public IAddressToIdMap createAddressMap(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap classAddressToId,
                                            long[] sortedInstanceAddresses,
                                            int baseInstanceId,
                                            java.io.File tempDir) {
        return createAddressMap(classAddressToId, sortedInstanceAddresses,
                sortedInstanceAddresses != null ? sortedInstanceAddresses.length : 0,
                baseInstanceId, tempDir);
    }

    public IAddressToIdMap createAddressMap(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap classAddressToId,
                                            long[] sortedInstanceAddresses,
                                            int instanceCount,
                                            int baseInstanceId,
                                            java.io.File tempDir) {
        return createAddressMap(classAddressToId, sortedInstanceAddresses, instanceCount, baseInstanceId, tempDir,
                chooseMode(instanceCount));
    }

    public IAddressToIdMap createAddressMap(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap classAddressToId,
                                            long[] sortedInstanceAddresses,
                                            int instanceCount,
                                            int baseInstanceId,
                                            java.io.File tempDir,
                                            MemoryMode mode) {
        String override = System.getProperty("dhp.address.map", "").toLowerCase();
        int count = instanceCount;
        long estimatedArrayBytes = (long) count * 8L;

        boolean mmap = mode == MemoryMode.EXTREME;
        if ("mmap".equals(override) || (totalAllocatedBytes < estimatedArrayBytes * 2 && count > 500_000)) {
            mmap = true;
        }
        if (mmap) {
            try {
                log.info("MemoryGovernor: Selected off-heap MmapAddressToIdMap (Heap budget = {}MB, Addresses = {}MB)",
                        totalAllocatedBytes / (1024 * 1024), estimatedArrayBytes / (1024 * 1024));
                return new MmapAddressToIdMap(classAddressToId, sortedInstanceAddresses, count, baseInstanceId, tempDir);
            } catch (Exception e) {
                log.warn("Failed creating MmapAddressToIdMap, falling back to ChunkedAddressToIdMap: {}", e.getMessage());
            }
        }

        if ("chunked".equals(override)) {
            log.info("MemoryGovernor: Selected ChunkedAddressToIdMap ({} objects, ~{}MB heap)",
                    count, estimatedArrayBytes / (1024 * 1024));
            return new ChunkedAddressToIdMap(classAddressToId, sortedInstanceAddresses, count, baseInstanceId);
        }

        boolean buckets = mode == MemoryMode.FAST || mode == MemoryMode.TIGHT;
        log.info("MemoryGovernor: Selected SortedAddressToIdMap{} ({} objects, ~{}MB heap)",
                buckets ? " with bucket table" : " without bucket table", count, estimatedArrayBytes / (1024 * 1024));
        return new SortedAddressToIdMap(classAddressToId, sortedInstanceAddresses, count, baseInstanceId, buckets);
    }

    /** True when the long cold per-object columns (class, size, file position) should be file-backed. */
    public boolean columnsFileBacked(MemoryMode mode) {
        return mode != MemoryMode.FAST;
    }

    /** True when the dominator's inbound CSR should be built in a memory-mapped file instead of the heap. */
    public boolean inboundFileBacked(MemoryMode mode) {
        return mode != MemoryMode.FAST;
    }

    /** Returns the mode that will be used for an ingestion of {@code objectCount} objects. */
    public MemoryMode modeFor(int objectCount) {
        return chooseMode(objectCount);
    }

    /**
     * Creates the optimal membership set for tracking ingested objects.
     * Default is high-speed BitSetMembershipSet (12.5MB for 100M objects, ~2.9ns/op).
     * RoaringMembershipSet can be selected via -Ddhp.membership.set=roaring.
     */
    public IObjectMembershipSet createMembershipSet(int estimatedObjects) {
        String mode = System.getProperty("dhp.membership.set", "bitset").toLowerCase();
        if ("roaring".equals(mode)) {
            log.info("MemoryGovernor: Selected RoaringMembershipSet (run-compressed bitmap)");
            return new RoaringMembershipSet();
        }
        log.info("MemoryGovernor: Selected BitSetMembershipSet (~{}KB memory, sub-nanosecond lookups)",
                Math.max(1, (estimatedObjects / 8) / 1024));
        return new BitSetMembershipSet(estimatedObjects);
    }

    /** Parses sizes such as 512M, 2G, 4GB, 1.5G or a raw byte count (K/M/G suffixes are powers of 1024). */
    public static long parseSize(String val) {
        if (val == null || val.isBlank()) return 0L;
        val = val.trim().toUpperCase();
        long multiplier = 1L;
        String numStr = val;
        if (val.endsWith("GB") || val.endsWith("G")) {
            multiplier = 1024L * 1024L * 1024L;
        } else if (val.endsWith("MB") || val.endsWith("M")) {
            multiplier = 1024L * 1024L;
        } else if (val.endsWith("KB") || val.endsWith("K")) {
            multiplier = 1024L;
        }
        numStr = val.replaceAll("[^0-9.]", "");
        if (numStr.contains(".")) {
            return (long) (Double.parseDouble(numStr) * multiplier);
        }
        return Long.parseLong(numStr) * multiplier;
    }

    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }

    /** Formats a byte count for logs (e.g. "2048 MB"). */
    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024 && bytes % (1024L * 1024 * 1024) == 0) {
            return (bytes / (1024L * 1024 * 1024)) + " GB";
        }
        if (bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0) {
            return (bytes / (1024L * 1024)) + " MB";
        }
        return bytes + " bytes";
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public long getDbPageCacheBytes() {
        return dbPageCacheBytes;
    }
}
