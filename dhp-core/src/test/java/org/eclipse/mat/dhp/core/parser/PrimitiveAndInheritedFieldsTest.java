package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrimitiveAndInheritedFieldsTest {

    @Test
    void testInheritedAndAllPrimitiveFieldTypesExtraction(@TempDir Path tempDir) throws Exception {
        File dumpFile = tempDir.resolve("primitive_fields_test.hprof").toFile();
        byte[] hprofBytes = buildSyntheticHprof();
        try (FileOutputStream fos = new FileOutputStream(dumpFile)) {
            fos.write(hprofBytes);
        }

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        assertThat(pass1.getClasses()).hasSizeGreaterThanOrEqualTo(2);
        var baseClass = pass1.getClasses().get(100L);
        assertThat(baseClass).isNotNull();
        assertThat(baseClass.fields()).hasSize(9); // 8 primitives + 1 object ref

        var subClass = pass1.getClasses().get(200L);
        assertThat(subClass).isNotNull();
        assertThat(subClass.superClassId()).isEqualTo(100L);
        assertThat(subClass.fields()).hasSize(1); // 1 additional object ref

        MockHeapStorageEngine storage = new MockHeapStorageEngine();
        MemoryGovernor governor = new MemoryGovernor(64 * 1024 * 1024L, 1);
        Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
        ingester.ingest(dumpFile);

        // Find the SubClass instance (addr = 0x5000)
        int subClassObjId = ingester.getAddressToId().get(0x5000L);
        assertThat(subClassObjId).isNotEqualTo(-1);

        // Collect all outbound target IDs for this instance
        List<Integer> targets = storage.outboundEdges.stream()
                .filter(e -> e.fromObjectId() == subClassObjId)
                .map(HeapStorageEngine.ReferenceEdge::toObjectId)
                .toList();

        int targetRef1Id = ingester.getAddressToId().get(0x9000L);
        int targetRef2Id = ingester.getAddressToId().get(0x9001L);
        int subClassTypeObjId = ingester.getAddressToId().get(200L);

        // Subclass instance MUST have edges to:
        // 1. Its class object (200L)
        // 2. Its inherited base field reference (0x9000L)
        // 3. Its declared sub field reference (0x9001L)
        assertThat(targets).contains(subClassTypeObjId);
        assertThat(targets).contains(targetRef1Id);
        assertThat(targets).contains(targetRef2Id);
    }

    private static byte[] buildSyntheticHprof() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 1. Header: 4-byte identifiers
        dos.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII));
        dos.writeInt(4); // idSize = 4
        dos.writeLong(System.currentTimeMillis());

        // 2. Strings
        writeString(dos, 1L, "com.example.BaseClass");
        writeString(dos, 2L, "com.example.SubClass");
        writeString(dos, 11L, "boolVal");
        writeString(dos, 12L, "byteVal");
        writeString(dos, 13L, "charVal");
        writeString(dos, 14L, "shortVal");
        writeString(dos, 15L, "intVal");
        writeString(dos, 16L, "longVal");
        writeString(dos, 17L, "floatVal");
        writeString(dos, 18L, "doubleVal");
        writeString(dos, 19L, "baseRef");
        writeString(dos, 20L, "subRef");

        // 3. LOAD_CLASS records
        writeLoadClass(dos, 1, 100L, 1L); // BaseClass: serial=1, classId=100
        writeLoadClass(dos, 2, 200L, 2L); // SubClass: serial=2, classId=200

        // 4. HEAP_DUMP_SEGMENT
        ByteArrayOutputStream segBaos = new ByteArrayOutputStream();
        DataOutputStream segDos = new DataOutputStream(segBaos);

        // CLASS_DUMP: BaseClass (id=100, super=0, instanceSize=38)
        segDos.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        segDos.writeInt(100); // classId
        segDos.writeInt(0); // stack serial
        segDos.writeInt(0); // superClassId
        segDos.writeInt(0); // classLoaderId
        segDos.writeInt(0); // signers
        segDos.writeInt(0); // protectionDomain
        segDos.writeInt(0); // res1
        segDos.writeInt(0); // res2
        segDos.writeInt(38); // instanceSize
        segDos.writeShort(0); // cpCount
        segDos.writeShort(0); // staticCount
        segDos.writeShort(9); // fieldCount: 8 primitives + 1 object ref
        writeField(segDos, 11L, HprofConstants.Type.BOOLEAN); // 1 byte
        writeField(segDos, 12L, HprofConstants.Type.BYTE);    // 1 byte
        writeField(segDos, 13L, HprofConstants.Type.CHAR);    // 2 bytes
        writeField(segDos, 14L, HprofConstants.Type.SHORT);   // 2 bytes
        writeField(segDos, 15L, HprofConstants.Type.INT);     // 4 bytes
        writeField(segDos, 16L, HprofConstants.Type.LONG);    // 8 bytes
        writeField(segDos, 17L, HprofConstants.Type.FLOAT);   // 4 bytes
        writeField(segDos, 18L, HprofConstants.Type.DOUBLE);  // 8 bytes
        writeField(segDos, 19L, HprofConstants.Type.OBJECT);  // 4 bytes (idSize)
        // total size = 1+1+2+2+4+8+4+8+4 = 34 bytes (adjusted instanceSize = 34)

        // CLASS_DUMP: SubClass (id=200, super=100, instanceSize=38)
        segDos.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        segDos.writeInt(200); // classId
        segDos.writeInt(0); // stack serial
        segDos.writeInt(100); // superClassId -> BaseClass
        segDos.writeInt(0); // classLoaderId
        segDos.writeInt(0); // signers
        segDos.writeInt(0); // protectionDomain
        segDos.writeInt(0); // res1
        segDos.writeInt(0); // res2
        segDos.writeInt(38); // instanceSize
        segDos.writeShort(0); // cpCount
        segDos.writeShort(0); // staticCount
        segDos.writeShort(1); // fieldCount
        writeField(segDos, 20L, HprofConstants.Type.OBJECT);  // 4 bytes (idSize)

        // INSTANCE_DUMP for SubClass at address 0x5000
        segDos.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        segDos.writeInt(0x5000); // objAddr
        segDos.writeInt(0); // stack serial
        segDos.writeInt(200); // classAddr = SubClass
        segDos.writeInt(38); // bytesFollow = 34 (base) + 4 (sub)

        // Payload:
        // 1. SubClass declared field: subRef
        segDos.writeInt(0x9001); // subRef -> 0x9001
        // 2. BaseClass inherited fields: 8 primitives + baseRef
        segDos.writeByte(1); // bool
        segDos.writeByte(2); // byte
        segDos.writeChar('A'); // char
        segDos.writeShort(300); // short
        segDos.writeInt(40000); // int
        segDos.writeLong(5000000000L); // long
        segDos.writeFloat(3.14f); // float
        segDos.writeDouble(2.71828); // double
        segDos.writeInt(0x9000); // baseRef -> 0x9000

        byte[] segBytes = segBaos.toByteArray();
        dos.writeByte(HprofConstants.Record.HEAP_DUMP_SEGMENT);
        dos.writeInt(0); // timeStamp
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
        dos.writeInt(0); // stack trace serial
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
        @Override public void saveClasses(java.util.Collection<org.eclipse.mat.dhp.core.model.HeapRecords.ClassRecord> classes) {}
        @Override public List<org.eclipse.mat.dhp.core.model.HeapRecords.ClassRecord> getAllClasses() { return List.of(); }
        @Override public org.eclipse.mat.dhp.core.model.HeapRecords.ClassRecord getClassById(long classId) { return null; }
        @Override public void insertObjectsBatch(List<RawObjectRecord> objs) { objects.addAll(objs); }
        @Override public void insertOutboundReferencesBatch(List<ReferenceEdge> edges) { outboundEdges.addAll(edges); }
        @Override public void insertGcRootsBatch(List<org.eclipse.mat.dhp.core.model.HeapRecords.GcRootRecord> gcRoots) {}
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
        @Override public List<org.eclipse.mat.dhp.core.model.HeapRecords.GcRootRecord> getGcRoots() { return List.of(); }
        @Override public int getDominatorId(int objectId) { return -1; }
        @Override public long getRetainedSize(int objectId) { return 0; }
        @Override public int[] getImmediateDominatedIds(int objectId) { return new int[0]; }
        @Override public void close() {}
    }
}
