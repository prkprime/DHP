package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Fastutil Long2IntOpenHashMap adapter for small heaps or backwards compatibility.
 */
public class FastutilAddressToIdMap implements IAddressToIdMap {
    private final Long2IntOpenHashMap map;

    public FastutilAddressToIdMap(Long2IntOpenHashMap map) {
        this.map = map != null ? map : new Long2IntOpenHashMap();
        this.map.defaultReturnValue(-1);
    }

    @Override
    public int get(long address) {
        return map.get(address);
    }

    @Override
    public boolean containsKey(long address) {
        return map.containsKey(address);
    }

    @Override
    public int size() {
        return map.size();
    }

    private long[] reverse;

    @Override
    public synchronized long addressOf(int objectId) {
        if (reverse == null) {
            int max = -1;
            for (int id : map.values()) {
                max = Math.max(max, id);
            }
            reverse = new long[max + 1];
            for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : map.long2IntEntrySet()) {
                reverse[e.getIntValue()] = e.getLongKey();
            }
        }
        return objectId >= 0 && objectId < reverse.length ? reverse[objectId] : 0L;
    }
}
