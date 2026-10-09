package org.eclipse.mat.dhp.core.memory;

import java.util.BitSet;

/**
 * Standard java.util.BitSet-backed membership set.
 * Uses strictly 1 bit per object (only 12.5 MB for 100M objects).
 * Provides sub-nanosecond single CPU-instruction lookup.
 */
public class BitSetMembershipSet implements IObjectMembershipSet {
    private final BitSet bitSet;
    private int count;

    public BitSetMembershipSet(int estimatedSize) {
        this.bitSet = new BitSet(Math.max(64, estimatedSize));
        this.count = 0;
    }

    @Override
    public boolean contains(int objectId) {
        return objectId >= 0 && bitSet.get(objectId);
    }

    @Override
    public void add(int objectId) {
        if (objectId >= 0 && !bitSet.get(objectId)) {
            bitSet.set(objectId);
            count++;
        }
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public void clear() {
        bitSet.clear();
        count = 0;
    }
}
