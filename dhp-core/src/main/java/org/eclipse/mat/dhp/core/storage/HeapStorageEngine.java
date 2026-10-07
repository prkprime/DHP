package org.eclipse.mat.dhp.core.storage;

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

    // Index queries needed for MAT snapshot operations
    int getObjectCount() throws SQLException;
    long getObjectAddress(int objectId) throws SQLException;
    int getObjectIdByAddress(long address) throws SQLException;
    long getObjectClassId(int objectId) throws SQLException;
    long getObjectUsedSize(int objectId) throws SQLException;
    long getObjectFilePosition(int objectId) throws SQLException;

    int[] getOutboundReferences(int objectId) throws SQLException;
    int[] getInboundReferences(int objectId) throws SQLException;

    List<HeapRecords.GcRootRecord> getGcRoots() throws SQLException;

    // Dominator tree support
    void saveDominatorTreeBatch(List<DominatorNode> dominators) throws SQLException;
    int getDominatorId(int objectId) throws SQLException;
    long getRetainedSize(int objectId) throws SQLException;
    int[] getImmediateDominatedIds(int objectId) throws SQLException;

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
}
