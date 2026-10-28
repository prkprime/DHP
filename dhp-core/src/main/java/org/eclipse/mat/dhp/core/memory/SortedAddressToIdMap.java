package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * High-speed in-memory sorted primitive long[] address mapping.
 *
 * <p>Uses 8 bytes per object for the sorted addresses plus a small bucket table (about 4 bytes per object)
 * that narrows every lookup to a handful of adjacent entries. Heap addresses are densely clustered, so a plain
 * binary search over hundreds of millions of bytes (about 26 dependent cache misses) becomes one table probe and
 * a search inside one or two cache lines.</p>
 */
public class SortedAddressToIdMap implements IAddressToIdMap {
    private final Long2IntOpenHashMap classAddressToId;
    private final ClassAddressIndex classAddresses;
    private final long[] instanceAddresses;
    private final int instanceCount;
    private final int baseInstanceId;
    private final int totalSize;

    // Bucket acceleration (disabled when bucketStart == null)
    private final int[] bucketStart;
    private final long minAddress;
    private final long maxAddress;
    private final int shift;

    public SortedAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedInstanceAddresses, int baseInstanceId) {
        this(classAddressToId, sortedInstanceAddresses, sortedInstanceAddresses != null ? sortedInstanceAddresses.length : 0, baseInstanceId);
    }

    public SortedAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedInstanceAddresses, int instanceCount, int baseInstanceId) {
        this(classAddressToId, sortedInstanceAddresses, instanceCount, baseInstanceId, true);
    }

    public SortedAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedInstanceAddresses, int instanceCount,
                                int baseInstanceId, boolean withBuckets) {
        this.classAddressToId = classAddressToId != null ? classAddressToId : new Long2IntOpenHashMap();
        this.classAddressToId.defaultReturnValue(-1);
        this.classAddresses = new ClassAddressIndex(this.classAddressToId, baseInstanceId);
        this.instanceAddresses = sortedInstanceAddresses != null ? sortedInstanceAddresses : new long[0];
        this.instanceCount = instanceCount;
        this.baseInstanceId = baseInstanceId;
        this.totalSize = this.classAddressToId.size() + this.instanceCount;

        int[] buckets = null;
        long min = 0;
        long max = -1;
        int s = 0;
        if (withBuckets && instanceCount >= 4096) {
            min = instanceAddresses[0];
            max = instanceAddresses[instanceCount - 1];
            long span = max - min;
            if (min >= 0 && span > 0) {
                long targetBuckets = Math.max(1024, instanceCount);
                while ((span >>> s) + 1 > targetBuckets) {
                    s++;
                }
                int nb = (int) ((span >>> s) + 1);
                buckets = new int[nb + 1];
                int pos = 0;
                for (int b = 0; b <= nb; b++) {
                    while (pos < instanceCount && ((instanceAddresses[pos] - min) >>> s) < b) {
                        pos++;
                    }
                    buckets[b] = pos;
                }
            }
        }
        this.bucketStart = buckets;
        this.minAddress = min;
        this.maxAddress = max;
        this.shift = s;
    }

    @Override
    public int get(long address) {
        if (instanceCount > 0) {
            int idx;
            if (bucketStart != null) {
                if (address < minAddress || address > maxAddress) {
                    idx = -1;
                } else {
                    int b = (int) ((address - minAddress) >>> shift);
                    idx = Arrays.binarySearch(instanceAddresses, bucketStart[b], bucketStart[b + 1], address);
                }
            } else {
                idx = Arrays.binarySearch(instanceAddresses, 0, instanceCount, address);
            }
            if (idx >= 0) {
                return baseInstanceId + idx;
            }
        }
        return classAddressToId.get(address);
    }

    @Override
    public boolean containsKey(long address) {
        return get(address) >= 0;
    }

    @Override
    public int size() {
        return totalSize;
    }

    @Override
    public long addressOf(int objectId) {
        if (objectId >= baseInstanceId) {
            int idx = objectId - baseInstanceId;
            return idx < instanceCount ? instanceAddresses[idx] : 0L;
        }
        return classAddresses.get(objectId);
    }
}
