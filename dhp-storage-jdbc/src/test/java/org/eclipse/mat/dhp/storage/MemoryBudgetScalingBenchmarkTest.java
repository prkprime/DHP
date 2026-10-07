package org.eclipse.mat.dhp.storage;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryBudgetScalingBenchmarkTest {

    @Test
    void testMemoryGovernorDynamicScaling() {
        // Test constrained 128MB budget
        MemoryGovernor constrainedGov = new MemoryGovernor(128 * 1024 * 1024L, 2);
        assertThat(constrainedGov.getBatchSize()).isLessThanOrEqualTo(50_000);
        assertThat(constrainedGov.getDbPageCacheBytes()).isLessThanOrEqualTo(40 * 1024 * 1024L);

        // Test medium 1GB budget
        MemoryGovernor mediumGov = new MemoryGovernor(1024 * 1024 * 1024L, 4);
        assertThat(mediumGov.getBatchSize()).isEqualTo(100_000);
        assertThat(mediumGov.getDbPageCacheBytes()).isGreaterThan(constrainedGov.getDbPageCacheBytes());

        // Test large 8GB budget
        MemoryGovernor largeGov = new MemoryGovernor(8L * 1024 * 1024 * 1024L, 8);
        assertThat(largeGov.getBatchSize()).isGreaterThanOrEqualTo(mediumGov.getBatchSize());
        assertThat(largeGov.getDbPageCacheBytes()).isGreaterThan(mediumGov.getDbPageCacheBytes());
    }
}
