package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.graph.HeapGraph;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;

/** Opens the database of a descriptor and, when it is still empty, ingests the dump into it (what {@code dhp-cli} does). */
final class DhpIngestion {
    private static final Logger log = LoggerFactory.getLogger(DhpIngestion.class);

    private DhpIngestion() {}

    /**
     * Connects to the database and makes sure it holds a complete snapshot. The caller owns (and closes) the returned storage.
     */
    static JdbcHeapStorageEngine openOrIngest(DhpDescriptor d) throws SnapshotException, SQLException, IOException {
        MemoryGovernor governor = new MemoryGovernor(d.memoryBudget(), 4);
        JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(d.jdbcUrl(), d.user(), d.password(), governor.getTotalAllocatedBytes());
        try {
            if (storage.hasExistingTables() && storage.getObjectCount() == 0) {
                // tables without the completion marker: an earlier ingestion did not finish, start over
                log.warn("The database holds an unfinished ingestion, discarding it");
                storage.dropExistingTables();
            }
            if (!storage.hasExistingTables()) {
                storage.initializeSchema();
            }
            if (storage.getObjectCount() != 0) {
                return storage; // already ingested
            }
            if (d.isGzip()) {
                throw new SnapshotException("Compressed heap dumps are not supported (recorded positions refer to the decompressed "
                        + "stream): decompress " + d.hprofFile().getName() + " first");
            }
            if (d.hprofFile() == null || !d.hprofFile().exists()) {
                throw new SnapshotException("The database " + d.jdbcUrl() + " holds no snapshot and the HPROF dump to ingest "
                        + (d.hprofFile() == null ? "is not named (set dump.file in " + d.file().getName() + ")" : "is missing: " + d.hprofFile()));
            }
            log.info("Database is empty, streaming the HPROF dump into it: {}", d.hprofFile().getAbsolutePath());
            Pass1ScanParser pass1 = new Pass1ScanParser();
            pass1.scan(d.hprofFile());
            HeapGraph graph;
            try (Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor)) {
                graph = ingester.ingest(d.hprofFile());
            }
            new DominatorTreeEngine(storage).computeAndStore(graph);
            return storage;
        } catch (SnapshotException | SQLException | IOException | RuntimeException e) {
            try {
                storage.close();
            } catch (IOException ignored) {
                // the original failure is the interesting one
            }
            throw e;
        }
    }
}
