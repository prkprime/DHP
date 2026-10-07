package org.eclipse.mat.dhp.storage;

import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultiJdkHeapDumpSupportTest {

    @Test
    void testJdk11CompressedDump(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/openjdk_jdk11_04_x64.hprof.gz");
        if (!dumpFile.exists()) return;

        File dbFile = tempDir.resolve("jdk11.db").toFile();
        MemoryGovernor governor = new MemoryGovernor(256 * 1024 * 1024L, 2);

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        assertThat(pass1.getHeader().idSize()).isEqualTo(8);
        assertThat(pass1.getClasses()).isNotEmpty();

        try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                "jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", governor.getTotalAllocatedBytes())) {

            storage.initializeSchema();
            Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
            ingester.ingest(dumpFile);

            assertThat(storage.getObjectCount()).isGreaterThan(1000);
            assertThat(storage.getAllClasses()).isNotEmpty();

            DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
            domEngine.computeAndStore();
        }
    }

    @Test
    void testJdk17Dump(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/dhp-test-dumps/jdk17_test.hprof");
        if (!dumpFile.exists()) return;

        File dbFile = tempDir.resolve("jdk17.db").toFile();
        MemoryGovernor governor = new MemoryGovernor(256 * 1024 * 1024L, 2);

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        assertThat(pass1.getHeader().idSize()).isEqualTo(8);
        assertThat(pass1.getClasses()).isNotEmpty();

        // Check our custom class is present
        boolean hasUserDataClass = pass1.getClasses().values().stream()
                .anyMatch(c -> c.name().contains("DumpGenerator$UserData"));
        assertThat(hasUserDataClass).isTrue();

        try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                "jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", governor.getTotalAllocatedBytes())) {

            storage.initializeSchema();
            Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
            ingester.ingest(dumpFile);

            assertThat(storage.getObjectCount()).isGreaterThan(1000);

            DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
            domEngine.computeAndStore();
            assertThat(storage.getRetainedSize(0)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void testJdk21Dump(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/dhp-test-dumps/jdk21_test.hprof");
        if (!dumpFile.exists()) return;

        File dbFile = tempDir.resolve("jdk21.db").toFile();
        MemoryGovernor governor = new MemoryGovernor(256 * 1024 * 1024L, 2);

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        assertThat(pass1.getHeader().idSize()).isEqualTo(8);
        assertThat(pass1.getClasses()).isNotEmpty();

        // Check our custom class is present
        boolean hasUserDataClass = pass1.getClasses().values().stream()
                .anyMatch(c -> c.name().contains("DumpGenerator$UserData"));
        assertThat(hasUserDataClass).isTrue();

        try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                "jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", governor.getTotalAllocatedBytes())) {

            storage.initializeSchema();
            Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
            ingester.ingest(dumpFile);

            assertThat(storage.getObjectCount()).isGreaterThan(1000);

            DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
            domEngine.computeAndStore();
            assertThat(storage.getRetainedSize(0)).isGreaterThanOrEqualTo(0);
        }
    }
}
