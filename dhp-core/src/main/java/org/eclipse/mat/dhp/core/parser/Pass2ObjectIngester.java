package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class Pass2ObjectIngester {
    private static final Logger log = LoggerFactory.getLogger(Pass2ObjectIngester.class);

    private final Pass1ScanParser pass1;
    private final HeapStorageEngine storage;
    private final MemoryGovernor governor;

    // Address to Object ID mapping: for memory-constrained environments,
    // this can also be backed by storage or off-heap.
    private final Map<Long, Integer> addressToId = new HashMap<>();

    public Pass2ObjectIngester(Pass1ScanParser pass1, HeapStorageEngine storage, MemoryGovernor governor) {
        this.pass1 = pass1;
        this.storage = storage;
        this.governor = governor;
    }

    public void ingest(File file) throws IOException, SQLException {
        log.info("Starting Pass 2 Object Ingestion into Database...");

        // Save metadata & classes from Pass 1
        storage.saveSnapshotInfo("version", pass1.getHeader().version());
        storage.saveSnapshotInfo("idSize", String.valueOf(pass1.getHeader().idSize()));
        storage.saveSnapshotInfo("creationTime", String.valueOf(pass1.getHeader().creationTime()));
        storage.saveClasses(pass1.getClasses().values());
        storage.insertGcRootsBatch(pass1.getGcRoots());

        int idSize = pass1.getHeader().idSize();
        int batchSize = governor.getBatchSize();

        List<HeapStorageEngine.RawObjectRecord> objectBatch = new ArrayList<>(batchSize);
        List<HeapStorageEngine.ReferenceEdge> edgeBatch = new ArrayList<>(batchSize);

        int currentObjectId = 0;

        // 1. Pre-register system classloader at address 0
        int systemClassLoaderObjId = currentObjectId++;
        addressToId.put(0L, systemClassLoaderObjId);
        objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                systemClassLoaderObjId, 0L, 0L, 0L, 0L, false
        ));

        // 2. Pre-register all classes as heap objects and map address -> objectId
        long javaLangClassAddress = 0L;
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if ("java.lang.Class".equals(cls.name())) {
                javaLangClassAddress = cls.classId();
                break;
            }
        }

        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if (!addressToId.containsKey(cls.classId())) {
                int classObjId = currentObjectId++;
                addressToId.put(cls.classId(), classObjId);
            }
        }

        int javaLangClassObjId = (javaLangClassAddress != 0 && addressToId.containsKey(javaLangClassAddress))
                ? addressToId.get(javaLangClassAddress)
                : -1;

        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            int classObjId = addressToId.get(cls.classId());
            int classTypeObjId = javaLangClassObjId != -1 ? javaLangClassObjId : classObjId;
            long size = cls.instanceSize() > 0 ? cls.instanceSize() : (2L * idSize);
            objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                    classObjId, cls.classId(), classTypeObjId, size, 0L, false
            ));
            if (objectBatch.size() >= batchSize) {
                storage.insertObjectsBatch(objectBatch);
                objectBatch.clear();
            }
        }

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
                                Integer classObjId = addressToId.get(classId);
                                int edgeSeq = 0;
                                for (int i = 0; i < sfCount; i++) {
                                    reader.skipBytes(idSize); // fieldNameId
                                    int t = reader.readByte();
                                    if (t == HprofConstants.Type.OBJECT) {
                                        long refAddr = reader.readId();
                                        if (refAddr != 0 && classObjId != null) {
                                            Integer targetId = addressToId.get(refAddr);
                                            if (targetId != null) {
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

                                boolean isDuplicate = addressToId.containsKey(objAddr);
                                int objId = isDuplicate ? addressToId.get(objAddr) : currentObjectId++;
                                if (!isDuplicate) {
                                    addressToId.put(objAddr, objId);
                                }

                                Integer classObjId = addressToId.get(classAddr);
                                int assignedClassId = classObjId != null ? classObjId : (javaLangClassObjId != -1 ? javaLangClassObjId : 0);

                                long usedSize = bytesFollow + (2L * idSize); // approximate header size
                                if (!isDuplicate) {
                                    objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                            objId, objAddr, assignedClassId, usedSize, objPos, false
                                    ));
                                }

                                if (objectBatch.size() >= batchSize) {
                                    storage.insertObjectsBatch(objectBatch);
                                    objectBatch.clear();
                                }
                            }

                            case HprofConstants.DumpSegment.OBJECT_ARRAY_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                int arrayLength = reader.readInt();
                                long elementClassAddr = reader.readId();

                                boolean isDuplicate = addressToId.containsKey(objAddr);
                                int objId = isDuplicate ? addressToId.get(objAddr) : currentObjectId++;
                                if (!isDuplicate) {
                                    addressToId.put(objAddr, objId);
                                }

                                Integer classObjId = addressToId.get(elementClassAddr);
                                int assignedClassId = classObjId != null ? classObjId : 0;

                                long usedSize = (long) arrayLength * idSize + (3L * idSize);
                                if (!isDuplicate) {
                                    objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                            objId, objAddr, assignedClassId, usedSize, objPos, true
                                    ));
                                }

                                // Read references
                                for (int i = 0; i < arrayLength; i++) {
                                    long refAddr = reader.readId();
                                    if (refAddr != 0) {
                                        Integer targetId = addressToId.get(refAddr);
                                        if (targetId != null) {
                                            edgeBatch.add(new HeapStorageEngine.ReferenceEdge(objId, i, targetId));
                                        }
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

                                boolean isDuplicate = addressToId.containsKey(objAddr);
                                int objId = isDuplicate ? addressToId.get(objAddr) : currentObjectId++;
                                if (!isDuplicate) {
                                    addressToId.put(objAddr, objId);
                                }

                                long primClassAddr = pass1.getPrimitiveArrayClassId(elementType);
                                Integer classObjId = addressToId.get(primClassAddr);
                                int assignedClassId = classObjId != null ? classObjId : 0;

                                long usedSize = (long) arrayLength * elementSize + (3L * idSize);
                                if (!isDuplicate) {
                                    objectBatch.add(new HeapStorageEngine.RawObjectRecord(
                                            objId, objAddr, assignedClassId, usedSize, objPos, true
                                    ));
                                }

                                if (objectBatch.size() >= batchSize) {
                                    storage.insertObjectsBatch(objectBatch);
                                    objectBatch.clear();
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

        storage.finishIngestion();
        log.info("Pass 2 Completed: Ingested {} objects into database.", currentObjectId);
    }

    public Map<Long, Integer> getAddressToId() {
        return addressToId;
    }
}
