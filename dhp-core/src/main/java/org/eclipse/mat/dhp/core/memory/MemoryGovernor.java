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
