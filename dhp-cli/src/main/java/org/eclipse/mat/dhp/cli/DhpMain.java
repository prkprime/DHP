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

    @Option(names = {"-c", "--config"}, description = "Path to DHP descriptor file (.dhp)")
    private File configFile;

    @Option(names = {"--export-dhp"}, description = "Path to export .dhp MAT descriptor file (defaults to <dumpPrefix>.dhp)")
    private File exportDhpFile;

    @Option(names = {"--url", "--jdbcurl", "--db-url"}, description = "JDBC Connection URL (e.g., jdbc:sqlite:heap.db or jdbc:postgresql://localhost:5432/heapdb)")
    private String jdbcUrl;

    @Option(names = {"--host"}, description = "Database host (used when building PostgreSQL URL without --url)")
    private String host;

    @Option(names = {"--port"}, description = "Database port (defaults to 5432 for PostgreSQL)")
    private Integer port;

    @Option(names = {"--db"}, description = "Database name or JDBC connection URL")
    private String databaseOption;

    @Option(names = {"-u", "--user"}, description = "Database username")
    private String user = "";

    @Option(names = {"-p", "--password"}, description = "Database password")
    private String password = "";

    @Option(names = {"-m", "--memory", "--memory-budget"}, description = "Memory budget (e.g. 512M, 2G, 4GB, or raw bytes). Defaults to 75%% of JVM -Xmx")
    private String memoryBudgetString;

    @Option(names = {"-t", "--threads", "-w", "--workers"}, description = "Worker threads count")
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
            if (databaseOption != null && !databaseOption.isBlank()) {
                if (databaseOption.startsWith("jdbc:")) {
                    jdbcUrl = databaseOption;
                } else if (host != null) {
                    int p = port != null ? port : 5432;
                    jdbcUrl = "jdbc:postgresql://" + host + ":" + p + "/" + databaseOption;
                }
            }
        }

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            if (host != null) {
                int p = port != null ? port : 5432;
                String dbName = databaseOption != null ? databaseOption : "heapdb";
                jdbcUrl = "jdbc:postgresql://" + host + ":" + p + "/" + dbName;
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

        Long memoryBudget = null;
        if (memoryBudgetString != null && !memoryBudgetString.isBlank()) {
            memoryBudget = parseMemoryString(memoryBudgetString);
        }

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
            System.out.println(String.format("   Ingested & garbage-collected %d reachable heap objects.", storage.getObjectCount()));

            System.out.println("[Phase 3/3] Calculating Dominator Tree & Retained Sizes...");
            DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
            domEngine.computeAndStore();

            long duration = System.currentTimeMillis() - start;
            System.out.println("================================================================================");
            System.out.println(String.format("DHP Parsing successfully finished in %.2f seconds!", duration / 1000.0));

            File targetDhp = exportDhpFile;
            if (targetDhp == null && dumpFile != null) {
                String base = dumpFile.getAbsolutePath();
                int dot = base.lastIndexOf('.');
                String pfx = dot > 0 ? base.substring(0, dot) : base;
                targetDhp = new File(pfx + ".dhp");
            }
            if (targetDhp != null) {
                Properties p = new Properties();
                p.setProperty("db.url", jdbcUrl);
                if (user != null && !user.isEmpty()) p.setProperty("db.user", user);
                if (password != null && !password.isEmpty()) p.setProperty("db.password", password);
                p.setProperty("dump.file", dumpFile.getAbsolutePath());
                if (memoryBudget != null) p.setProperty("memory.budget", String.valueOf(memoryBudget));
                if (threads != null) p.setProperty("worker.threads", String.valueOf(threads));
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetDhp)) {
                    p.store(fos, "Dynamic Heap Parser (DHP) Descriptor");
                    System.out.println("Exported MAT DHP descriptor: " + targetDhp.getAbsolutePath());
                } catch (IOException e) {
                    log.warn("Failed to export DHP descriptor: {}", e.getMessage());
                }
            }

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
        if (jdbcUrl == null) {
            jdbcUrl = props.getProperty("db.url", props.getProperty("jdbcurl", props.getProperty("url", null)));
            if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.startsWith("jdbc:sqlite::memory:")) {
                String sub = jdbcUrl.substring("jdbc:sqlite:".length());
                File dbf = new File(sub);
                if (!dbf.isAbsolute() && file.getParentFile() != null) {
                    jdbcUrl = "jdbc:sqlite:" + new File(file.getParentFile(), sub).getAbsolutePath();
                }
            }
        }
        if (user == null || user.isEmpty()) user = props.getProperty("db.user", props.getProperty("user", ""));
        if (password == null || password.isEmpty()) password = props.getProperty("db.password", props.getProperty("password", ""));
        if (dumpFile == null) {
            String dumpPath = props.getProperty("dump.file", props.getProperty("dump", null));
            if (dumpPath != null) {
                File df = new File(dumpPath);
                if (!df.isAbsolute() && file.getParentFile() != null) {
                    df = new File(file.getParentFile(), dumpPath);
                }
                dumpFile = df;
            }
        }
        if (memoryBudgetString == null) {
            String mem = props.getProperty("memory.budget", props.getProperty("memory", null));
            if (mem != null) {
                memoryBudgetString = mem;
            }
        }
        if (threads == null) {
            String thr = props.getProperty("worker.threads", props.getProperty("threads", null));
            if (thr != null) {
                try {
                    threads = Integer.parseInt(thr.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    public static long parseMemoryString(String val) {
        if (val == null || val.isBlank()) return 0L;
        val = val.trim().toUpperCase();
        long multiplier = 1L;
        String numStr = val;
        if (val.endsWith("GB") || val.endsWith("G")) {
            multiplier = 1024L * 1024L * 1024L;
            numStr = val.replaceAll("[^0-9.]", "");
        } else if (val.endsWith("MB") || val.endsWith("M")) {
            multiplier = 1024L * 1024L;
            numStr = val.replaceAll("[^0-9.]", "");
        } else if (val.endsWith("KB") || val.endsWith("K")) {
            multiplier = 1024L;
            numStr = val.replaceAll("[^0-9.]", "");
        } else if (val.endsWith("B")) {
            numStr = val.replaceAll("[^0-9.]", "");
        }
        if (numStr.contains(".")) {
            return (long) (Double.parseDouble(numStr) * multiplier);
        }
        return Long.parseLong(numStr) * multiplier;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DhpMain()).execute(args);
        System.exit(exitCode);
    }
}
