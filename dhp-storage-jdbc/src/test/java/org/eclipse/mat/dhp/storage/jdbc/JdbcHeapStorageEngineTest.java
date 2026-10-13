package org.eclipse.mat.dhp.storage.jdbc;

import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcHeapStorageEngineTest {

    private JdbcHeapStorageEngine storage;
    private File dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        dbFile = tempDir.resolve("test_heap.db").toFile();
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        storage = new JdbcHeapStorageEngine(jdbcUrl, "", "", 128 * 1024 * 1024L);
        storage.initializeSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void testBasicStorageAndRetrieval() throws SQLException {
        storage.saveSnapshotInfo("version", "JAVA PROFILE 1.0.2");
        assertThat(storage.getSnapshotInfo("version")).isEqualTo("JAVA PROFILE 1.0.2");

        // Save class
        HeapRecords.ClassRecord cls = new HeapRecords.ClassRecord(100L, 0L, 0L, "java.lang.Object", 16, List.of(), List.of());
        storage.saveClasses(List.of(cls));

        HeapRecords.ClassRecord retrieved = storage.getClassById(100L);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.name()).isEqualTo("java.lang.Object");

        // Persist a small graph through the bulk API
        var map = new org.eclipse.mat.dhp.core.memory.SortedAddressToIdMap(
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(), new long[]{0x1000L, 0x2000L}, 0);
        var graph = new org.eclipse.mat.dhp.core.graph.HeapGraph(2, 0, map);
        graph.setObject(0, 0, 24L, 500L, false);
        graph.setObject(1, 0, 32L, 600L, true);
        graph.setEdges(0, new int[]{1, 0}, 2);
        storage.saveObjects(graph);
        storage.saveOutboundEdges(graph);
        storage.finishIngestion();
        assertThat(storage.getOutboundReferences(0)).containsExactly(1, 0);
        assertThat(storage.getOutboundReferences(1)).isEmpty();
        assertThat(storage.isArray(1)).isTrue();
        assertThat(storage.getObjectFilePosition(1)).isEqualTo(600L);

        assertThat(storage.getObjectCount()).isEqualTo(2);
        assertThat(storage.getObjectAddress(0)).isEqualTo(0x1000L);
        assertThat(storage.getObjectIdByAddress(0x2000L)).isEqualTo(1);
        assertThat(storage.getObjectClassId(0)).isEqualTo(0L);
        assertThat(storage.getObjectUsedSize(0)).isEqualTo(24L);
    }

    @Test
    void testPreflightCheckAndDropSchema() throws SQLException {
        assertThat(storage.hasExistingTables()).isTrue();

        storage.dropExistingTables();
        assertThat(storage.hasExistingTables()).isFalse();

        storage.initializeSchema();
        assertThat(storage.hasExistingTables()).isTrue();
    }

    @Test
    void testClassStatsAndMetadataPersistence() throws SQLException {
        // Save base class record
        HeapRecords.ClassRecord cls = new HeapRecords.ClassRecord(100L, 0L, 0L, "java.lang.Object", 16, List.of(), List.of());
        storage.saveClasses(List.of(cls));

        // Save class stats in memory
        List<HeapStorageEngine.ClassStats> stats = List.of(
                new HeapStorageEngine.ClassStats(5, 42, 1024L)
        );
        storage.saveClassStats(stats);

        var statsMap = storage.getClassStats();
        assertThat(statsMap).containsKey(5);
        assertThat(statsMap.get(5).instanceCount()).isEqualTo(42);
        assertThat(statsMap.get(5).totalSize()).isEqualTo(1024L);

        // Update class metadata in memory
        storage.updateClassesMetadata(List.of(
                new HeapStorageEngine.ResolvedClassMetadata(100L, 5, -1, 0, 16L)
        ));

        storage.finishIngestion();

        HeapRecords.ClassRecord updated = storage.getClassById(100L);
        assertThat(updated).isNotNull();
        assertThat(updated.classObjId()).isEqualTo(5);
        assertThat(updated.usedSize()).isEqualTo(16L);
    }

    @Test
    void aFinishedSnapshotSurvivesReopeningAndKeepsDominatorData() throws Exception {
        // objects 0..3 are all of class 0 (object 0 is the class object); dominator tree: 0 -> 1 -> 2, 3 under the root
        var map = new org.eclipse.mat.dhp.core.memory.SortedAddressToIdMap(
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(), new long[]{0x10L, 0x20L, 0x30L, 0x40L}, 0);
        var graph = new org.eclipse.mat.dhp.core.graph.HeapGraph(4, 0, map);
        for (int i = 0; i < 4; i++) {
            graph.setObject(i, 0, 8L, 100L + i, false);
        }
        storage.saveObjects(graph);
        storage.saveDominators(new int[]{-1, 0, 1, -1}, new long[]{100, 60, 20, 7}, 4);
        storage.close();

        try (JdbcHeapStorageEngine reopened = new JdbcHeapStorageEngine("jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", 64L << 20)) {
            assertThat(reopened.getObjectCount()).isEqualTo(4);
            assertThat(reopened.getDominatorId(2)).isEqualTo(1);
            assertThat(reopened.getRetainedSize(1)).isEqualTo(60);
            assertThat(reopened.getImmediateDominatedIds(0)).containsExactly(1);
            assertThat(reopened.getImmediateDominatedIds(-1)).containsExactly(0, 3);
            // the set of class 0 is {0, 1, 2, 3} plus the class object again (object 0 is an instance of its own class, as
            // java.lang.Class is): only members no other member dominates count: 0 twice (100 each) and 3 (7)
            assertThat(reopened.getClassMinRetainedSizes()).containsExactly(java.util.Map.entry(0, 207L));
            assertThat(reopened.getObjectsByClassId(0)).containsExactly(0, 1, 2, 3);
        }
        storage = null; // already closed
    }
}
