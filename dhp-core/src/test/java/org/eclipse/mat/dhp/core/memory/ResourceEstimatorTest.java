package org.eclipse.mat.dhp.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceEstimatorTest {

    @Test
    public void testSmallHeapDumpEstimate() {
        // Scaled test dump: 140MB file, 180,000 objects, 1,200 classes, 500 GC roots
        var estimate = ResourceEstimator.estimate(140L * 1024 * 1024, 180_000, 1_200, 500);

        assertNotNull(estimate);
        assertEquals(180_000, estimate.uniqueObjectCount());
        assertEquals(1_200, estimate.classCount());
        assertEquals(500, estimate.gcRootCount());
        assertTrue(estimate.estimatedPeakHeapBytes() > 0);
        assertTrue(estimate.recommendedMinHeapBytes() >= 512L * 1024 * 1024);
        assertNotNull(estimate.recommendedXmx());
        assertTrue(estimate.estimatedDiskBytes() > 0);
        assertTrue(estimate.banner().contains("DHP PRE-FLIGHT RESOURCE ESTIMATE"));
        assertTrue(estimate.banner().contains("RECOMMENDED MINIMUM HEAP"));
    }

    @Test
    public void testLarge11GbHeapDumpEstimate() {
        // Massive 11GB dump: 47.9M objects, 2,009 classes, 2,000 GC roots
        var estimate = ResourceEstimator.estimate(11_000_000_000L, 47_877_279, 2_009, 2_000);

        assertNotNull(estimate);
        // Peak heap should predict ~3.2GB to 3.5GB
        long peakMb = estimate.estimatedPeakHeapBytes() / (1024 * 1024);
        assertTrue(peakMb >= 2500 && peakMb <= 4500, "Estimated peak heap: " + peakMb + " MB");
        // Recommended Xmx should be 4GB (-Xmx4g)
        assertEquals("-Xmx4g", estimate.recommendedXmx());
        // Estimated disk space should be 12GB to 18GB
        long diskGb = estimate.estimatedDiskBytes() / (1024 * 1024 * 1024);
        assertTrue(diskGb >= 10 && diskGb <= 20, "Estimated disk space: " + diskGb + " GB");
    }
}
