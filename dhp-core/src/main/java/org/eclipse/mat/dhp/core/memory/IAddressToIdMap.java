package org.eclipse.mat.dhp.core.memory;

/**
 * High-performance abstraction for mapping 64-bit object JVM addresses to 32-bit MAT object IDs.
 * Implementations provide bounded memory usage without uncontrolled rehashing allocations.
 */
public interface IAddressToIdMap extends AutoCloseable {

    /**
     * Look up the 32-bit object ID for a 64-bit JVM address.
     *
     * @param address 64-bit JVM object pointer
     * @return 0-indexed object ID, or -1 if the address is not registered
     */
    int get(long address);

    /**
     * Check if the map contains the given address.
     */
    boolean containsKey(long address);

    /**
     * Total number of registered objects in the mapping.
     */
    int size();

    @Override
    default void close() {
        // Default no-op for purely in-memory maps
    }
}
