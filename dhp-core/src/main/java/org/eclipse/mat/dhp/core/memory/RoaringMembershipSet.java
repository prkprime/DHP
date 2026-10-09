package org.eclipse.mat.dhp.core.memory;

import org.roaringbitmap.RoaringBitmap;

/**
 * Compressed bitmap implementation backed by RoaringBitmap.
 * Automatically run-compresses contiguous ID sequences to virtually zero memory overhead.
 */
public class RoaringMembershipSet implements IObjectMembershipSet {
    private final RoaringBitmap bitmap = new RoaringBitmap();

    public RoaringMembershipSet() {}

    @Override
    public boolean contains(int objectId) {
        return objectId >= 0 && bitmap.contains(objectId);
    }

    @Override
    public void add(int objectId) {
        if (objectId >= 0) {
            bitmap.add(objectId);
        }
    }

    @Override
    public int size() {
        return bitmap.getCardinality();
    }

    @Override
    public void clear() {
        bitmap.clear();
    }
}
