package org.eclipse.mat.dhp.cli;

import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Command-line entry point for DHP (Dynamic Heap Dump Parser).
 * Usage:
 *   dhp --dump /path/to/dump.hprof --jdbcurl jdbc:postgresql://... --user ... --password ...
 * or:
 *   dhp --config /path/to/dhp.properties
 */
@Command(
        name = "dhp",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Dynamic Heap Dump Parser (DHP): Memory-bounded HPROF Ingestion and Database Stager for Eclipse MAT"
)
public class DhpMain implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(DhpMain.class);

    @Option(names = {"-d", "--dump"}, description = "Path to the input .hprof heap dump file")
    private File dumpFile;

    @Option(names = {"-c", "--config"}, description = "Path to database configuration properties file")
    private File configFile;

    @Option(names = {"--jdbcurl"}, description = "JDBC Connection URL (e.g., jdbc:sqlite:heap.db or jdbc:postgresql://localhost:5432/heapdb)")
    private String jdbcUrl;

    @Option(names = {"--host"}, description = "Database host")
    private String host;

    @Option(names = {"--port"}, description = "Database port")
    private Integer port;

    @Option(names = {"--db"}, description = "Database name")
    private String databaseName;

    @Option(names = {"-u", "--user"}, description = "Database username")
    private String user = "";

    @Option(names = {"-p", "--password"}, description = "Database password")
    private String password = "";

    @Option(names = {"-m", "--memory-budget"}, description = "Memory budget in bytes (e.g. 5368709120 for 5GB). Defaults to 75%% of JVM -Xmx")
    private Long memoryBudget;

    @Option(names = {"-t", "--threads"}, description = "Worker threads count")
    private Integer threads;

    @Option(names = {"--clean", "--drop-existing"}, description = "Clean/drop existing DHP tables in target database before ingestion")
    private boolean clean = false;

    @Override
    public Integer call() throws Exception {
        if (configFile != null && configFile.exists()) {
            loadConfigProperties(configFile);
        }

        if (dumpFile == null || !dumpFile.exists()) {
            System.err.println("Error: Valid HPROF heap dump file must be specified with --dump or in config file.");
            return 1;
        }

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            if (host != null && databaseName != null) {
                int p = port != null ? port : 5432;
                jdbcUrl = "jdbc:postgresql://" + host + ":" + p + "/" + databaseName;
            } else {
                // Default to optimized SQLite database alongside the dump
                String baseName = dumpFile.getAbsolutePath();
                int idx = baseName.lastIndexOf('.');
                String prefix = idx > 0 ? baseName.substring(0, idx) : baseName;
                jdbcUrl = "jdbc:sqlite:" + prefix + ".dhp.db";
            }
        }

        System.out.println("================================================================================");
        System.out.println("Dynamic Heap Dump Parser (DHP) - Starting Analysis");
        System.out.println("Heap Dump: " + dumpFile.getAbsolutePath());
        System.out.println("Target Database: " + jdbcUrl);
        System.out.println("================================================================================");

        long start = System.currentTimeMillis();

        MemoryGovernor governor = new MemoryGovernor(memoryBudget, threads);

        try (JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(
                jdbcUrl, user, password, governor.getTotalAllocatedBytes())) {

            if (storage.hasExistingTables()) {
                if (clean) {
                    System.out.println("Pre-existing DHP tables found. Dropping existing tables (--clean specified)...");
                    storage.dropExistingTables();
                } else {
                    System.err.println("Error: Target database already contains existing DHP tables.");
                    System.err.println("Pass --clean to drop existing tables and re-ingest, or specify a clean database URL.");
                    return 1;
                }
            }

            storage.initializeSchema();

            System.out.println("[Phase 1/3] Scanning HPROF structure, classes, and GC roots...");
            Pass1ScanParser pass1 = new Pass1ScanParser();
            pass1.scan(dumpFile);
            System.out.println(String.format("   Parsed %d strings, %d classes, %d GC roots.",
                    pass1.getStrings().size(), pass1.getClasses().size(), pass1.getGcRoots().size()));

            System.out.println("[Phase 2/3] Streaming object instances & references into database...");
            Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
            ingester.ingest(dumpFile);
            System.out.println(String.format("   Ingested %d total heap objects.", storage.getObjectCount()));

            System.out.println("[Phase 3/3] Calculating Dominator Tree & Retained Sizes...");
            DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
            domEngine.computeAndStore();

            long duration = System.currentTimeMillis() - start;
            System.out.println("================================================================================");
            System.out.println(String.format("DHP Parsing successfully finished in %.2f seconds!", duration / 1000.0));
            System.out.println("Ready to open with Eclipse MAT using DHP plugin!");
            System.out.println("================================================================================");
        }

        return 0;
    }

    private void loadConfigProperties(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        if (jdbcUrl == null) jdbcUrl = props.getProperty("db.url");
        if (user == null || user.isEmpty()) user = props.getProperty("db.user", "");
        if (password == null || password.isEmpty()) password = props.getProperty("db.password", "");
        if (dumpFile == null && props.containsKey("dump.file")) {
            dumpFile = new File(props.getProperty("dump.file"));
        }
        if (memoryBudget == null && props.containsKey("memory.budget")) {
            memoryBudget = parseMemoryString(props.getProperty("memory.budget"));
        }
    }

    private long parseMemoryString(String val) {
        val = val.trim().toUpperCase();
        if (val.endsWith("G") || val.endsWith("GB")) {
            return Long.parseLong(val.replaceAll("[^0-9]", "")) * 1024L * 1024L * 1024L;
        } else if (val.endsWith("M") || val.endsWith("MB")) {
            return Long.parseLong(val.replaceAll("[^0-9]", "")) * 1024L * 1024L;
        }
        return Long.parseLong(val);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DhpMain()).execute(args);
        System.exit(exitCode);
    }
}
