package org.eclipse.mat.dhp.storage.jdbc;

import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * High-performance JDBC storage engine supporting SQLite and PostgreSQL
 * with tuned batching, PRAGMAs, and indexing.
 */
public class JdbcHeapStorageEngine implements HeapStorageEngine {
    private static final Logger log = LoggerFactory.getLogger(JdbcHeapStorageEngine.class);

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable ignored) {}
        try {
            Class.forName("org.postgresql.Driver");
        } catch (Throwable ignored) {}
    }

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final boolean isSqlite;
    private final boolean isPostgres;

    private Connection connection;
    /** Memory-mapped chunk store next to a file-backed SQLite database; null when chunks live in the dhp_chunks table. */
    private volatile ChunkFile chunkFile;

    public JdbcHeapStorageEngine(String jdbcUrl, String user, String password, long memoryBudgetBytes) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.isSqlite = jdbcUrl.startsWith("jdbc:sqlite:");
        this.isPostgres = jdbcUrl.startsWith("jdbc:postgresql:");

        this.connection = DriverManager.getConnection(jdbcUrl, user, password);

        if (isSqlite) {
            configureSqlite(memoryBudgetBytes);
        } else if (isPostgres) {
            configurePostgres();
        }

        this.connection.setAutoCommit(false);
        if (isSqlite) {
            openChunkFileIfPresent();
        }
    }

    private static final int[] SPARSE_KINDS = {11, 12, 13, 15}; // K_OUT_HUGE, K_IN_HUGE, K_CHILD_HUGE, K_CLASS_INSTANCES

    /** The chunk file that belongs to this database, or null for in-memory and non-SQLite databases. */
    private File chunkFilePath() {
        if (!isSqlite || jdbcUrl.contains(":memory:") || jdbcUrl.contains("mode=memory")) {
            return null;
        }
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        return path.isEmpty() ? null : new File(path + ".chunks");
    }

    private void openChunkFileIfPresent() throws SQLException {
        File f = chunkFilePath();
        if (f == null) {
            return;
        }
        String flag = null;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT property_value FROM dhp_snapshot_info WHERE property_key = 'chunkFile'")) {
            if (rs.next()) {
                flag = rs.getString(1);
            }
        } catch (SQLException noTable) {
            return; // not ingested yet
        }
        if (!"1".equals(flag)) {
            return;
        }
        try {
            chunkFile = ChunkFile.open(f, SPARSE_KINDS);
        } catch (IOException e) {
            throw new SQLException("The database " + jdbcUrl + " keeps its data in " + f + ", which cannot be read: " + e.getMessage(), e);
        }
    }

    private void configureSqlite(long memoryBudgetBytes) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA page_size = 16384;"); // only effective on a fresh database
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            // Set cache size: negative number indicates KiB (cap at 128 MB to preserve OS RAM)
            long cacheKiB = Math.min(128 * 1024, Math.max(32 * 1024, memoryBudgetBytes / 1024 / 16));
            stmt.execute("PRAGMA cache_size = -" + cacheKiB + ";");
            stmt.execute("PRAGMA temp_store = FILE;"); // Always use disk for temp sorting to protect system RAM
            stmt.execute("PRAGMA mmap_size = 268435456;"); // 256 MB bounded mmap, not 4 GB
            stmt.execute("PRAGMA wal_autocheckpoint = 10000;");
            stmt.execute("PRAGMA threads = 4;");
        }
        log.info("Configured SQLite for high performance bounded WAL ingestion with safe temp_store=FILE");
    }

    /** Journal mode cannot change inside a transaction, so switch to autocommit around the pragmas. */
    private void setJournalPragmas(String journalMode, String synchronous) throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = " + journalMode + ";");
            stmt.execute("PRAGMA synchronous = " + synchronous + ";");
        } finally {
            connection.setAutoCommit(false);
        }
    }

    private void configurePostgres() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET synchronous_commit = OFF;");
        }
        log.info("Configured PostgreSQL with asynchronous commit for high ingestion throughput");
    }

    @Override
    public boolean hasExistingTables() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null && tableName.toLowerCase().startsWith("dhp_")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void dropExistingTables() throws SQLException {
        log.info("Dropping existing DHP tables cleanly...");
        String[] tables = {
            "dhp_chunks",
            "dhp_gc_roots",
            "dhp_class_stats",
            "dhp_class_order",
            "dhp_classes",
            "dhp_snapshot_info"
        };
        try (Statement stmt = connection.createStatement()) {
            for (String table : tables) {
                stmt.execute("DROP TABLE IF EXISTS " + table + (isPostgres ? " CASCADE" : ""));
            }
        }
        connection.commit();
        if (isSqlite) {
            // dropping tables only frees pages inside the file: give the space back (VACUUM cannot run in a transaction)
            connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("VACUUM");
            } finally {
                connection.setAutoCommit(false);
            }
        }
        ChunkFile old = chunkFile;
        chunkFile = null;
        File cf = chunkFilePath();
        try {
            if (old != null) {
                old.closeAndUnmap();
            }
            if (cf != null) {
                java.nio.file.Files.deleteIfExists(cf.toPath());
            }
        } catch (IOException e) {
            throw new SQLException("Cannot delete " + cf + ": " + e.getMessage(), e);
        }
        log.info("Existing DHP tables successfully dropped.");
    }

    @Override
    public void initializeSchema() throws SQLException {
        File cf = chunkFilePath();
        if (cf != null && chunkFile == null) {
            try {
                chunkFile = ChunkFile.create(cf, SPARSE_KINDS);
            } catch (IOException e) {
                throw new SQLException("Cannot create " + cf + ": " + e.getMessage(), e);
            }
        }
        String unlogged = isPostgres ? "UNLOGGED " : "";
        if (isSqlite) {
            // Bulk load: a crashed ingestion is simply redone, so skip the journal and fsyncs (every page is
            // written once instead of twice). saveDominators() restores WAL once the data is complete.
            setJournalPragmas("OFF", "OFF");
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_snapshot_info (" +
                    "property_key VARCHAR(128) PRIMARY KEY, " +
                    "property_value TEXT" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_classes (" +
                    "class_id BIGINT PRIMARY KEY, " +
                    "super_class_id BIGINT, " +
                    "class_loader_id BIGINT, " +
                    "class_name VARCHAR(1024) NOT NULL, " +
                    "instance_size INT NOT NULL, " +
                    "fields_data TEXT, " +
                    "class_obj_id INT DEFAULT -1, " +
                    "super_class_obj_id INT DEFAULT -1, " +
                    "class_loader_obj_id INT DEFAULT -1, " +
                    "used_size BIGINT DEFAULT 0, " +
                    "static_fields_data TEXT" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_class_order (seq INT, old_id INT, class_id BIGINT, new_id INT)");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_class_stats (" +
                    "class_id INT PRIMARY KEY, " +
                    "instance_count INT NOT NULL, " +
                    "total_size BIGINT NOT NULL" +
                    ");");

            // Chunked blob store for all per-object data (see saveObjects / saveOutboundEdges / ...)
            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_chunks (" +
                    "chunk_key " + (isPostgres ? "BIGINT" : "INTEGER") + " PRIMARY KEY, " +
                    "data " + (isPostgres ? "BYTEA" : "BLOB") + " NOT NULL" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_gc_roots (" +
                    "object_id INT NOT NULL, " +
                    "object_address BIGINT NOT NULL, " +
                    "referrer_address BIGINT, " +
                    "root_type INT NOT NULL, " +
                    "thread_address BIGINT, " +
                    "thread_object_id INT DEFAULT -1, " +
                    "old_object_id INT DEFAULT -1, " +
                    "old_thread_object_id INT DEFAULT -1, " +
                    "root_seq INT NOT NULL DEFAULT 0" +
                    ");");

        }
        connection.commit();
        log.info("Initialized raw unindexed heap tables for maximum bulk ingestion throughput.");
    }

    @Override
    public void saveSnapshotInfo(String key, String value) throws SQLException {
        String sql = isPostgres 
                ? "INSERT INTO dhp_snapshot_info(property_key, property_value) VALUES(?, ?) ON CONFLICT (property_key) DO UPDATE SET property_value = EXCLUDED.property_value"
                : "INSERT OR REPLACE INTO dhp_snapshot_info(property_key, property_value) VALUES(?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
        connection.commit();
    }

    @Override
    public String getSnapshotInfo(String key) throws SQLException {
        synchronized (connection) {
            return getSnapshotInfoLocked(key);
        }
    }

    private String getSnapshotInfoLocked(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT property_value FROM dhp_snapshot_info WHERE property_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    @Override
    public void saveClasses(Collection<HeapRecords.ClassRecord> classes) throws SQLException {
        String sql = isPostgres
                ? "INSERT INTO dhp_classes(class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, static_fields_data) VALUES(?, ?, ?, ?, ?, ?, ?) ON CONFLICT (class_id) DO NOTHING"
                : "INSERT OR REPLACE INTO dhp_classes(class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, static_fields_data) VALUES(?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapRecords.ClassRecord cls : classes) {
                ps.setLong(1, cls.classId());
                ps.setLong(2, cls.superClassId());
                ps.setLong(3, cls.classLoaderId());
                ps.setString(4, cls.name());
                ps.setInt(5, cls.instanceSize());
                StringBuilder sb = new StringBuilder();
                for (var f : cls.fields()) {
                    if (!sb.isEmpty()) sb.append(';');
                    sb.append(f.name()).append(':').append(f.type());
                }
                ps.setString(6, sb.toString());

                StringBuilder sfSb = new StringBuilder();
                for (var sf : cls.staticFields()) {
                    if (!sfSb.isEmpty()) sfSb.append(';');
                    String valStr;
                    if (sf.value() instanceof Character c) {
                        valStr = String.valueOf((int) c);
                    } else {
                        valStr = sf.value() != null ? String.valueOf(sf.value()) : "";
                    }
                    sfSb.append(encodeField(sf.name())).append(':')
                        .append(sf.type()).append(':')
                        .append(encodeField(valStr));
                }
                ps.setString(7, sfSb.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public void saveClassStats(Collection<ClassStats> stats) throws SQLException {
        if (stats == null || stats.isEmpty()) return;
        String sql = isPostgres
                ? "INSERT INTO dhp_class_stats(class_id, instance_count, total_size) VALUES(?, ?, ?) ON CONFLICT (class_id) DO UPDATE SET instance_count = EXCLUDED.instance_count, total_size = EXCLUDED.total_size"
                : "INSERT OR REPLACE INTO dhp_class_stats(class_id, instance_count, total_size) VALUES(?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ClassStats s : stats) {
                ps.setInt(1, s.classObjId());
                ps.setInt(2, s.instanceCount());
                ps.setLong(3, s.totalSize());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
        log.info("Persisted pre-calculated class stats in memory for {} classes.", stats.size());
    }

    @Override
    public void updateClassesMetadata(Collection<ResolvedClassMetadata> classes) throws SQLException {
        if (classes == null || classes.isEmpty()) return;
        String sql = "UPDATE dhp_classes SET class_obj_id = ?, super_class_obj_id = ?, class_loader_obj_id = ?, used_size = ? WHERE class_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ResolvedClassMetadata c : classes) {
                ps.setInt(1, c.classObjId());
                ps.setInt(2, c.superClassObjId());
                ps.setInt(3, c.classLoaderObjId());
                ps.setLong(4, c.usedSize());
                ps.setLong(5, c.classAddress());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
        log.info("Persisted pre-resolved class hierarchy & metadata for {} classes.", classes.size());
    }

    @Override
    public void saveClassOrder(List<long[]> rows) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO dhp_class_order(seq, old_id, class_id, new_id) VALUES(?, ?, ?, ?)")) {
            for (long[] r : rows) {
                ps.setInt(1, (int) r[0]);
                ps.setInt(2, (int) r[1]);
                ps.setLong(3, r[2]);
                ps.setInt(4, (int) r[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public List<long[]> getClassOrder() throws SQLException {
        List<long[]> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT seq, old_id, class_id, new_id FROM dhp_class_order ORDER BY seq")) {
            while (rs.next()) {
                list.add(new long[]{rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)});
            }
        } catch (SQLException e) {
            return List.of(); // ingested before this table existed
        }
        return list;
    }

    @Override
    public List<HeapRecords.ClassRecord> getAllClasses() throws SQLException {
        List<HeapRecords.ClassRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            boolean loaded = false;
            try (ResultSet rs = stmt.executeQuery("SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, class_obj_id, super_class_obj_id, class_loader_obj_id, used_size, static_fields_data FROM dhp_classes")) {
                loaded = true;
                while (rs.next()) {
                    list.add(new HeapRecords.ClassRecord(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getInt(5),
                            parseFieldsData(rs.getString(6)),
                            parseStaticFieldsData(rs.getString(11)),
                            rs.getInt(7),
                            rs.getInt(8),
                            rs.getInt(9),
                            rs.getLong(10)
                    ));
                }
            } catch (SQLException ignored) {}

            if (!loaded) {
                try (ResultSet rs = stmt.executeQuery("SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, class_obj_id, super_class_obj_id, class_loader_obj_id, used_size FROM dhp_classes")) {
                    loaded = true;
                    while (rs.next()) {
                        list.add(new HeapRecords.ClassRecord(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getInt(5),
                                parseFieldsData(rs.getString(6)),
                                List.of(),
                                rs.getInt(7),
                                rs.getInt(8),
                                rs.getInt(9),
                                rs.getLong(10)
                        ));
                    }
                } catch (SQLException ignored) {}
            }

            if (!loaded) {
                try (ResultSet rs = stmt.executeQuery("SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data FROM dhp_classes")) {
                    while (rs.next()) {
                        list.add(new HeapRecords.ClassRecord(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getInt(5),
                                parseFieldsData(rs.getString(6)),
                                List.of()
                        ));
                    }
                }
            }
        }
        return list;
    }

    @Override
    public HeapRecords.ClassRecord getClassById(long classId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, class_obj_id, super_class_obj_id, class_loader_obj_id, used_size, static_fields_data FROM dhp_classes WHERE class_id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HeapRecords.ClassRecord(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getInt(5),
                            parseFieldsData(rs.getString(6)),
                            parseStaticFieldsData(rs.getString(11)),
                            rs.getInt(7),
                            rs.getInt(8),
                            rs.getInt(9),
                            rs.getLong(10)
                    );
                }
            }
        } catch (SQLException e) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data, static_fields_data FROM dhp_classes WHERE class_id = ?")) {
                ps.setLong(1, classId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new HeapRecords.ClassRecord(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getInt(5),
                                parseFieldsData(rs.getString(6)),
                                parseStaticFieldsData(rs.getString(7))
                        );
                    }
                }
            }
        }
        return null;
    }

    private static List<HeapRecords.FieldDescriptor> parseFieldsData(String fieldsData) {
        if (fieldsData == null || fieldsData.isEmpty()) {
            return List.of();
        }
        List<HeapRecords.FieldDescriptor> list = new ArrayList<>();
        for (String token : fieldsData.split(";")) {
            int idx = token.lastIndexOf(':');
            if (idx > 0) {
                String fName = token.substring(0, idx);
                try {
                    int fType = Integer.parseInt(token.substring(idx + 1));
                    list.add(new HeapRecords.FieldDescriptor(fName, fType));
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }

    private static List<HeapRecords.StaticFieldRecord> parseStaticFieldsData(String data) {
        if (data == null || data.isBlank()) return List.of();
        List<HeapRecords.StaticFieldRecord> list = new ArrayList<>();
        String[] entries = data.split(";");
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            int idx1 = entry.indexOf(':');
            int idx2 = entry.indexOf(':', idx1 + 1);
            if (idx1 != -1 && idx2 != -1) {
                String name = decodeField(entry.substring(0, idx1));
                try {
                    int type = Integer.parseInt(entry.substring(idx1 + 1, idx2));
                    String rawVal = decodeField(entry.substring(idx2 + 1));
                    Object val = parseStaticFieldValue(type, rawVal);
                    list.add(new HeapRecords.StaticFieldRecord(name, type, val));
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }

    private static Object parseStaticFieldValue(int type, String rawVal) {
        if (rawVal == null || rawVal.isEmpty() || "null".equals(rawVal)) {
            return null;
        }
        try {
            return switch (type) {
                case 2 -> Long.parseLong(rawVal); // OBJECT: object address
                case 4 -> Boolean.parseBoolean(rawVal); // BOOLEAN
                case 5 -> {
                    try {
                        yield (char) Integer.parseInt(rawVal);
                    } catch (NumberFormatException ignored) {
                        yield rawVal.isEmpty() ? '\0' : rawVal.charAt(0);
                    }
                }
                case 6 -> Float.parseFloat(rawVal); // FLOAT
                case 7 -> Double.parseDouble(rawVal); // DOUBLE
                case 8 -> Byte.parseByte(rawVal); // BYTE
                case 9 -> Short.parseShort(rawVal); // SHORT
                case 10 -> Integer.parseInt(rawVal); // INT
                case 11 -> Long.parseLong(rawVal); // LONG
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodeField(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace(":", "%3A").replace(";", "%3B").replace("\0", "%00");
    }

    private static String decodeField(String s) {
        if (s == null) return "";
        return s.replace("%00", "\0").replace("%3B", ";").replace("%3A", ":").replace("%25", "%");
    }

    /** Position of the next root; the order roots were reported in is what MAT's own structures are built from. */
    private int nextRootSeq;

    @Override
    public void insertGcRootsBatch(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException {
        nextRootSeq = 0;
        String sql = "INSERT INTO dhp_gc_roots(object_id, object_address, referrer_address, root_type, thread_address, thread_object_id, old_object_id, old_thread_object_id, root_seq) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Statement del = connection.createStatement()) {
            del.execute("DELETE FROM dhp_gc_roots"); // a retried ingestion must not duplicate roots
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapRecords.GcRootRecord root : gcRoots) {
                ps.setInt(1, root.objectId());
                ps.setLong(2, root.objectAddress());
                ps.setLong(3, root.referrerAddress());
                ps.setInt(4, root.rootType());
                ps.setLong(5, root.threadAddress());
                ps.setInt(6, root.threadObjectId());
                ps.setInt(7, root.oldObjectId());
                ps.setInt(8, root.oldThreadObjectId());
                ps.setInt(9, nextRootSeq++);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public synchronized void appendGcRoots(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException {
        if (nextRootSeq == 0) {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT coalesce(max(root_seq) + 1, 0) FROM dhp_gc_roots")) {
                nextRootSeq = rs.next() ? rs.getInt(1) : 0;
            }
        }
        String sql = "INSERT INTO dhp_gc_roots(object_id, object_address, referrer_address, root_type, thread_address, thread_object_id, old_object_id, old_thread_object_id, root_seq) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapRecords.GcRootRecord root : gcRoots) {
                long address = root.objectAddress() != 0 ? root.objectAddress() : getObjectAddress(root.objectId());
                ps.setInt(1, root.objectId());
                ps.setLong(2, address);
                ps.setLong(3, root.referrerAddress());
                ps.setInt(4, root.rootType());
                ps.setLong(5, root.threadAddress());
                ps.setInt(6, root.threadObjectId());
                ps.setInt(7, root.oldObjectId());
                ps.setInt(8, root.oldThreadObjectId());
                ps.setInt(9, nextRootSeq++);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public void finishIngestion() throws SQLException {
        long start = System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_gc_roots_obj ON dhp_gc_roots(object_id);");
            if (isSqlite) {
                stmt.execute("PRAGMA optimize;");
            } else if (isPostgres) {
                stmt.execute("ANALYZE;");
            }
        }
        connection.commit();
        log.info("Ingestion finalized in {} ms.", System.currentTimeMillis() - start);
    }

    // =====================================================================================================
    // Chunked blob store: per-object data is packed into fixed-size chunks (one row per chunk) instead of one
    // row per object/edge. Ingestion issues ~1000x fewer inserts; point lookups fetch one small blob that is
    // kept in a byte-bounded LRU cache.
    // =====================================================================================================

    static final int K_ADDR = 1, K_CLASS = 2, K_SIZE = 3, K_POS = 4, K_FLAGS = 5, K_OUT = 6, K_IN = 7,
            K_DOM = 8, K_RET = 9, K_CHILD = 10, K_OUT_HUGE = 11, K_IN_HUGE = 12, K_CHILD_HUGE = 13,
            K_ROOT_CHILDREN = 14, K_CLASS_INSTANCES = 15, K_ADDR_INDEX = 16;
    static final int SC = 512;           // objects per scalar chunk (long chunks = 4 KB, three fit a 16 KB page)
    static final int EC = 32;            // objects per edge chunk (fits the 32-bit "huge list" mask)
    static final int HUGE_LIST = 16384;  // lists longer than this are stored out of line

    private static final java.lang.invoke.VarHandle INT_LE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final java.lang.invoke.VarHandle LONG_LE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    private static long key(int kind, long chunk) {
        return ((long) kind << 32) | chunk;
    }

    private PreparedStatement psChunkPut;
    private int pendingPuts;
    private volatile int cachedObjectCount = -1;
    private volatile long[] addrIndex;

    // ---- concurrent read path -------------------------------------------------------------------------
    // MAT calls the index adapters from many worker threads. Every thread gets its own read connection
    // (SQLite WAL allows concurrent readers) and a small lock-free direct-mapped chunk cache, so reads never
    // contend on a lock or a shared statement.
    private static final int CACHE_SLOTS = 256;
    private static final int CACHE_MAX_BLOB = 256 * 1024;

    private final class ReadHandle {
        final Connection conn;
        final boolean shared;
        final PreparedStatement psChunk;
        final long[] keys = new long[CACHE_SLOTS];
        final byte[][] vals = new byte[CACHE_SLOTS][];

        ReadHandle(Connection conn, boolean shared) throws SQLException {
            this.conn = conn;
            this.shared = shared;
            this.psChunk = conn == null ? null : conn.prepareStatement("SELECT data FROM dhp_chunks WHERE chunk_key = ?");
            java.util.Arrays.fill(keys, -1L);
        }
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<ReadHandle> readHandles = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ThreadLocal<ReadHandle> readHandle = new ThreadLocal<>();

    private ReadHandle handle() throws SQLException {
        ReadHandle h = readHandle.get();
        if (h == null && chunkFile != null) {
            h = new ReadHandle(null, false); // chunks come from the mapped file: no connection needed
            readHandles.add(h);
            readHandle.set(h);
        } else if (h == null) {
            boolean sharedOnly = isSqlite && (jdbcUrl.contains(":memory:") || jdbcUrl.contains("mode=memory"));
            if (sharedOnly) {
                synchronized (connection) {
                    h = new ReadHandle(connection, true);
                }
            } else {
                Connection c = DriverManager.getConnection(jdbcUrl, user, password);
                if (isSqlite) {
                    try (Statement st = c.createStatement()) {
                        st.execute("PRAGMA cache_size = -32768;");
                        st.execute("PRAGMA mmap_size = 137438953472;"); // 128 GB of address space: the database is read through the page cache, never copied
                    }
                }
                h = new ReadHandle(c, false);
            }
            readHandles.add(h);
            readHandle.set(h);
        }
        return h;
    }

    private byte[] chunk(int kind, long chunkNo) throws SQLException {
        long k = key(kind, chunkNo);
        ReadHandle h = handle();
        int slot = (int) ((k ^ (k >>> 11) ^ (k >>> 29)) & (CACHE_SLOTS - 1));
        if (h.keys[slot] == k) {
            return h.vals[slot];
        }
        byte[] b;
        ChunkFile cf = chunkFile;
        if (cf != null) {
            try {
                b = cf.read(kind, chunkNo);
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot read " + cf + ": " + e.getMessage(), e);
            }
        } else if (h.shared) {
            synchronized (connection) {
                b = fetch(h, k);
            }
        } else {
            b = fetch(h, k);
        }
        if (b == null || b.length <= CACHE_MAX_BLOB) {
            h.keys[slot] = k;
            h.vals[slot] = b;
        }
        return b;
    }

    private static byte[] fetch(ReadHandle h, long k) throws SQLException {
        h.psChunk.setLong(1, k);
        try (ResultSet rs = h.psChunk.executeQuery()) {
            return rs.next() ? rs.getBytes(1) : null;
        }
    }

    private void putChunk(int kind, long chunkNo, byte[] data) throws SQLException {
        ChunkFile cf = chunkFile;
        if (cf != null) {
            try {
                cf.put(kind, chunkNo, data);
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot write " + cf + ": " + e.getMessage(), e);
            }
            return;
        }
        if (psChunkPut == null) {
            psChunkPut = connection.prepareStatement(
                    isPostgres ? "INSERT INTO dhp_chunks(chunk_key, data) VALUES(?, ?) ON CONFLICT (chunk_key) DO UPDATE SET data = EXCLUDED.data"
                               : "INSERT OR REPLACE INTO dhp_chunks(chunk_key, data) VALUES(?, ?)");
        }
        psChunkPut.setLong(1, key(kind, chunkNo));
        psChunkPut.setBytes(2, data);
        psChunkPut.addBatch();
        if (++pendingPuts >= 512) {
            flushPuts();
        }
    }

    private void flushPuts() throws SQLException {
        if (pendingPuts > 0) {
            psChunkPut.executeBatch();
            connection.commit();
            pendingPuts = 0;
        }
    }

    @Override
    public synchronized void saveObjects(org.eclipse.mat.dhp.core.graph.HeapGraph g) throws SQLException {
        long t0 = System.currentTimeMillis();
        int n = g.objectCount();
        int chunks = (n + SC - 1) / SC;
        long[] firstAddr = new long[Math.max(1, chunks)];
        byte[] bAddr = new byte[SC * 8], bClass = new byte[SC * 4], bSize = new byte[SC * 8],
                bPos = new byte[SC * 8], bFlags = new byte[SC / 8];
        for (int c = 0; c < chunks; c++) {
            int from = c * SC;
            int len = Math.min(SC, n - from);
            java.util.Arrays.fill(bFlags, (byte) 0);
            for (int i = 0; i < len; i++) {
                int id = from + i;
                long a = g.addressOf(id);
                LONG_LE.set(bAddr, i * 8, a);
                INT_LE.set(bClass, i * 4, g.classObjId(id));
                LONG_LE.set(bSize, i * 8, g.usedSize(id));
                LONG_LE.set(bPos, i * 8, g.filePosition(id));
                if (g.isArray(id)) bFlags[i >> 3] |= (byte) (1 << (i & 7));
            }
            firstAddr[c] = len > 0 ? g.addressOf(from) : 0L;
            putChunk(K_ADDR, c, java.util.Arrays.copyOf(bAddr, len * 8));
            putChunk(K_CLASS, c, java.util.Arrays.copyOf(bClass, len * 4));
            putChunk(K_SIZE, c, java.util.Arrays.copyOf(bSize, len * 8));
            putChunk(K_POS, c, java.util.Arrays.copyOf(bPos, len * 8));
            putChunk(K_FLAGS, c, java.util.Arrays.copyOf(bFlags, (len + 7) / 8));
        }
        byte[] idx = new byte[firstAddr.length * 8];
        for (int i = 0; i < firstAddr.length; i++) LONG_LE.set(idx, i * 8, firstAddr[i]);
        putChunk(K_ADDR_INDEX, 0, idx);

        flushPuts();
        // numberOfObjects is persisted last (end of saveDominators): readers treat a missing value as "no snapshot",
        // so an interrupted ingestion is never mistaken for a complete one.
        cachedObjectCount = n;
        addrIndex = firstAddr;
        log.info("Persisted {} objects in {} chunks ({} ms)", n, chunks, System.currentTimeMillis() - t0);
    }

    /**
     * Objects grouped by class (counting sort over the stored class column). Done at the end of ingestion from the
     * persisted column, when the large in-memory structures have been released, instead of while the whole graph is
     * still on the heap.
     */
    private void saveClassInstances(int n) throws SQLException {
        long t0 = System.currentTimeMillis();
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap counts = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        scanKind(K_CLASS, (c, b) -> {
            for (int i = 0; i * 4 < b.length; i++) {
                int cl = (int) INT_LE.get(b, i * 4);
                if (cl >= 0 && c * SC + i < n) counts.addTo(cl, 1);
            }
        });
        int[] classIds = counts.keySet().toIntArray();
        java.util.Arrays.sort(classIds);
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap start = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int total = 0;
        for (int cl : classIds) {
            start.put(cl, total);
            total += counts.get(cl);
        }
        int[] sorted = new int[total];
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap fill = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(start);
        scanKind(K_CLASS, (c, b) -> {
            for (int i = 0; i * 4 < b.length; i++) {
                int cl = (int) INT_LE.get(b, i * 4);
                int id = c * SC + i;
                if (cl >= 0 && id < n) sorted[fill.addTo(cl, 1)] = id;
            }
        });
        for (int cl : classIds) {
            putChunk(K_CLASS_INSTANCES, cl, toBytes(sorted, start.get(cl), counts.get(cl)));
        }
        flushPuts();
        log.info("Persisted instance lists of {} classes ({} ms)", classIds.length, System.currentTimeMillis() - t0);
    }

    private static byte[] toBytes(int[] a, int from, int len) {
        byte[] b = new byte[len * 4];
        for (int i = 0; i < len; i++) INT_LE.set(b, i * 4, a[from + i]);
        return b;
    }

    private static int[] toInts(byte[] b, int byteFrom, int count) {
        int[] r = new int[count];
        for (int i = 0; i < count; i++) r[i] = (int) INT_LE.get(b, byteFrom + i * 4);
        return r;
    }

    /** Writes edge-list chunks; {@code src} supplies the list of one object. */
    private interface ListSource {
        int load(int id, int[] dst);   // returns length, dst grows via scratch holder
    }

    @Override
    public synchronized void saveOutboundEdges(org.eclipse.mat.dhp.core.graph.HeapGraph g) throws SQLException {
        long t0 = System.currentTimeMillis();
        int n = g.objectCount();
        int[] scratch = new int[HUGE_LIST + 1];
        int[][] holder = {null};
        writeEdgeChunks(n, K_OUT, K_OUT_HUGE, id -> {
            int deg = g.outDegree(id);
            if (deg > scratch.length) {
                holder[0] = new int[deg];
                g.copyOutEdges(id, holder[0]);
                return holder[0];
            }
            g.copyOutEdges(id, scratch);
            holder[0] = null;
            return scratch;
        }, g::outDegree);
        log.info("Persisted {} outbound references ({} ms)", g.edgeCount(), System.currentTimeMillis() - t0);
    }

    @Override
    public synchronized void saveInboundEdges(int[] head, int[] to, int n, int ignored) throws SQLException {
        long t0 = System.currentTimeMillis();
        int[][] holder = {new int[256]};
        writeEdgeChunks(n, K_IN, K_IN_HUGE, id -> {
            int s = head[id], e = head[id + 1];
            int[] buf = holder[0];
            if (buf.length < e - s) buf = holder[0] = new int[Math.max(e - s, buf.length * 2)];
            int len = 0;
            for (int i = s; i < e; i++) {
                if (to[i] != ignored) buf[len++] = to[i];
            }
            return buf;
        }, id -> {
            int len = 0;
            for (int i = head[id], e = head[id + 1]; i < e; i++) {
                if (to[i] != ignored) len++;
            }
            return len;
        });
        log.info("Persisted inbound references ({} ms)", System.currentTimeMillis() - t0);
    }

    private interface IntSource {
        int[] get(int id);
    }

    private interface IntFn {
        int apply(int id);
    }

    /** Edge chunk layout: [huge-mask int][EC+1 cumulative offsets][targets...]; huge lists live in their own row. */
    private void writeEdgeChunks(int n, int kind, int hugeKind, IntSource source, IntFn degree) throws SQLException {
        int chunks = (n + EC - 1) / EC;
        int[] lens = new int[EC];
        for (int c = 0; c < chunks; c++) {
            int from = c * EC;
            int cnt = Math.min(EC, n - from);
            int mask = 0;
            int total = 0;
            for (int i = 0; i < cnt; i++) {
                int len = degree.apply(from + i);
                lens[i] = len;
                if (len > HUGE_LIST) {
                    mask |= 1 << i;
                } else {
                    total += len;
                }
            }
            byte[] blob = new byte[4 * (1 + EC + 1 + total)];
            INT_LE.set(blob, 0, mask);
            int off = 0;
            int p = 4 * (1 + EC + 1);
            for (int i = 0; i < EC; i++) {
                INT_LE.set(blob, 4 + 4 * i, off);
                if (i >= cnt) {
                    continue;
                }
                int len = lens[i];
                if (len > HUGE_LIST) {
                    putChunk(hugeKind, from + i, toBytes(source.get(from + i), 0, len));
                } else if (len > 0) {
                    int[] list = source.get(from + i);
                    for (int k = 0; k < len; k++) {
                        INT_LE.set(blob, p, list[k]);
                        p += 4;
                    }
                    off += len;
                }
            }
            INT_LE.set(blob, 4 + 4 * EC, off);
            putChunk(kind, c, blob);
        }
        flushPuts();
    }

    private int[] readEdgeList(int kind, int hugeKind, int id) throws SQLException {
        if (id < 0) return new int[0];
        byte[] b = chunk(kind, id / EC);
        if (b == null) return new int[0];
        int i = id % EC;
        int mask = (int) INT_LE.get(b, 0);
        if ((mask & (1 << i)) != 0) {
            byte[] h = chunk(hugeKind, id);
            return h == null ? new int[0] : toInts(h, 0, h.length / 4);
        }
        int start = (int) INT_LE.get(b, 4 + 4 * i);
        int end = (int) INT_LE.get(b, 4 + 4 * (i + 1));
        return toInts(b, 4 * (1 + EC + 1) + 4 * start, end - start);
    }

    @Override
    public synchronized void saveDominators(int[] idom, long[] retained, int n) throws SQLException {
        long t0 = System.currentTimeMillis();
        int chunks = (n + SC - 1) / SC;
        for (int c = 0; c < chunks; c++) {
            int from = c * SC;
            int len = Math.min(SC, n - from);
            byte[] d = new byte[len * 4];
            byte[] r = new byte[len * 8];
            for (int i = 0; i < len; i++) {
                INT_LE.set(d, i * 4, idom[from + i]);
                LONG_LE.set(r, i * 8, retained[from + i]);
            }
            putChunk(K_DOM, c, d);
            putChunk(K_RET, c, r);
        }
        flushPuts();
        long t1 = System.currentTimeMillis();

        // Dominated lists in exactly the order MAT produces them: all objects sorted by dominator (vertex = id + 2, the
        // virtual root is vertex 1) with MAT's hybrid sort, then every list sorted by retained size, descending, with the
        // same sort (see MatSort): ties are left in the order MAT's algorithm leaves them.
        int[] start = new int[n + 2];   // index p + 1 (so that the -1 "super root" list sits at slot 0)
        for (int i = 0; i < n; i++) start[idom[i] + 1 + 1]++;
        for (int i = 0; i < n + 1; i++) start[i + 1] += start[i];
        int[] domKeys = new int[n + 2];
        int[] ids = new int[n + 2];
        for (int i = 0; i < n; i++) {
            domKeys[i + 2] = idom[i] + 2;
            ids[i + 2] = i;
        }
        org.eclipse.mat.dhp.core.graph.MatSort.sort(domKeys, ids, 2, n);
        domKeys = null;
        final int[] startF = start;
        final int[] idsF = ids;
        final ThreadLocal<long[]> keyScratch = ThreadLocal.withInitial(() -> new long[256]);
        final ThreadLocal<int[]> valScratch = ThreadLocal.withInitial(() -> new int[256]);
        // The lists are independent: sort blocks of parents in parallel (each list is sorted by the sequential algorithm)
        java.util.stream.IntStream.range(0, (n + 1 + 4095) / 4096).parallel().forEach(blk -> {
            int pEnd = Math.min(n + 1, (blk + 1) * 4096);
            for (int p = blk * 4096; p < pEnd; p++) {
                int from = 2 + startF[p], len = startF[p + 1] - startF[p];
                if (len < 2) continue;
                long[] keys = keyScratch.get();
                int[] vals = valScratch.get();
                if (keys.length < len) {
                    keys = new long[Math.max(len, keys.length * 2)];
                    vals = new int[keys.length];
                    keyScratch.set(keys);
                    valScratch.set(vals);
                }
                for (int k = 0; k < len; k++) {
                    int id = idsF[from + k];
                    vals[k] = id;
                    keys[k] = retained[id];
                }
                org.eclipse.mat.dhp.core.graph.MatSort.sortDesc(keys, vals, len);
                System.arraycopy(vals, 0, idsF, from, len);
            }
        });
        // root list (dominator -1)
        putChunk(K_ROOT_CHILDREN, 0, toBytes(ids, 2 + start[0], start[1] - start[0]));
        int[][] holder = {new int[256]};
        writeEdgeChunks(n, K_CHILD, K_CHILD_HUGE, id -> {
            int from = 2 + startF[id + 1], len = startF[id + 2] - startF[id + 1];
            int[] buf = holder[0];
            if (buf.length < len) buf = holder[0] = new int[Math.max(len, buf.length * 2)];
            System.arraycopy(idsF, from, buf, 0, len);
            return buf;
        }, id -> startF[id + 2] - startF[id + 1]);
        saveClassInstances(n);
        if (isSqlite) {
            setJournalPragmas("WAL", "NORMAL");
        } else if (isPostgres) {
            // The tables were UNLOGGED for fast loading; they would be emptied by a server crash, so make them durable
            long tl = System.currentTimeMillis();
            try (Statement stmt = connection.createStatement()) {
                for (String table : new String[]{"dhp_snapshot_info", "dhp_classes", "dhp_class_order", "dhp_class_stats", "dhp_gc_roots", "dhp_chunks"}) {
                    stmt.execute("ALTER TABLE " + table + " SET LOGGED");
                }
                connection.commit();
            }
            log.info("PostgreSQL tables converted to LOGGED in {} ms", System.currentTimeMillis() - tl);
        }
        ChunkFile cf = chunkFile;
        if (cf != null && !cf.isReadOnly()) {
            try {
                cf.finish();
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot finish " + cf + ": " + e.getMessage(), e);
            }
            saveSnapshotInfo("chunkFile", "1");
        }
        // the completion marker comes last: only a fully durable, complete snapshot counts as ingested
        saveSnapshotInfo("numberOfObjects", String.valueOf(n));
        cachedObjectCount = n;
        log.info("Persisted dominator tree: columns {} ms, children {} ms", t1 - t0, System.currentTimeMillis() - t1);
    }

    // ---------------------------------------------------------------------------- reads

    private long readLongAt(int kind, int id, int perChunk) throws SQLException {
        if (id < 0) return 0L;
        byte[] b = chunk(kind, id / perChunk);
        if (b == null) return 0L;
        int i = id % perChunk;
        return i * 8 + 8 <= b.length ? (long) LONG_LE.get(b, i * 8) : 0L;
    }

    private int readIntAt(int kind, int id, int perChunk, int dflt) throws SQLException {
        if (id < 0) return dflt;
        byte[] b = chunk(kind, id / perChunk);
        if (b == null) return dflt;
        int i = id % perChunk;
        return i * 4 + 4 <= b.length ? (int) INT_LE.get(b, i * 4) : dflt;
    }

    @Override
    public int getObjectCount() throws SQLException {
        int c = cachedObjectCount;
        return c >= 0 ? c : loadObjectCount();
    }

    private synchronized int loadObjectCount() throws SQLException {
        if (cachedObjectCount >= 0) return cachedObjectCount;
        String cached = getSnapshotInfo("numberOfObjects");
        if (cached != null && !cached.isBlank()) {
            try {
                return cachedObjectCount = Integer.parseInt(cached.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    @Override
    public long getObjectAddress(int objectId) throws SQLException {
        return readLongAt(K_ADDR, objectId, SC);
    }

    @Override
    public int getObjectIdByAddress(long address) throws SQLException {
        // Object IDs are the ranks of the addresses, so a binary search over the chunk index, then the chunk, finds it
        int n = getObjectCount();
        if (n <= 0) return -1;
        long[] idx = addrIndex;
        if (idx == null) {
            byte[] b = chunk(K_ADDR_INDEX, 0);
            if (b == null) return -1;
            idx = new long[b.length / 8];
            for (int i = 0; i < idx.length; i++) idx[i] = (long) LONG_LE.get(b, i * 8);
            addrIndex = idx;
        }
        int lo = 0, hi = idx.length - 1, c = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idx[mid] <= address) { c = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (c < 0) return -1;
        byte[] b = chunk(K_ADDR, c);
        if (b == null) return -1;
        int l = 0, hh = b.length / 8 - 1;
        while (l <= hh) {
            int mid = (l + hh) >>> 1;
            long v = (long) LONG_LE.get(b, mid * 8);
            if (v < address) l = mid + 1; else if (v > address) hh = mid - 1; else return c * SC + mid;
        }
        return -1;
    }

    @Override
    public long getObjectClassId(int objectId) throws SQLException {
        return readIntAt(K_CLASS, objectId, SC, 0);
    }

    @Override
    public long getObjectUsedSize(int objectId) throws SQLException {
        return readLongAt(K_SIZE, objectId, SC);
    }

    @Override
    public long getTotalHeapSize() throws SQLException {
        String val = getSnapshotInfo("totalHeapSize");
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        long total = 0;
        for (ClassStats s : getClassStats().values()) total += s.totalSize();
        return total;
    }

    @Override
    public long getObjectFilePosition(int objectId) throws SQLException {
        return readLongAt(K_POS, objectId, SC);
    }

    @Override
    public int[] getOutboundReferences(int objectId) throws SQLException {
        return readEdgeList(K_OUT, K_OUT_HUGE, objectId);
    }

    @Override
    public int[] getInboundReferences(int objectId) throws SQLException {
        return readEdgeList(K_IN, K_IN_HUGE, objectId);
    }

    @Override
    public int getDominatorId(int objectId) throws SQLException {
        return readIntAt(K_DOM, objectId, SC, -1);
    }

    @Override
    public long getRetainedSize(int objectId) throws SQLException {
        return readLongAt(K_RET, objectId, SC);
    }

    @Override
    public int getObjectIdByRetainedSize(long retainedSize) throws SQLException {
        int n = getObjectCount();
        for (int c = 0; c * SC < n; c++) {
            byte[] b = chunk(K_RET, c);
            if (b == null) continue;
            for (int i = 0; i * 8 < b.length; i++) {
                if ((long) LONG_LE.get(b, i * 8) == retainedSize) return c * SC + i;
            }
        }
        return -1;
    }

    @Override
    public int[] getImmediateDominatedIds(int objectId) throws SQLException {
        if (objectId < 0) {
            byte[] b = chunk(K_ROOT_CHILDREN, 0);
            return b == null ? new int[0] : toInts(b, 0, b.length / 4);
        }
        return readEdgeList(K_CHILD, K_CHILD_HUGE, objectId);
    }

    @Override
    public boolean isArray(int objectId) throws SQLException {
        if (objectId < 0) return false;
        byte[] b = chunk(K_FLAGS, objectId / SC);
        if (b == null) return false;
        int i = objectId % SC;
        return (i >> 3) < b.length && (b[i >> 3] & (1 << (i & 7))) != 0;
    }

    @Override
    public synchronized boolean[] getAllArrayFlags(int objectCount) throws SQLException {
        boolean[] flags = new boolean[objectCount];
        populateArrayBitField(id -> {
            if (id < objectCount) flags[id] = true;
        });
        return flags;
    }

    @Override
    public synchronized void populateArrayBitField(java.util.function.IntConsumer setBit) throws SQLException {
        scanKind(K_FLAGS, (c, b) -> {
            for (int i = 0; i < b.length * 8; i++) {
                if ((b[i >> 3] & (1 << (i & 7))) != 0) setBit.accept(c * SC + i);
            }
        });
    }

    private interface ChunkVisitor {
        void visit(int chunkNo, byte[] data);
    }

    /** Sequentially visits all chunks of a kind in order, bypassing the point-lookup cache. */
    private void scanKind(int kind, ChunkVisitor visitor) throws SQLException {
        ChunkFile cf = chunkFile;
        if (cf != null) {
            try {
                int n = cf.chunkCount(kind);
                for (int c = 0; c < n; c++) {
                    byte[] b = cf.read(kind, c);
                    if (b != null) {
                        visitor.visit(c, b);
                    }
                }
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot read " + cf + ": " + e.getMessage(), e);
            }
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT chunk_key, data FROM dhp_chunks WHERE chunk_key >= ? AND chunk_key < ? ORDER BY chunk_key")) {
            ps.setLong(1, key(kind, 0));
            ps.setLong(2, key(kind + 1, 0));
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    visitor.visit((int) (rs.getLong(1) & 0xFFFFFFFFL), rs.getBytes(2));
                }
            }
        }
    }

    @Override
    public synchronized long[] loadAllObjectUsedSizes(int objectCount) throws SQLException {
        long[] sizes = new long[objectCount];
        scanKind(K_SIZE, (c, b) -> {
            for (int i = 0; i * 8 < b.length; i++) {
                int id = c * SC + i;
                if (id < objectCount) sizes[id] = (long) LONG_LE.get(b, i * 8);
            }
        });
        return sizes;
    }

    @Override
    public int[] getObjectsByClassId(int classObjId) throws SQLException {
        if (classObjId < 0) return new int[0];
        byte[] b = chunk(K_CLASS_INSTANCES, classObjId);
        return b == null ? new int[0] : toInts(b, 0, b.length / 4);
    }

    @Override
    public List<HeapRecords.GcRootRecord> getGcRoots() throws SQLException {
        List<HeapRecords.GcRootRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_id, object_address, referrer_address, root_type, thread_address, thread_object_id, old_object_id, old_thread_object_id FROM dhp_gc_roots ORDER BY root_seq")) {
            while (rs.next()) {
                list.add(new HeapRecords.GcRootRecord(
                        rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getLong(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8)));
            }
        }
        return list;
    }

    @Override
    public Map<Integer, ClassStats> getClassStats() throws SQLException {
        Map<Integer, ClassStats> map = new HashMap<>();
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT class_id, instance_count, total_size FROM dhp_class_stats")) {
                while (rs.next()) {
                    int classId = rs.getInt(1);
                    int count = rs.getInt(2);
                    long total = rs.getLong(3);
                    map.put(classId, new ClassStats(classId, count, total));
                }
            } catch (SQLException ignored) {}
        }
        return map;
    }

    @Override
    public void close() throws IOException {
        try {
            for (ReadHandle h : readHandles) {
                if (!h.shared && h.conn != null) {
                    try { h.conn.close(); } catch (SQLException ignored) {}
                }
            }
            readHandles.clear();
            ChunkFile cf = chunkFile;
            if (cf != null) {
                cf.close();
            }
            if (connection != null && !connection.isClosed()) {
                flushPuts();
                connection.close();
            }
        } catch (SQLException e) {
            throw new IOException("Error closing JDBC connection", e);
        }
    }
}
