package org.eclipse.mat.dhp.core.storage;

import org.eclipse.mat.dhp.core.graph.HeapGraph;
import org.eclipse.mat.dhp.core.model.HeapRecords;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * Storage interface abstracting persistence and retrieval of parsed heap data.
 *
 * <p>Ingestion is bulk oriented: the parser builds a {@link HeapGraph} in memory and persists it with a handful of
 * calls ({@link #saveObjects}, {@link #saveOutboundEdges}, {@link #saveInboundEdges}, {@link #saveDominators}) instead
 * of inserting one row per object or edge.</p>
 */
public interface HeapStorageEngine extends Closeable {

    boolean hasExistingTables() throws SQLException;
    void dropExistingTables() throws SQLException;
    void initializeSchema() throws SQLException;

    void saveSnapshotInfo(String key, String value) throws SQLException;
    String getSnapshotInfo(String key) throws SQLException;

    void saveClasses(Collection<HeapRecords.ClassRecord> classes) throws SQLException;
    List<HeapRecords.ClassRecord> getAllClasses() throws SQLException;
    HeapRecords.ClassRecord getClassById(long classId) throws SQLException;

    /** Persists the per-object columns: address, class object ID, shallow size, file position and array flag. */
    void saveObjects(HeapGraph graph) throws SQLException;

    /** Persists the outbound reference lists (class reference first, then field/element references in order). */
    void saveOutboundEdges(HeapGraph graph) throws SQLException;

    /**
     * Persists the inbound reference lists from a CSR layout. Entries equal to {@code ignoredSource}
     * (the artificial super root) are skipped.
     */
    void saveInboundEdges(int[] head, int[] to, int objectCount, int ignoredSource) throws SQLException;

    /**
     * Persists dominator results: immediate dominator per object (-1 for top-level/unreachable objects) and retained
     * sizes. Dominated-children lists (ordered by retained size, descending) are derived and stored as well.
     */
    void saveDominators(int[] immediateDominator, long[] retainedSizes, int objectCount) throws SQLException;

    /** GC roots with object and thread object IDs already resolved. */
    void insertGcRootsBatch(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException;

    /** Adds GC roots without removing existing ones; a zero object address is resolved from the stored objects. */
    default void appendGcRoots(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException {}

    void finishIngestion() throws SQLException;

    // Index queries needed for MAT snapshot operations
    int getObjectCount() throws SQLException;
    long getObjectAddress(int objectId) throws SQLException;
    int getObjectIdByAddress(long address) throws SQLException;
    long getObjectClassId(int objectId) throws SQLException;
    long getObjectUsedSize(int objectId) throws SQLException;
    default long getTotalHeapSize() throws SQLException { return 0L; }
    long getObjectFilePosition(int objectId) throws SQLException;

    int[] getOutboundReferences(int objectId) throws SQLException;
    int[] getInboundReferences(int objectId) throws SQLException;

    List<HeapRecords.GcRootRecord> getGcRoots() throws SQLException;

    // Dominator tree support
    int getDominatorId(int objectId) throws SQLException;
    long getRetainedSize(int objectId) throws SQLException;
    default int getObjectIdByRetainedSize(long retainedSize) throws SQLException { return -1; }
    int[] getImmediateDominatedIds(int objectId) throws SQLException;

    // Bulk graph & class instance queries
    default boolean isArray(int objectId) throws SQLException { return false; }
    default boolean[] getAllArrayFlags(int objectCount) throws SQLException { return new boolean[objectCount]; }
    default void populateArrayBitField(java.util.function.IntConsumer setBit) throws SQLException {
        boolean[] flags = getAllArrayFlags(getObjectCount());
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) setBit.accept(i);
        }
    }
    default long[] loadAllObjectUsedSizes(int objectCount) throws SQLException { return new long[objectCount]; }
    default int[] getObjectsByClassId(int classObjId) throws SQLException { return new int[0]; }
    default java.util.Map<Integer, ClassStats> getClassStats() throws SQLException { return java.util.Collections.emptyMap(); }
    default void saveClassStats(Collection<ClassStats> stats) throws SQLException {}
    default void updateClassesMetadata(Collection<ResolvedClassMetadata> classes) throws SQLException {}

    record ResolvedClassMetadata(
            long classAddress,
            int classObjId,
            int superClassObjId,
            int classLoaderObjId,
            long usedSize
    ) {}

    /** Every class of the dump in CLASS_DUMP order, including those removed with the unreachable objects: {seq, old id, address, new id or -1}. */
    default void saveClassOrder(java.util.List<long[]> rows) throws SQLException {}

    /** Rows saved by {@link #saveClassOrder}, ordered by dump sequence; empty if the database predates them. */
    default java.util.List<long[]> getClassOrder() throws SQLException { return java.util.List.of(); }

    record ClassStats(
            int classObjId,
            int instanceCount,
            long totalSize
    ) {}
}
