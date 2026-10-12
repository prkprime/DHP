package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Reverse (object ID -> address) lookup table for the small low-ID range occupied by class objects
 * and the synthetic system class loader.
 */
final class ClassAddressIndex {
    private final long[] addressById;

    ClassAddressIndex(Long2IntOpenHashMap classAddressToId, int baseInstanceId) {
        this.addressById = new long[Math.max(0, baseInstanceId)];
        for (Long2IntMap.Entry e : classAddressToId.long2IntEntrySet()) {
            int id = e.getIntValue();
            if (id >= 0 && id < addressById.length) {
                addressById[id] = e.getLongKey();
            }
        }
    }

    long get(int id) {
        return (id >= 0 && id < addressById.length) ? addressById[id] : 0L;
    }
}
