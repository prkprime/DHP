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

    /**
     * Reverse lookup: the 64-bit JVM address of the object with the given 32-bit ID.
     * Class objects (and the synthetic system class loader at address 0) occupy the low ID range,
     * instances follow in ascending address order.
     */
    long addressOf(int objectId);

    @Override
    default void close() {
        // Default no-op for purely in-memory maps
    }
}
