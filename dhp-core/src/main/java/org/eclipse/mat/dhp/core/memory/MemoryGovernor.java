package org.eclipse.mat.dhp.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamically computes memory budgets and cache parameters based on JVM max memory,
 * available physical RAM, and user settings.
 */
public final class MemoryGovernor {
    private static final Logger log = LoggerFactory.getLogger(MemoryGovernor.class);

    private final long totalAllocatedBytes;
    private final int batchSize;
    private final int workerThreads;
    private final long dbPageCacheBytes;

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

        log.info("Initialized MemoryGovernor: Budget={}MB, BatchSize={}, Threads={}, DbCache={}MB",
                effectiveBudget / (1024 * 1024), batchSize, workerThreads, dbPageCacheBytes / (1024 * 1024));
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
        String override = System.getProperty("dhp.address.map", "").toLowerCase();
        int count = sortedInstanceAddresses != null ? sortedInstanceAddresses.length : 0;
        long estimatedArrayBytes = (long) count * 8L;

        if ("mmap".equals(override) || (totalAllocatedBytes < estimatedArrayBytes * 2 && count > 500_000)) {
            try {
                log.info("MemoryGovernor: Selected off-heap MmapAddressToIdMap (Heap budget = {}MB, Addresses = {}MB)",
                        totalAllocatedBytes / (1024 * 1024), estimatedArrayBytes / (1024 * 1024));
                return new MmapAddressToIdMap(classAddressToId, sortedInstanceAddresses, baseInstanceId, tempDir);
            } catch (Exception e) {
                log.warn("Failed creating MmapAddressToIdMap, falling back to ChunkedAddressToIdMap: {}", e.getMessage());
            }
        }

        if ("chunked".equals(override)) {
            log.info("MemoryGovernor: Selected ChunkedAddressToIdMap ({} objects, ~{}MB heap)",
                    count, estimatedArrayBytes / (1024 * 1024));
            return new ChunkedAddressToIdMap(classAddressToId, sortedInstanceAddresses, baseInstanceId);
        }

        log.info("MemoryGovernor: Selected SortedAddressToIdMap ({} objects, ~{}MB heap)",
                count, estimatedArrayBytes / (1024 * 1024));
        return new SortedAddressToIdMap(classAddressToId, sortedInstanceAddresses, baseInstanceId);
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

    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
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
