package org.eclipse.mat.dhp.storage;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FullIngestionPipelineTest {

    @Test
    void testParseAndIngestJdkDump(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        File dbFile = tempDir.resolve("sun_jdk6_18_parsed.db").toFile();

        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        MemoryGovernor governor = new MemoryGovernor(256 * 1024 * 1024L, 4);

        try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                "jdbc:sqlite:" + dbFile.getAbsolutePath(), "", "", governor.getTotalAllocatedBytes())) {

            storage.initializeSchema();
            Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
            ingester.ingest(dumpFile);

            // Assert database contains all parsed objects and metadata
            assertThat(storage.getObjectCount()).isGreaterThan(1000);
            assertThat(storage.getSnapshotInfo("version")).isEqualTo(pass1.getHeader().version());
            assertThat(storage.getAllClasses()).hasSize(pass1.getClasses().size());

            long bootLoaderAddr = storage.getObjectAddress(0);
            assertThat(bootLoaderAddr).isZero();
            assertThat(storage.getObjectIdByAddress(0L)).isEqualTo(0);

            long firstClassAddr = storage.getObjectAddress(1);
            assertThat(firstClassAddr).isNotZero();
            assertThat(storage.getObjectIdByAddress(firstClassAddr)).isEqualTo(1);
        }
    }
}
