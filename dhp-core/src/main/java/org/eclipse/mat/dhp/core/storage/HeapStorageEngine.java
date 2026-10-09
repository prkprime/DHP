package org.eclipse.mat.dhp.core.storage;

import org.eclipse.mat.dhp.core.graph.CsrGraph;
import org.eclipse.mat.dhp.core.model.HeapRecords;

import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * Storage interface abstracting persistence and retrieval of parsed heap data.
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

    void insertObjectsBatch(List<RawObjectRecord> objects) throws SQLException;
    void insertOutboundReferencesBatch(List<ReferenceEdge> edges) throws SQLException;
    void insertGcRootsBatch(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException;

    void finishIngestion() throws SQLException;

    // Database Garbage Collection
    default int runGarbageCollection() throws SQLException { return 0; }

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
    void saveDominatorTreeBatch(List<DominatorNode> dominators) throws SQLException;
    default void finishDominatorTree() throws SQLException {}
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
    default int[][] loadAllOutboundReferences(int objectCount) throws SQLException {
        CsrGraph csr = loadOutboundCsr(objectCount);
        int[][] adj = new int[objectCount][];
        for (int i = 0; i < objectCount; i++) {
            int deg = csr.degree(i);
            adj[i] = new int[deg];
            for (int k = 0; k < deg; k++) {
                adj[i][k] = csr.getEdge(i, k);
            }
        }
        return adj;
    }
    default CsrGraph loadOutboundCsr(int objectCount) throws SQLException {
        return new CsrGraph(new int[objectCount + 1], new int[0]);
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

    record RawObjectRecord(
            int objectId,
            long objectAddress,
            long classId,
            long usedSize,
            long filePosition,
            boolean isArray
    ) {}

    record ReferenceEdge(
            int fromObjectId,
            int seq,
            int toObjectId
    ) {}

    record DominatorNode(
            int objectId,
            int dominatorId,
            long retainedSize
    ) {}

    record ClassStats(
            int classObjId,
            int instanceCount,
            long totalSize
    ) {}
}
