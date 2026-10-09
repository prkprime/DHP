package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * High-speed in-memory sorted primitive long[] address mapping.
 * Uses exact binary search (O(log N)) with zero value-array overhead.
 * Memory is strictly 8 bytes per object (800 MB for 100M objects).
 */
public class SortedAddressToIdMap implements IAddressToIdMap {
    private final Long2IntOpenHashMap classAddressToId;
    private final long[] instanceAddresses;
    private final int baseInstanceId;
    private final int totalSize;

    public SortedAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedInstanceAddresses, int baseInstanceId) {
        this.classAddressToId = classAddressToId != null ? classAddressToId : new Long2IntOpenHashMap();
        this.instanceAddresses = sortedInstanceAddresses != null ? sortedInstanceAddresses : new long[0];
        this.baseInstanceId = baseInstanceId;
        this.totalSize = this.classAddressToId.size() + this.instanceAddresses.length;
    }

    @Override
    public int get(long address) {
        if (classAddressToId.containsKey(address)) {
            return classAddressToId.get(address);
        }
        if (instanceAddresses.length > 0) {
            int idx = Arrays.binarySearch(instanceAddresses, address);
            if (idx >= 0) {
                return baseInstanceId + idx;
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
