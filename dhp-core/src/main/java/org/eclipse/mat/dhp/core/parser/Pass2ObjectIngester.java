package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.memory.IAddressToIdMap;
import org.eclipse.mat.dhp.core.memory.IObjectMembershipSet;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.memory.SortedAddressToIdMap;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass 2 Stream Parser:
 * Streams object instances and arrays from HPROF directly into JDBC storage batches.
 * Dynamically adjusts batch sizing according to MemoryGovernor.
 */
public class Pass2ObjectIngester implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Pass2ObjectIngester.class);

    private final Pass1ScanParser pass1;
    private final HeapStorageEngine storage;
    private final MemoryGovernor governor;

    private IAddressToIdMap addressToId;
    private final Map<Long, List<HeapRecords.ClassRecord>> classHierarchyCache = new HashMap<>();

    public Pass2ObjectIngester(Pass1ScanParser pass1, HeapStorageEngine storage, MemoryGovernor governor) {
        this.pass1 = pass1;
        this.storage = storage;
        this.governor = governor;
        this.addressToId = new SortedAddressToIdMap(new Long2IntOpenHashMap(), new long[0], 0);
    }

    public void ingest(File file) throws IOException, SQLException {
        log.info("Starting Pass 2 Object Ingestion into Database...");

        // Save metadata & classes from Pass 1
        storage.saveSnapshotInfo("version", pass1.getHeader().version());
        storage.saveSnapshotInfo("idSize", String.valueOf(pass1.getHeader().idSize()));
        storage.saveSnapshotInfo("creationTime", String.valueOf(pass1.getHeader().creationTime()));
        storage.saveClasses(pass1.getClasses().values());
        List<HeapRecords.GcRootRecord> allRoots = new ArrayList<>(pass1.getGcRoots());
        boolean hasZero = false;
        for (var r : allRoots) {
            if (r.objectAddress() == 0L) {
                hasZero = true;
                break;
            }
        }
        if (!hasZero) {
            allRoots.add(new HeapRecords.GcRootRecord(0L, 0L, 1, 0L));
        }
        storage.insertGcRootsBatch(allRoots);

        int idSize = pass1.getHeader().idSize();
        int batchSize = governor.getBatchSize();

        List<HeapStorageEngine.RawObjectRecord> objectBatch = new ArrayList<>(batchSize);
        List<HeapStorageEngine.ReferenceEdge> edgeBatch = new ArrayList<>(batchSize);

        int currentObjectId = 0;
        Long2IntOpenHashMap classAddressToId = new Long2IntOpenHashMap();
        classAddressToId.defaultReturnValue(-1);

        // 1. Pre-register system classloader at address 0
        int systemClassLoaderObjId = currentObjectId++;
        classAddressToId.put(0L, systemClassLoaderObjId);

        // 2. Pre-register all classes as heap objects and map address -> objectId
        long javaLangClassAddress = 0L;
        long classLoaderClassAddress = 0L;
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if ("java.lang.Class".equals(cls.name())) {
                javaLangClassAddress = cls.classId();
            }
            if ("java.lang.ClassLoader".equals(cls.name())) {
                classLoaderClassAddress = cls.classId();
            }
        }

        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if (!classAddressToId.containsKey(cls.classId())) {
                int classObjId = currentObjectId++;
                classAddressToId.put(cls.classId(), classObjId);
            }
        }

        long classLoaderInstanceSize = 0;
        long javaLangClassInstanceSize = 0;
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if ("java.lang.ClassLoader".equals(cls.name())) {
                classLoaderInstanceSize = cls.instanceSize();
            }
            if ("java.lang.Class".equals(cls.name())) {
                javaLangClassInstanceSize = cls.instanceSize();
            }
        }
        if (classLoaderInstanceSize == 0) classLoaderInstanceSize = 4L * idSize;
        if (javaLangClassInstanceSize == 0) javaLangClassInstanceSize = 4L * idSize;

        int javaLangClassObjId = (javaLangClassAddress != 0 && classAddressToId.containsKey(javaLangClassAddress))
                ? classAddressToId.get(javaLangClassAddress)
                : -1;
        int classLoaderClassObjId = (classLoaderClassAddress != 0 && classAddressToId.containsKey(classLoaderClassAddress))
                ? classAddressToId.get(classLoaderClassAddress)
                : (javaLangClassObjId != -1 ? javaLangClassObjId : 0);

        int baseInstanceId = currentObjectId;
        long[] sortedInstances = pass1.getSortedInstanceAddresses();
        File tempDir = file.getAbsoluteFile().getParentFile();

        this.addressToId = governor.createAddressMap(
                classAddressToId,
                sortedInstances,
                baseInstanceId,
                tempDir
        );

        int totalExpectedObjects = addressToId.size();
        IObjectMembershipSet writtenObjects = governor.createMembershipSet(totalExpectedObjects);
        IObjectMembershipSet processedClassDumps = governor.createMembershipSet(baseInstanceId + 16);

        writtenObjects.add(systemClassLoaderObjId);

        objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                systemClassLoaderObjId, 0L, classLoaderClassObjId, classLoaderInstanceSize, 0L, false
        ));
        int sysSeq = 0;
        if (classLoaderClassObjId >= 0) {
            edgeBatch.add(new HeapStorageEngine.ReferenceEdge(systemClassLoaderObjId, sysSeq++, classLoaderClassObjId));
        }
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if (cls.classLoaderId() == 0L) {
                int cid = addressToId.get(cls.classId());
                if (cid >= 0) {
                    edgeBatch.add(new HeapStorageEngine.ReferenceEdge(systemClassLoaderObjId, sysSeq++, cid));
                }
            }
        }

        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            int classObjId = addressToId.get(cls.classId());
            int classTypeObjId = javaLangClassObjId != -1 ? javaLangClassObjId : classObjId;
            long staticFieldsSize = 0;
            for (var sf : cls.staticFields()) {
                staticFieldsSize += (sf.type() == HprofConstants.Type.OBJECT) ? idSize : HprofConstants.Type.sizeOf(sf.type(), idSize);
            }
            long size = javaLangClassInstanceSize + alignUpToX(staticFieldsSize, 8);
            writtenObjects.add(classObjId);
            objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                    classObjId, cls.classId(), classTypeObjId, size, 0L, false
            ));
            if (objectBatch.size() >= batchSize) {
                storage.insertObjectsBatch(objectBatch);
                objectBatch.clear();
            }
        }

        log.info("Pass 2 Pre-registration complete: classes={}, instances={}, totalExpectedObjects={}",
                pass1.getClasses().size(), sortedInstances.length, totalExpectedObjects);

        try (HprofBinaryReader reader = new HprofBinaryReader(file)) {
            reader.readHeader(); // skip header

            while (true) {
                int tag = reader.readByte();
                if (tag == -1) break;

                long timeStamp = reader.readUnsignedInt();
                long length = reader.readUnsignedInt();

                if (tag == HprofConstants.Record.HEAP_DUMP || tag == HprofConstants.Record.HEAP_DUMP_SEGMENT) {
                    long segmentEnd = reader.getPosition() + length;

                    while (reader.getPosition() < segmentEnd) {
                        int subTag = reader.readByte();
                        if (subTag == -1) break;

                        switch (subTag) {
                            case HprofConstants.DumpSegment.ROOT_UNKNOWN,
                                 HprofConstants.DumpSegment.ROOT_STICKY_CLASS,
                                 HprofConstants.DumpSegment.ROOT_MONITOR_USED -> reader.skipBytes(idSize);

                            case HprofConstants.DumpSegment.ROOT_JNI_GLOBAL -> reader.skipBytes(idSize * 2L);

                            case HprofConstants.DumpSegment.ROOT_NATIVE_STACK,
                                 HprofConstants.DumpSegment.ROOT_THREAD_BLOCK -> reader.skipBytes(idSize + 4L);

                            case HprofConstants.DumpSegment.ROOT_THREAD_OBJECT,
                                 HprofConstants.DumpSegment.ROOT_JNI_LOCAL,
                                 HprofConstants.DumpSegment.ROOT_JAVA_FRAME -> reader.skipBytes(idSize + 8L);

                            case HprofConstants.DumpSegment.CLASS_DUMP -> {
                                long classId = reader.readId();
                                reader.skipBytes(4); // stack trace serial
                                long superClassId = reader.readId();
                                long classLoaderId = reader.readId();
                                reader.skipBytes(idSize * 4L); // signers, pd, res1, res2
                                int instanceSize = reader.readInt();

                                int cpCount = reader.readUnsignedShort();
                                for (int i = 0; i < cpCount; i++) {
                                    reader.skipBytes(2); // cp index
                                    int t = reader.readByte();
                                    reader.skipBytes(HprofConstants.Type.sizeOf(t, idSize));
                                }

                                int sfCount = reader.readUnsignedShort();
                                int classObjId = addressToId.get(classId);
                                boolean isFirstClassDump = (classObjId >= 0 && !processedClassDumps.contains(classObjId));
                                if (isFirstClassDump) {
                                    processedClassDumps.add(classObjId);
                                }
                                int edgeSeq = 0;
                                if (classObjId != -1 && isFirstClassDump) {
                                    if (javaLangClassObjId != -1) {
                                        edgeBatch.add(new HeapStorageEngine.ReferenceEdge(classObjId, edgeSeq++, javaLangClassObjId));
                                    }
                                    if (superClassId != 0 && addressToId.containsKey(superClassId)) {
                                        edgeBatch.add(new HeapStorageEngine.ReferenceEdge(classObjId, edgeSeq++, addressToId.get(superClassId)));
                                    }
                                    int clId = (classLoaderId != 0 && addressToId.containsKey(classLoaderId))
                                            ? addressToId.get(classLoaderId)
                                            : systemClassLoaderObjId;
                                    if (clId >= 0) {
                                        edgeBatch.add(new HeapStorageEngine.ReferenceEdge(classObjId, edgeSeq++, clId));
                                    }
                                }
                                for (int i = 0; i < sfCount; i++) {
                                    reader.skipBytes(idSize); // fieldNameId
                                    int t = reader.readByte();
                                    if (t == HprofConstants.Type.OBJECT) {
                                        long refAddr = reader.readId();
                                        if (refAddr != 0 && classObjId != -1 && isFirstClassDump) {
                                            if (addressToId.containsKey(refAddr)) {
                                                int targetId = addressToId.get(refAddr);
                                                edgeBatch.add(new HeapStorageEngine.ReferenceEdge(classObjId, edgeSeq++, targetId));
                                            }
                                        }
                                    } else {
                                        reader.skipBytes(HprofConstants.Type.sizeOf(t, idSize));
                                    }
                                }

                                int ifCount = reader.readUnsignedShort();
                                reader.skipBytes((long) ifCount * (idSize + 1));
                            }

                            case HprofConstants.DumpSegment.INSTANCE_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                long classAddr = reader.readId();
                                int bytesFollow = reader.readInt();
                                byte[] instanceBytes = reader.readBytes(bytesFollow);

                                int objId = addressToId.get(objAddr);
                                if (objId == -1 || writtenObjects.contains(objId)) {
                                    continue;
                                }

                                writtenObjects.add(objId);
                                int assignedClassId = addressToId.containsKey(classAddr)
                                        ? addressToId.get(classAddr)
                                        : (javaLangClassObjId != -1 ? javaLangClassObjId : 0);

                                HeapRecords.ClassRecord clsRecord = pass1.getClasses().get(classAddr);
                                long usedSize = (clsRecord != null && clsRecord.instanceSize() > 0)
                                        ? clsRecord.instanceSize()
                                        : alignUpToX(bytesFollow + (2L * idSize), 8);
                                objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                        objId, objAddr, assignedClassId, usedSize, objPos, false
                                ));

                                int edgeSeq = 0;
                                if (assignedClassId >= 0) {
                                    edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, edgeSeq++, assignedClassId));
                                }

                                // Extract outbound references from instance fields by traversing class hierarchy
                                List<HeapRecords.ClassRecord> hierarchy = resolveClassHierarchy(classAddr);
                                int offset = 0;
                                for (HeapRecords.ClassRecord cls : hierarchy) {
                                    for (HeapRecords.FieldDescriptor field : cls.fields()) {
                                        int fieldType = field.type();
                                        int fieldSize = HprofConstants.Type.sizeOf(fieldType, idSize);
                                        if (offset + fieldSize > instanceBytes.length) {
                                            break;
                                        }
                                        if (fieldType == HprofConstants.Type.OBJECT) {
                                            long refAddr = readIdFromBytes(instanceBytes, offset, idSize);
                                            if (refAddr != 0L && addressToId.containsKey(refAddr)) {
                                                int targetId = addressToId.get(refAddr);
                                                edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, edgeSeq++, targetId));
                                            }
                                        }
                                        offset += fieldSize;
                                    }
                                }

                                if (objectBatch.size() >= batchSize) {
                                    storage.insertObjectsBatch(objectBatch);
                                    objectBatch.clear();
                                }
                                if (edgeBatch.size() >= batchSize) {
                                    storage.insertOutboundReferencesBatch(edgeBatch);
                                    edgeBatch.clear();
                                }
                            }

                            case HprofConstants.DumpSegment.OBJECT_ARRAY_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                int arrayLength = reader.readInt();
                                long elementClassAddr = reader.readId();

                                int objId = addressToId.get(objAddr);
                                if (objId == -1 || writtenObjects.contains(objId)) {
                                    reader.skipBytes((long) arrayLength * idSize);
                                    continue;
                                }

                                writtenObjects.add(objId);
                                int assignedClassId = addressToId.containsKey(elementClassAddr)
                                        ? addressToId.get(elementClassAddr)
                                        : 0;

                                long usedSize = alignUpToX(2L * idSize + 4 + (long) arrayLength * idSize, 8);
                                objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                        objId, objAddr, assignedClassId, usedSize, objPos, true
                                ));

                                int edgeSeq = 0;
                                if (assignedClassId >= 0) {
                                    edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, edgeSeq++, assignedClassId));
                                }

                                // Read references
                                for (int i = 0; i < arrayLength; i++) {
                                    long refAddr = reader.readId();
                                    if (refAddr != 0 && addressToId.containsKey(refAddr)) {
                                        int targetId = addressToId.get(refAddr);
                                        edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, edgeSeq++, targetId));
                                    }
                                }

                                if (objectBatch.size() >= batchSize) {
                                    storage.insertObjectsBatch(objectBatch);
                                    objectBatch.clear();
                                }
                                if (edgeBatch.size() >= batchSize) {
                                    storage.insertOutboundReferencesBatch(edgeBatch);
                                    edgeBatch.clear();
                                }
                            }

                            case HprofConstants.DumpSegment.PRIMITIVE_ARRAY_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                int arrayLength = reader.readInt();
                                int elementType = reader.readByte();
                                int elementSize = HprofConstants.Type.sizeOf(elementType, idSize);
                                reader.skipBytes((long) arrayLength * elementSize);

                                int objId = addressToId.get(objAddr);
                                if (objId == -1 || writtenObjects.contains(objId)) {
                                    continue;
                                }

                                writtenObjects.add(objId);
                                long primClassAddr = pass1.getPrimitiveArrayClassId(elementType);
                                int assignedClassId = addressToId.containsKey(primClassAddr)
                                        ? addressToId.get(primClassAddr)
                                        : 0;

                                long usedSize = alignUpToX(alignUpToX(2L * idSize + 4, idSize) + (long) arrayLength * elementSize, 8);
                                objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                        objId, objAddr, assignedClassId, usedSize, objPos, true
                                ));

                                if (assignedClassId >= 0) {
                                    edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, 0, assignedClassId));
                                }

                                if (objectBatch.size() >= batchSize) {
                                    storage.insertObjectsBatch(objectBatch);
                                    objectBatch.clear();
                                }
                                if (edgeBatch.size() >= batchSize) {
                                    storage.insertOutboundReferencesBatch(edgeBatch);
                                    edgeBatch.clear();
                                }
                            }

                            default -> throw new IOException("Unknown subtag: " + subTag);
                        }
                    }
                } else {
                    reader.skipBytes(length);
                }
            }
        }

        // Flush remaining batches
        if (!objectBatch.isEmpty()) {
            storage.insertObjectsBatch(objectBatch);
            objectBatch.clear();
        }
        if (!edgeBatch.isEmpty()) {
            storage.insertOutboundReferencesBatch(edgeBatch);
            edgeBatch.clear();
        }

        try {
            storage.runGarbageCollection();
            storage.finishIngestion();
        } catch (SQLException e) {
            throw new IOException("Failed to run database garbage collection and indexing", e);
        }
        log.info("Pass 2 Completed: Ingested & garbage-collected objects in database (writtenObjects={}).", writtenObjects.size());
    }

    private List<HeapRecords.ClassRecord> resolveClassHierarchy(long classId) {
        List<HeapRecords.ClassRecord> cached = classHierarchyCache.get(classId);
        if (cached != null) return cached;

        List<HeapRecords.ClassRecord> hierarchy = new ArrayList<>();
        HeapRecords.ClassRecord curr = pass1.getClasses().get(classId);
        while (curr != null) {
            hierarchy.add(curr);
            if (curr.superClassId() == 0L) break;
            curr = pass1.getClasses().get(curr.superClassId());
        }
        classHierarchyCache.put(classId, hierarchy);
        return hierarchy;
    }

    private static long readIdFromBytes(byte[] bytes, int offset, int idSize) {
        long id = 0L;
        for (int i = 0; i < idSize; i++) {
            id = (id << 8) | (bytes[offset + i] & 0xFFL);
        }
        return id;
    }

    private static long alignUpToX(long n, int x) {
        long r = n % x;
        return r == 0 ? n : n + x - r;
    }

    public IAddressToIdMap getAddressToId() {
        return addressToId;
    }

    @Override
    public void close() {
        if (addressToId != null) {
            addressToId.close();
        }
    }
}
