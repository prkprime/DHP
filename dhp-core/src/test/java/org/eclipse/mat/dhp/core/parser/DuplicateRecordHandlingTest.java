package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DuplicateRecordHandlingTest {

    @Test
    void testDuplicateInstanceDumpAndDuplicateClassDumpHandling(@TempDir Path tempDir) throws Exception {
        byte[] hprofBytes = buildHprofWithDuplicates();
        File dumpFile = tempDir.resolve("duplicates.hprof").toFile();
        try (FileOutputStream fos = new FileOutputStream(dumpFile)) {
            fos.write(hprofBytes);
        }

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        MockHeapStorageEngine storage = new MockHeapStorageEngine();
        MemoryGovernor governor = new MemoryGovernor(64 * 1024 * 1024L, 1);
        Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);

        // Must ingest cleanly without throwing Primary Key or constraint violations
        ingester.ingest(dumpFile);

        // Instance at 0x5000 should be registered exactly once in raw objects
        long countAt5000 = storage.objects.stream()
                .filter(o -> o.objectAddress() == 0x5000L)
                .count();
        assertThat(countAt5000).isEqualTo(1L);

        int objId = ingester.getAddressToId().get(0x5000L);
        assertThat(objId).isNotEqualTo(-1);

        // Outbound edges from objId should not be duplicated
        List<HeapStorageEngine.ReferenceEdge> edges = storage.outboundEdges.stream()
                .filter(e -> e.fromObjectId() == objId)
                .toList();
        // 1 to class (100) + 1 to field (0x9000) = 2 edges
        assertThat(edges).hasSize(2);
    }

    private static byte[] buildHprofWithDuplicates() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII));
        dos.writeInt(4); // idSize = 4
        dos.writeLong(System.currentTimeMillis());

        writeString(dos, 1L, "com.example.DuplicateClass");
        writeString(dos, 11L, "refField");
        writeLoadClass(dos, 1, 100L, 1L);

        ByteArrayOutputStream segBaos = new ByteArrayOutputStream();
        DataOutputStream segDos = new DataOutputStream(segBaos);

        // CLASS_DUMP: DuplicateClass (id=100)
        segDos.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        segDos.writeInt(100);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(4);
        segDos.writeShort(0); // cpCount
        segDos.writeShort(0); // staticCount
        segDos.writeShort(1); // fieldCount
        writeField(segDos, 11L, HprofConstants.Type.OBJECT);

        // Duplicate CLASS_DUMP for id=100 in the same stream
        segDos.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        segDos.writeInt(100);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(0);
        segDos.writeInt(4);
        segDos.writeShort(0);
        segDos.writeShort(0);
        segDos.writeShort(1);
        writeField(segDos, 11L, HprofConstants.Type.OBJECT);

        // 1st INSTANCE_DUMP at 0x5000
        segDos.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        segDos.writeInt(0x5000);
        segDos.writeInt(0);
        segDos.writeInt(100);
        segDos.writeInt(4);
        segDos.writeInt(0x9000);

        // 2nd duplicate INSTANCE_DUMP at 0x5000
        segDos.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        segDos.writeInt(0x5000);
        segDos.writeInt(0);
        segDos.writeInt(100);
        segDos.writeInt(4);
        segDos.writeInt(0x9000);

        // Referenced target object at 0x9000
        segDos.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        segDos.writeInt(0x9000);
        segDos.writeInt(0);
        segDos.writeInt(100);
        segDos.writeInt(0);

        byte[] segBytes = segBaos.toByteArray();
        dos.writeByte(HprofConstants.Record.HEAP_DUMP_SEGMENT);
        dos.writeInt(0);
        dos.writeInt(segBytes.length);
        dos.write(segBytes);

        return baos.toByteArray();
    }

    private static void writeString(DataOutputStream dos, long id, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        dos.writeByte(HprofConstants.Record.STRING_IN_UTF8);
        dos.writeInt(0);
        dos.writeInt(4 + bytes.length);
        dos.writeInt((int) id);
        dos.write(bytes);
    }

    private static void writeLoadClass(DataOutputStream dos, int serial, long classId, long nameId) throws Exception {
        dos.writeByte(HprofConstants.Record.LOAD_CLASS);
        dos.writeInt(0);
        dos.writeInt(4 + 4 + 4 + 4);
        dos.writeInt(serial);
        dos.writeInt((int) classId);
        dos.writeInt(0);
        dos.writeInt((int) nameId);
    }

    private static void writeField(DataOutputStream dos, long nameId, int type) throws Exception {
        dos.writeInt((int) nameId);
        dos.writeByte(type);
    }

    private static class MockHeapStorageEngine implements HeapStorageEngine {
        final List<RawObjectRecord> objects = new ArrayList<>();
        final List<ReferenceEdge> outboundEdges = new ArrayList<>();

        @Override public boolean hasExistingTables() { return false; }
        @Override public void dropExistingTables() {}
        @Override public void initializeSchema() {}
        @Override public void saveSnapshotInfo(String key, String value) {}
        @Override public String getSnapshotInfo(String key) { return null; }
        @Override public void saveClasses(java.util.Collection<HeapRecords.ClassRecord> classes) {}
        @Override public List<HeapRecords.ClassRecord> getAllClasses() { return List.of(); }
        @Override public HeapRecords.ClassRecord getClassById(long classId) { return null; }
        @Override public void insertObjectsBatch(List<RawObjectRecord> objs) { objects.addAll(objs); }
        @Override public void insertOutboundReferencesBatch(List<ReferenceEdge> edges) { outboundEdges.addAll(edges); }
        @Override public void insertGcRootsBatch(List<HeapRecords.GcRootRecord> gcRoots) {}
        @Override public void saveDominatorTreeBatch(List<DominatorNode> dominatorNodes) {}
        @Override public void finishIngestion() {}
        @Override public int getObjectCount() { return objects.size(); }
        @Override public long getObjectAddress(int objectId) { return 0; }
        @Override public int getObjectIdByAddress(long address) { return -1; }
        @Override public long getObjectClassId(int objectId) { return 0; }
        @Override public long getObjectUsedSize(int objectId) { return 0; }
        @Override public long getObjectFilePosition(int objectId) { return 0; }
        @Override public int[] getOutboundReferences(int objectId) { return new int[0]; }
        @Override public int[] getInboundReferences(int objectId) { return new int[0]; }
        @Override public List<HeapRecords.GcRootRecord> getGcRoots() { return List.of(); }
        @Override public int getDominatorId(int objectId) { return -1; }
        @Override public long getRetainedSize(int objectId) { return 0; }
        @Override public int[] getImmediateDominatedIds(int objectId) { return new int[0]; }
        @Override public void close() {}
    }
}
