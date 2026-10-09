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

        // Insert objects batch
        List<HeapStorageEngine.RawObjectRecord> objects = List.of(
                new HeapStorageEngine.RawObjectRecord(0, 0x1000L, 100L, 24L, 500L, false),
                new HeapStorageEngine.RawObjectRecord(1, 0x2000L, 100L, 32L, 600L, false)
        );
        storage.insertObjectsBatch(objects);
        storage.finishIngestion();

        assertThat(storage.getObjectCount()).isEqualTo(2);
        assertThat(storage.getObjectAddress(0)).isEqualTo(0x1000L);
        assertThat(storage.getObjectIdByAddress(0x2000L)).isEqualTo(1);
        assertThat(storage.getObjectClassId(0)).isEqualTo(100L);
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
}
