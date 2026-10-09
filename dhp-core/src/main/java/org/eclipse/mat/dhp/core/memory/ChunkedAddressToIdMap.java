package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Chunked sorted long[][] address mapping using 8 MB chunks (1,048,576 longs).
 * Completely eliminates JVM humongous object allocation warnings in G1 GC.
 */
public class ChunkedAddressToIdMap implements IAddressToIdMap {
    public static final int CHUNK_SHIFT = 20; // 1M longs = 8MB per chunk
    public static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    public static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private final Long2IntOpenHashMap classAddressToId;
    private final long[][] chunks;
    private final int instanceCount;
    private final int baseInstanceId;
    private final int totalSize;

    public ChunkedAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedAddresses, int baseInstanceId) {
        this.classAddressToId = classAddressToId != null ? classAddressToId : new Long2IntOpenHashMap();
        this.instanceCount = sortedAddresses != null ? sortedAddresses.length : 0;
        this.baseInstanceId = baseInstanceId;
        this.totalSize = this.classAddressToId.size() + instanceCount;

        if (instanceCount > 0) {
            int numChunks = (instanceCount + CHUNK_SIZE - 1) >>> CHUNK_SHIFT;
            this.chunks = new long[numChunks][];
            for (int c = 0; c < numChunks; c++) {
                int offset = c << CHUNK_SHIFT;
                int len = Math.min(CHUNK_SIZE, instanceCount - offset);
                this.chunks[c] = new long[len];
                System.arraycopy(sortedAddresses, offset, this.chunks[c], 0, len);
            }
        } else {
            this.chunks = new long[0][];
        }
    }

    @Override
    public int get(long address) {
        if (classAddressToId.containsKey(address)) {
            return classAddressToId.get(address);
        }
        if (instanceCount == 0) return -1;

        int low = 0;
        int high = instanceCount - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = chunks[mid >>> CHUNK_SHIFT][mid & CHUNK_MASK];
            if (midVal < address) {
                low = mid + 1;
            } else if (midVal > address) {
                high = mid - 1;
            } else {
                return baseInstanceId + mid;
            }
        }
        return -1;
    }

    @Override
    public boolean containsKey(long address) {
        return get(address) >= 0;
    }

    @Override
    public int size() {
        return totalSize;
    }
}
