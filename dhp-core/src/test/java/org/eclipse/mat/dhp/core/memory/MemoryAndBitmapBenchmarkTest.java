package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;

public class MemoryAndBitmapBenchmarkTest {

    @Test
    public void benchmarkBitSetVsRoaringBitmap() {
        int N = 5_000_000;
        System.out.println("================================================================================");
        System.out.println("BENCHMARK 1: java.util.BitSet vs org.roaringbitmap.RoaringBitmap (N = " + N + ")");
        System.out.println("================================================================================");

        // 1. BitSet
        System.gc();
        long memBeforeBitSet = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long t0 = System.nanoTime();
        BitSet bitSet = new BitSet(N);
        for (int i = 0; i < N; i++) {
            bitSet.set(i);
        }
        long bitSetWriteTime = System.nanoTime() - t0;
        long memAfterBitSet = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long bitSetMem = Math.max(0, memAfterBitSet - memBeforeBitSet);

        // Read test
        t0 = System.nanoTime();
        int bitSetHits = 0;
        for (int i = 0; i < N; i++) {
            if (bitSet.get(i)) bitSetHits++;
        }
        long bitSetReadTime = System.nanoTime() - t0;

        System.out.printf("java.util.BitSet:      Write: %6.2f ms | Read: %6.2f ms | Memory: ~%d KB | Hits: %d%n",
                bitSetWriteTime / 1_000_000.0, bitSetReadTime / 1_000_000.0, (N / 8) / 1024, bitSetHits);

        // 2. RoaringBitmap
        System.gc();
        long memBeforeRoar = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        t0 = System.nanoTime();
        RoaringBitmap roaring = new RoaringBitmap();
        for (int i = 0; i < N; i++) {
            roaring.add(i);
        }
        roaring.runOptimize(); // Roaring run optimization for contiguous ranges
        long roarWriteTime = System.nanoTime() - t0;
        long memAfterRoar = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int roarBytes = roaring.getSizeInBytes();

        // Read test
        t0 = System.nanoTime();
        int roarHits = 0;
        for (int i = 0; i < N; i++) {
            if (roaring.contains(i)) roarHits++;
        }
        long roarReadTime = System.nanoTime() - t0;

        System.out.printf("RoaringBitmap:         Write: %6.2f ms | Read: %6.2f ms | Memory: %d KB | Hits: %d%n",
                roarWriteTime / 1_000_000.0, roarReadTime / 1_000_000.0, roarBytes / 1024, roarHits);

        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("Scaling projection to 100M objects:%n");
        System.out.printf(" - BitSet:       %d MB (flat 1 bit/item, ~%.1f ns/op)%n",
                (100_000_000L / 8) / (1024 * 1024), (double) bitSetReadTime / N);
        System.out.printf(" - RoaringBitmap: %d KB (run-compressed, ~%.1f ns/op)%n",
                (roarBytes / 1024), (double) roarReadTime / N);
        System.out.println("================================================================================\n");
    }

    @Test
    public void benchmarkAddressMapImplementations() {
        int N = 5_000_000;
        System.out.println("================================================================================");
        System.out.println("BENCHMARK 2: Long2IntOpenHashMap vs Sorted Arrays (N = " + N + ")");
        System.out.println("================================================================================");

        // Generate synthetic addresses (64-bit addresses, monotonically increasing with gaps)
        long[] sampleAddrs = new long[N];
        Random rnd = new Random(42);
        long curr = 0x700000000L;
        for (int i = 0; i < N; i++) {
            curr += (rnd.nextInt(64) + 16);
            sampleAddrs[i] = curr;
        }

        // Test 1: Fastutil Long2IntOpenHashMap
        System.gc();
        long memBeforeMap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long t0 = System.nanoTime();
        Long2IntOpenHashMap map = new Long2IntOpenHashMap(N, 0.75f);
        map.defaultReturnValue(-1);
        for (int i = 0; i < N; i++) {
            map.put(sampleAddrs[i], i);
        }
        long mapBuildTime = System.nanoTime() - t0;
        long memAfterMap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        t0 = System.nanoTime();
        int mapFound = 0;
        for (int i = 0; i < N; i++) {
            if (map.get(sampleAddrs[i]) >= 0) mapFound++;
        }
        long mapLookupTime = System.nanoTime() - t0;

        System.out.printf("Long2IntOpenHashMap: Build: %6.2f ms | Lookup: %6.2f ms | Est. 100M Heap: ~3.2 GB - 4.8 GB (Rehash OOM)%n",
                mapBuildTime / 1_000_000.0, mapLookupTime / 1_000_000.0);

        // Test 2: Flat Sorted Array with binary search
        long[] sortedArray = Arrays.copyOf(sampleAddrs, N);
        System.gc();
        t0 = System.nanoTime();
        Arrays.sort(sortedArray);
        long arraySortTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        int arrayFound = 0;
        for (int i = 0; i < N; i++) {
            int idx = Arrays.binarySearch(sortedArray, sampleAddrs[i]);
            if (idx >= 0) arrayFound++;
        }
        long arrayLookupTime = System.nanoTime() - t0;

        System.out.printf("Sorted Flat Array:   Build: %6.2f ms | Lookup: %6.2f ms | Est. 100M Heap: 800 MB (ZERO rehash)%n",
                arraySortTime / 1_000_000.0, arrayLookupTime / 1_000_000.0);

        // Test 3: Chunked Array (1M longs per chunk = 8MB chunks, eliminating humongous G1 regions)
        int chunkSize = 1024 * 1024;
        int numChunks = (N + chunkSize - 1) / chunkSize;
        long[][] chunked = new long[numChunks][];
        t0 = System.nanoTime();
        for (int c = 0; c < numChunks; c++) {
            int len = Math.min(chunkSize, N - c * chunkSize);
            chunked[c] = new long[len];
            System.arraycopy(sortedArray, c * chunkSize, chunked[c], 0, len);
        }
        long chunkBuildTime = System.nanoTime() - t0;

        t0 = System.nanoTime();
        int chunkFound = 0;
        for (int i = 0; i < N; i++) {
            long key = sampleAddrs[i];
            int low = 0;
            int high = N - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                long midVal = chunked[mid / chunkSize][mid % chunkSize];
                if (midVal < key) low = mid + 1;
                else if (midVal > key) high = mid - 1;
                else {
                    chunkFound++;
                    break;
                }
            }
        }
        long chunkLookupTime = System.nanoTime() - t0;

        System.out.printf("Chunked Sorted Array:Build: %6.2f ms | Lookup: %6.2f ms | Est. 100M Heap: 800 MB (No humongous fragmentation)%n",
                chunkBuildTime / 1_000_000.0, chunkLookupTime / 1_000_000.0);
        System.out.println("================================================================================");
    }
}
