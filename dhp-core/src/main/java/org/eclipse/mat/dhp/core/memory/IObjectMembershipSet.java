package org.eclipse.mat.dhp.core.memory;

/**
 * Membership set abstraction for tracking ingested object IDs (0 <= id < N)
 * with constant, predictable memory overhead.
 */
public interface IObjectMembershipSet {

    /**
     * Check if the object ID has been recorded in the set.
     */
    boolean contains(int objectId);

    /**
     * Add the object ID to the set.
     */
    void add(int objectId);

    /**
     * Number of elements currently in the set.
     */
    int size();

    /**
     * Clear all elements from the set.
     */
    void clear();
}
