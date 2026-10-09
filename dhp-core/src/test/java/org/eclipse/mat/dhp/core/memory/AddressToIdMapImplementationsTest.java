package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class AddressToIdMapImplementationsTest {

    @Test
    void testSortedAddressToIdMap() {
        Long2IntOpenHashMap classMap = new Long2IntOpenHashMap();
        classMap.defaultReturnValue(-1);
        classMap.put(0L, 0); // system classloader
        classMap.put(0x1000L, 1); // Class 1
        classMap.put(0x2000L, 2); // Class 2

        long[] instances = new long[]{0x3000L, 0x4000L, 0x5000L, 0x6000L};
        int baseInstanceId = 3;

        SortedAddressToIdMap map = new SortedAddressToIdMap(classMap, instances, baseInstanceId);
        assertThat(map.size()).isEqualTo(7);

        // System classloader and classes
        assertThat(map.get(0L)).isEqualTo(0);
        assertThat(map.get(0x1000L)).isEqualTo(1);
        assertThat(map.get(0x2000L)).isEqualTo(2);

        // Instances
        assertThat(map.get(0x3000L)).isEqualTo(3);
        assertThat(map.get(0x4000L)).isEqualTo(4);
        assertThat(map.get(0x5000L)).isEqualTo(5);
        assertThat(map.get(0x6000L)).isEqualTo(6);

        // ContainsKey
        assertThat(map.containsKey(0L)).isTrue();
        assertThat(map.containsKey(0x5000L)).isTrue();
        assertThat(map.containsKey(0x9999L)).isFalse();

        // Non-existent
        assertThat(map.get(0x9999L)).isEqualTo(-1);
    }

    @Test
    void testChunkedAddressToIdMapSpanningMultipleChunks() {
        Long2IntOpenHashMap classMap = new Long2IntOpenHashMap();
        classMap.defaultReturnValue(-1);
        classMap.put(0L, 0);
        classMap.put(0x100L, 1);

        // Create an array that crosses ChunkedAddressToIdMap.CHUNK_SIZE boundary (1,048,576)
        int size = ChunkedAddressToIdMap.CHUNK_SIZE + 500;
        long[] instances = new long[size];
        for (int i = 0; i < size; i++) {
            instances[i] = 0x1000L + (long) i * 16L;
        }

        int baseInstanceId = 2;
        ChunkedAddressToIdMap chunkedMap = new ChunkedAddressToIdMap(classMap, instances, baseInstanceId);
        assertThat(chunkedMap.size()).isEqualTo(size + 2);

        // Check classes
        assertThat(chunkedMap.get(0L)).isEqualTo(0);
        assertThat(chunkedMap.get(0x100L)).isEqualTo(1);

        // Check start of chunk 0
        assertThat(chunkedMap.get(instances[0])).isEqualTo(baseInstanceId);
        assertThat(chunkedMap.get(instances[10])).isEqualTo(baseInstanceId + 10);

        // Check boundary of chunk 0 and chunk 1
        int boundaryIdx = ChunkedAddressToIdMap.CHUNK_SIZE;
        assertThat(chunkedMap.get(instances[boundaryIdx - 1])).isEqualTo(baseInstanceId + boundaryIdx - 1);
        assertThat(chunkedMap.get(instances[boundaryIdx])).isEqualTo(baseInstanceId + boundaryIdx);
        assertThat(chunkedMap.get(instances[boundaryIdx + 1])).isEqualTo(baseInstanceId + boundaryIdx + 1);

        // Check end of chunk 1
        assertThat(chunkedMap.get(instances[size - 1])).isEqualTo(baseInstanceId + size - 1);

        // Non-existent
        assertThat(chunkedMap.get(0x9999L)).isEqualTo(-1);
        assertThat(chunkedMap.containsKey(0x9999L)).isFalse();
    }

    @Test
    void testMmapAddressToIdMap(@TempDir Path tempDir) throws Exception {
        Long2IntOpenHashMap classMap = new Long2IntOpenHashMap();
        classMap.defaultReturnValue(-1);
        classMap.put(0L, 0);
        classMap.put(0x200L, 1);

        int count = 20_000;
        long[] instances = new long[count];
        for (int i = 0; i < count; i++) {
            instances[i] = 0x500000L + (long) i * 8L;
        }

        int baseInstanceId = 2;
        try (MmapAddressToIdMap mmapMap = new MmapAddressToIdMap(classMap, instances, baseInstanceId, tempDir.toFile())) {
            assertThat(mmapMap.size()).isEqualTo(count + 2);

            assertThat(mmapMap.get(0L)).isEqualTo(0);
            assertThat(mmapMap.get(0x200L)).isEqualTo(1);
            assertThat(mmapMap.get(instances[0])).isEqualTo(baseInstanceId);
            assertThat(mmapMap.get(instances[1000])).isEqualTo(baseInstanceId + 1000);
            assertThat(mmapMap.get(instances[count - 1])).isEqualTo(baseInstanceId + count - 1);

            assertThat(mmapMap.containsKey(instances[500])).isTrue();
            assertThat(mmapMap.containsKey(0x123L)).isFalse();
            assertThat(mmapMap.get(0x123L)).isEqualTo(-1);
        }
    }

    @Test
    void testMembershipSetsParity() {
        int N = 50_000;
        BitSetMembershipSet bitSet = new BitSetMembershipSet(N);
        RoaringMembershipSet roaring = new RoaringMembershipSet();

        for (int i = 0; i < N; i += 2) {
            bitSet.add(i);
            roaring.add(i);
        }

        assertThat(bitSet.size()).isEqualTo(N / 2);
        assertThat(roaring.size()).isEqualTo(N / 2);

        for (int i = 0; i < N; i++) {
            boolean expected = (i % 2 == 0);
            assertThat(bitSet.contains(i)).isEqualTo(expected);
            assertThat(roaring.contains(i)).isEqualTo(expected);
        }

        // Test boundary conditions and negative values
        assertThat(bitSet.contains(-1)).isFalse();
        assertThat(roaring.contains(-1)).isFalse();

        bitSet.clear();
        roaring.clear();
        assertThat(bitSet.size()).isEqualTo(0);
        assertThat(roaring.size()).isEqualTo(0);
    }
}
