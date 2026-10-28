package org.eclipse.mat.dhp.storage;

import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingestion and dominator pipeline must produce bit-identical databases in every memory mode: FAST keeps
 * everything in the JVM heap, EXTREME keeps the object columns and the dominator's inbound CSR in memory-mapped
 * temp files and the address array in an mmap file. This test ingests the same dump twice, once per mode, and
 * compares every table of the two databases row by row (snapshot info, classes, class order, class stats,
 * GC roots and the chunked object data).
 */
class MemoryModeParityTest {

    @Test
    void fastAndExtremeModesProduceIdenticalDatabases(@TempDir Path tempDir) throws Exception {
        byte[] hprof = buildHprof();
        File dumpFile = tempDir.resolve("parity.hprof").toFile();
        try (FileOutputStream fos = new FileOutputStream(dumpFile)) {
            fos.write(hprof);
        }

        String fastDb = ingest(dumpFile, "fast", tempDir.resolve("fast.db").toFile());
        String saverDb = ingest(dumpFile, "extreme", tempDir.resolve("extreme.db").toFile());

        assertThat(saverDb).isEqualTo(fastDb);
    }

    private static String ingest(File dumpFile, String mode, File dbFile) throws Exception {
        System.setProperty("dhp.memory.mode", mode);
        try {
            MemoryGovernor governor = new MemoryGovernor(256 * 1024 * 1024L, 2);
            Pass1ScanParser pass1 = new Pass1ScanParser();
            pass1.scan(dumpFile);

            org.eclipse.mat.dhp.core.graph.HeapGraph graph;
            try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", governor.getTotalAllocatedBytes())) {
                storage.initializeSchema();
                try (Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor)) {
                    graph = ingester.ingest(dumpFile);
                }
                boolean fileBackedInbound = mode.equals("extreme") ? true
                        : governor.inboundFileBacked(graph.objectCount(), graph.edgeCount());
                // exercises the file-backed inbound CSR in the dominator phase regardless of the governor decision,
                // so the memory-mapped write/read path is verified even on small dumps
                new DominatorTreeEngine(storage, fileBackedInbound, dumpFile.getParentFile()).computeAndStore(graph);
                graph.close();
                return dumpTables(dbFile);
            }
        } finally {
            System.clearProperty("dhp.memory.mode");
        }
    }

    private static String dumpTables(File dbFile) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            for (String table : List.of("dhp_snapshot_info", "dhp_classes", "dhp_class_order", "dhp_class_stats", "dhp_gc_roots", "dhp_chunks")) {
                sb.append("== ").append(table).append('\n');
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                    int cols = rs.getMetaData().getColumnCount();
                    List<String> rows = new ArrayList<>();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int c = 1; c <= cols; c++) {
                            if (c > 1) row.append('|');
                            row.append(rs.getString(c));
                        }
                        // the chunk-file footer carries a random per-snapshot id, it is not part of the data
                        if (table.equals("dhp_snapshot_info") && row.toString().startsWith("chunkFile|")) {
                            continue;
                        }
                        rows.add(row.toString());
                    }
                    rows.sort(String::compareTo);
                    for (String r : rows) {
                        sb.append(r).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    // HPROF 1.0.2, 4-byte ids, fixed creation time. Two classes (Base with an Object field, Child extending it with
    // an extra int), one GC root, a reachable cycle A -> object array -> B/C, B -> int array, and one unreachable
    // instance that the default garbage collection removes.
    private static byte[] buildHprof() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII));
        dos.writeInt(4); // idSize
        dos.writeLong(1234567890L); // fixed creation time

        writeString(dos, 1L, "com.example.Base");
        writeString(dos, 2L, "com.example.Child");
        writeString(dos, 3L, "ref");
        writeString(dos, 4L, "x");
        writeLoadClass(dos, 1, 0x10000L, 1L);
        writeLoadClass(dos, 2, 0x11000L, 2L);

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);

        // class dump: Base @0x10000, 1 Object field "ref"
        s.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        s.writeInt(0x10000);
        s.writeInt(0);            // stack trace
        s.writeInt(0);            // super
        s.writeInt(0);            // loader
        s.writeInt(0);            // signers
        s.writeInt(0);            // protection domain
        s.writeInt(0);            // reserved1
        s.writeInt(0);            // reserved2
        s.writeInt(16);           // instance size
        s.writeShort(0);          // constant pool
        s.writeShort(0);          // static fields
        s.writeShort(1);          // instance fields
        s.writeInt(3);            // field name "ref"
        s.writeByte(HprofConstants.Type.OBJECT);

        // class dump: Child @0x11000 extends Base, fields "ref" (Object) and "x" (int)
        s.writeByte(HprofConstants.DumpSegment.CLASS_DUMP);
        s.writeInt(0x11000);
        s.writeInt(0);
        s.writeInt(0x10000);
        s.writeInt(0);
        s.writeInt(0);
        s.writeInt(0);
        s.writeInt(0);
        s.writeInt(0);
        s.writeInt(24);
        s.writeShort(0);
        s.writeShort(0);
        s.writeShort(2);
        s.writeInt(3);
        s.writeByte(HprofConstants.Type.OBJECT);
        s.writeInt(4);
        s.writeByte(HprofConstants.Type.INT);

        // roots: A is a JNI global root
        s.writeByte(HprofConstants.DumpSegment.ROOT_JNI_GLOBAL);
        s.writeInt(0x4000);
        s.writeInt(0);            // JNI handle

        // instance A @0x4000 (Child): ref -> object array @0x20000, x = 1
        s.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        s.writeInt(0x4000);
        s.writeInt(0);
        s.writeInt(0x11000);
        s.writeInt(8);
        s.writeInt(0x20000);
        s.writeInt(1);

        // instance B @0x5000 (Base): ref -> int array @0x30000
        s.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        s.writeInt(0x5000);
        s.writeInt(0);
        s.writeInt(0x10000);
        s.writeInt(4);
        s.writeInt(0x30000);

        // instance C @0x6000 (Base): ref -> 0
        s.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        s.writeInt(0x6000);
        s.writeInt(0);
        s.writeInt(0x10000);
        s.writeInt(4);
        s.writeInt(0);

        // unreachable instance D @0x7000 (Child)
        s.writeByte(HprofConstants.DumpSegment.INSTANCE_DUMP);
        s.writeInt(0x7000);
        s.writeInt(0);
        s.writeInt(0x11000);
        s.writeInt(8);
        s.writeInt(0);
        s.writeInt(0);

        // object array @0x20000 of class Base, length 2: [B, C]
        s.writeByte(HprofConstants.DumpSegment.OBJECT_ARRAY_DUMP);
        s.writeInt(0x20000);
        s.writeInt(0);
        s.writeInt(2);
        s.writeInt(0x10000);
        s.writeInt(0x5000);
        s.writeInt(0x6000);

        // primitive int array @0x30000, length 3
        s.writeByte(HprofConstants.DumpSegment.PRIMITIVE_ARRAY_DUMP);
        s.writeInt(0x30000);
        s.writeInt(0);
        s.writeInt(3);
        s.writeByte(HprofConstants.Type.INT);
        s.writeInt(7);
        s.writeInt(8);
        s.writeInt(9);

        byte[] segBytes = seg.toByteArray();
        dos.writeByte(HprofConstants.Record.HEAP_DUMP);
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

    @Test
    void modeSelectionStepsDownAsBudgetShrinks() {
        // The thresholds live in MemoryGovernor; the mode must step down as the budget shrinks, and the effective
        // budget is capped by the JVM heap, so expectations below are guarded by the actual maxMemory.
        int n = 48_000_000;
        long max = Runtime.getRuntime().maxMemory();

        MemoryGovernor.MemoryMode at8g = new MemoryGovernor(Math.max(max, 8L * 1024 * 1024 * 1024), 4).modeFor(n);
        MemoryGovernor.MemoryMode at2g = new MemoryGovernor(2L * 1024 * 1024 * 1024, 4).modeFor(n);
        MemoryGovernor.MemoryMode at1g = new MemoryGovernor(1024L * 1024 * 1024, 4).modeFor(n);

        // 1 GB can never hold FAST/TIGHT structures for 48M objects: EXTREME is the last rung and always chosen
        assertThat(at1g).isEqualTo(MemoryGovernor.MemoryMode.EXTREME);
        // monotonic: more budget never selects a more aggressive (more heap-saving) mode
        assertThat(at8g.ordinal()).isLessThanOrEqualTo(at2g.ordinal());
        assertThat(at2g.ordinal()).isLessThanOrEqualTo(at1g.ordinal());
        // on a machine with at least 2 GB of heap, a 2 GB budget lands exactly on TIGHT (file-backed columns)
        if (max >= 2L * 1024 * 1024 * 1024) {
            assertThat(at2g).isEqualTo(MemoryGovernor.MemoryMode.TIGHT);
        }
        // and a budget above the FAST rung keeps everything in heap
        if (max >= 3L * 1024 * 1024 * 1024) {
            assertThat(at8g).isEqualTo(MemoryGovernor.MemoryMode.FAST);
        }
    }
}