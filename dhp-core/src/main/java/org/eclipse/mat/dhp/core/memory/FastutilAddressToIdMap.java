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
}
