package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.dhp.core.memory.MemoryGovernor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * What a {@code .dhp} descriptor (or a bare {@code .hprof} handed to the index builder) says: where the database is, which
 * dump it was built from and how much memory ingestion may use. Relative paths are resolved against the folder of the
 * descriptor, JDBC URLs for SQLite always use forward slashes (also on Windows).
 *
 * <pre>
 * db.url=jdbc:sqlite:heapdump.dhp.db      (default: &lt;name&gt;.dhp.db next to the descriptor)
 * db.user= / db.password=                 (PostgreSQL)
 * dump.file=heapdump.hprof                (needed to ingest on the fly and to read field values)
 * memory.budget=4G
 * </pre>
 */
record DhpDescriptor(File file, String jdbcUrl, String user, String password, File hprofFile, long memoryBudget) {
    static final long DEFAULT_MEMORY_BUDGET = 1024L * 1024 * 1024;

    static boolean isDescriptor(File f) {
        return f != null && f.getName().toLowerCase().endsWith(".dhp");
    }

    /** Reads a descriptor, or derives the defaults for a bare dump file. */
    static DhpDescriptor read(File descriptorOrDump) throws IOException {
        File abs = descriptorOrDump.getAbsoluteFile();
        File dir = abs.getParentFile();
        String defaultDb = new File(dir, baseName(abs) + ".dhp.db").getAbsolutePath().replace('\\', '/');
        if (!isDescriptor(abs)) {
            return new DhpDescriptor(abs, "jdbc:sqlite:" + defaultDb, "", "", abs, DEFAULT_MEMORY_BUDGET);
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(abs)) {
            props.load(in);
        }
        String url = props.getProperty("db.url", "jdbc:sqlite:" + defaultDb);
        if (url.startsWith("jdbc:sqlite:") && !url.startsWith("jdbc:sqlite::memory:")) {
            File db = new File(url.substring("jdbc:sqlite:".length()));
            File resolved = db.isAbsolute() || dir == null ? db : new File(dir, db.getPath());
            url = "jdbc:sqlite:" + resolved.getAbsolutePath().replace('\\', '/');
        }
        File hprof = null;
        String dump = props.getProperty("dump.file");
        if (dump != null && !dump.isBlank()) {
            File candidate = new File(dump.trim());
            hprof = candidate.isAbsolute() || dir == null ? candidate : new File(dir, dump.trim());
        }
        long budget = DEFAULT_MEMORY_BUDGET;
        String mem = props.getProperty("memory.budget");
        if (mem != null && !mem.isBlank()) {
            try {
                budget = MemoryGovernor.parseSize(mem.trim());
            } catch (RuntimeException ignored) {
                // keep the default
            }
        }
        return new DhpDescriptor(abs, url, props.getProperty("db.user", ""), props.getProperty("db.password", ""), hprof, budget);
    }

    /**
     * Prefix of the files MAT derives from the snapshot (it reads {@code <prefix>threads}). The CLI writes the
     * {@code .threads} file next to the dump, so follow the dump when the descriptor has another name or lives elsewhere.
     */
    String prefix() {
        File dir = file.getParentFile();
        String prefix = new File(dir, baseName(file) + ".").getAbsolutePath().replace('\\', '/');
        if (hprofFile != null && !new File(prefix + "threads").exists()) {
            File dumpDir = hprofFile.getAbsoluteFile().getParentFile();
            String dumpPrefix = new File(dumpDir, baseName(hprofFile) + ".").getAbsolutePath().replace('\\', '/');
            if (new File(dumpPrefix + "threads").exists()) {
                return dumpPrefix;
            }
        }
        return prefix;
    }

    boolean isGzip() {
        return hprofFile != null && hprofFile.getName().toLowerCase().endsWith(".gz");
    }

    private static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
