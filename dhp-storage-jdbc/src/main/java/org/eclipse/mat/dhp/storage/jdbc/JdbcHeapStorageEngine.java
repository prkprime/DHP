package org.eclipse.mat.dhp.storage.jdbc;

import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    }

    private void configureSqlite(long memoryBudgetBytes) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            // Set cache size: negative number indicates KiB
            long cacheKiB = Math.max(64 * 1024, memoryBudgetBytes / 1024 / 2);
            stmt.execute("PRAGMA cache_size = -" + cacheKiB + ";");
            stmt.execute("PRAGMA temp_store = MEMORY;");
            stmt.execute("PRAGMA mmap_size = 4294967296;"); // 4GB mmap
            stmt.execute("PRAGMA threads = 4;");
        }
        log.info("Configured SQLite for high performance WAL ingestion with memory cache budget");
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
            "dhp_dominator_tree",
            "dhp_outbound_references",
            "dhp_inbound_references",
            "dhp_gc_roots",
            "dhp_class_stats",
            "dhp_objects",
            "dhp_classes",
            "dhp_snapshot_info"
        };
        try (Statement stmt = connection.createStatement()) {
            for (String table : tables) {
                stmt.execute("DROP TABLE IF EXISTS " + table + (isPostgres ? " CASCADE" : ""));
            }
        }
        connection.commit();
        log.info("Existing DHP tables successfully dropped.");
    }

    @Override
    public void initializeSchema() throws SQLException {
        String unlogged = isPostgres ? "UNLOGGED " : "";
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

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_class_stats (" +
                    "class_id INT PRIMARY KEY, " +
                    "instance_count INT NOT NULL, " +
                    "total_size BIGINT NOT NULL" +
                    ");");

            // Bulk ingestion tables: NO PRIMARY KEYS or secondary indexes upfront
            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_objects (" +
                    "object_id INT NOT NULL, " +
                    "object_address BIGINT NOT NULL, " +
                    "class_id BIGINT NOT NULL, " +
                    "used_size BIGINT NOT NULL, " +
                    "file_position BIGINT NOT NULL, " +
                    "is_array SMALLINT NOT NULL DEFAULT 0" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_outbound_references (" +
                    "from_object_id INT NOT NULL, " +
                    "seq INT NOT NULL, " +
                    "to_object_id INT NOT NULL" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_inbound_references (" +
                    "to_object_id INT NOT NULL, " +
                    "from_object_id INT NOT NULL" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_gc_roots (" +
                    "object_id INT NOT NULL, " +
                    "object_address BIGINT NOT NULL, " +
                    "referrer_address BIGINT, " +
                    "root_type INT NOT NULL, " +
                    "thread_address BIGINT, " +
                    "thread_object_id INT DEFAULT -1" +
                    ");");

            stmt.execute("CREATE " + unlogged + "TABLE IF NOT EXISTS dhp_dominator_tree (" +
                    "object_id INT NOT NULL, " +
                    "dominator_id INT NOT NULL, " +
                    "retained_size BIGINT NOT NULL" +
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
        } catch (SQLException e) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT class_id, super_class_id, class_loader_id, class_name, instance_size, fields_data FROM dhp_classes WHERE class_id = ?")) {
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
                                List.of()
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

    @Override
    public void insertObjectsBatch(List<RawObjectRecord> objects) throws SQLException {
        String sql = "INSERT INTO dhp_objects(object_id, object_address, class_id, used_size, file_position, is_array) VALUES(?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (RawObjectRecord obj : objects) {
                ps.setInt(1, obj.objectId());
                ps.setLong(2, obj.objectAddress());
                ps.setLong(3, obj.classId());
                ps.setLong(4, obj.usedSize());
                ps.setLong(5, obj.filePosition());
                ps.setShort(6, (short) (obj.isArray() ? 1 : 0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public void insertOutboundReferencesBatch(List<ReferenceEdge> edges) throws SQLException {
        String sqlOut = "INSERT INTO dhp_outbound_references(from_object_id, seq, to_object_id) VALUES(?, ?, ?)";
        String sqlIn = "INSERT INTO dhp_inbound_references(to_object_id, from_object_id) VALUES(?, ?)";
        try (PreparedStatement psOut = connection.prepareStatement(sqlOut);
             PreparedStatement psIn = connection.prepareStatement(sqlIn)) {
            for (ReferenceEdge edge : edges) {
                psOut.setInt(1, edge.fromObjectId());
                psOut.setInt(2, edge.seq());
                psOut.setInt(3, edge.toObjectId());
                psOut.addBatch();

                psIn.setInt(1, edge.toObjectId());
                psIn.setInt(2, edge.fromObjectId());
                psIn.addBatch();
            }
            psOut.executeBatch();
            psIn.executeBatch();
        }
        connection.commit();
    }

    @Override
    public void insertGcRootsBatch(List<HeapRecords.GcRootRecord> gcRoots) throws SQLException {
        String sql = "INSERT INTO dhp_gc_roots(object_id, object_address, referrer_address, root_type, thread_address) VALUES(?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapRecords.GcRootRecord root : gcRoots) {
                ps.setInt(1, (int) root.objectAddress()); // mapped ID or temporary
                ps.setLong(2, root.objectAddress());
                ps.setLong(3, root.referrerAddress());
                ps.setInt(4, root.rootType());
                ps.setLong(5, root.threadAddress());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public int runGarbageCollection() throws SQLException {
        int n = getObjectCount();
        if (n == 0) return 0;

        log.info("Starting Database-side Garbage Collection for {} objects...", n);

        // 1. Collect GC root object IDs
        it.unimi.dsi.fastutil.ints.IntOpenHashSet rootSet = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT o.object_id FROM dhp_objects o JOIN dhp_gc_roots r ON o.object_address = r.object_address")) {
            while (rs.next()) {
                rootSet.add(rs.getInt(1));
            }
        }
        int sysId = getObjectIdByAddress(0L);
        if (sysId >= 0) rootSet.add(sysId);

        // 2. Load outbound adjacency for reachability traversal
        int[][] outAdj = loadAllOutboundReferences(n);

        // 3. Fast BFS reachability traversal
        java.util.BitSet reachable = new java.util.BitSet(n);
        it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();

        for (int r : rootSet) {
            if (r >= 0 && r < n && !reachable.get(r)) {
                reachable.set(r);
                queue.enqueue(r);
            }
        }

        while (!queue.isEmpty()) {
            int u = queue.dequeueInt();
            if (u >= 0 && u < outAdj.length) {
                for (int v : outAdj[u]) {
                    if (v >= 0 && v < n && !reachable.get(v)) {
                        reachable.set(v);
                        queue.enqueue(v);
                    }
                }
            }
        }

        int reachableCount = reachable.cardinality();
        int unreachableCount = n - reachableCount;
        log.info("Database Garbage Collection analysis: {} reachable, {} unreachable (out of {} objects)",
                reachableCount, unreachableCount, n);

        if (unreachableCount == 0) {
            saveSnapshotInfo("removedUnreachableObjects", "0");
            saveSnapshotInfo("removedUnreachableBytes", "0");
            return 0;
        }

        // 4. Calculate total unreachable bytes
        long unreachableBytes = 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_id, used_size FROM dhp_objects")) {
            while (rs.next()) {
                int oid = rs.getInt(1);
                if (oid < n && !reachable.get(oid)) {
                    unreachableBytes += rs.getLong(2);
                }
            }
        }

        // 5. Purge unreachable records and re-index contiguous IDs directly in the database
        purgeUnreachableObjectsInDatabase(reachable, n, reachableCount);

        saveSnapshotInfo("numberOfObjects", String.valueOf(reachableCount));
        saveSnapshotInfo("removedUnreachableObjects", String.valueOf(unreachableCount));
        saveSnapshotInfo("removedUnreachableBytes", String.valueOf(unreachableBytes));

        log.info("Database Garbage Collection complete: removed {} unreachable objects ({} bytes). Live objects: {}",
                unreachableCount, unreachableBytes, reachableCount);
        return unreachableCount;
    }

    private void purgeUnreachableObjectsInDatabase(java.util.BitSet reachable, int n, int reachableCount) throws SQLException {
        log.info("Purging {} unreachable records directly inside database tables...", n - reachableCount);
        long t0 = System.currentTimeMillis();

        String unlogged = isPostgres ? "UNLOGGED " : "";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS dhp_gc_map");
            stmt.execute("CREATE " + unlogged + "TABLE dhp_gc_map (old_id INT PRIMARY KEY, new_id INT NOT NULL)");
        }

        String insertMapSql = "INSERT INTO dhp_gc_map(old_id, new_id) VALUES(?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertMapSql)) {
            int newId = 0;
            int batch = 0;
            for (int oldId = 0; oldId < n; oldId++) {
                if (reachable.get(oldId)) {
                    ps.setInt(1, oldId);
                    ps.setInt(2, newId++);
                    ps.addBatch();
                    if (++batch >= 10000) {
                        ps.executeBatch();
                        batch = 0;
                    }
                }
            }
            if (batch > 0) {
                ps.executeBatch();
            }
        }
        connection.commit();

        try (Statement stmt = connection.createStatement()) {
            // Rebuild dhp_objects
            stmt.execute("DROP TABLE IF EXISTS dhp_objects_clean");
            stmt.execute("CREATE " + unlogged + "TABLE dhp_objects_clean AS " +
                    "SELECT m.new_id AS object_id, o.object_address, COALESCE(mc.new_id, o.class_id) AS class_id, o.used_size, o.file_position, o.is_array " +
                    "FROM dhp_objects o " +
                    "JOIN dhp_gc_map m ON o.object_id = m.old_id " +
                    "LEFT JOIN dhp_gc_map mc ON o.class_id = mc.old_id");
            stmt.execute("DROP TABLE dhp_objects" + (isPostgres ? " CASCADE" : ""));
            stmt.execute("ALTER TABLE dhp_objects_clean RENAME TO dhp_objects");

            // Rebuild dhp_outbound_references
            stmt.execute("DROP TABLE IF EXISTS dhp_outbound_clean");
            stmt.execute("CREATE " + unlogged + "TABLE dhp_outbound_clean AS " +
                    "SELECT mf.new_id AS from_object_id, r.seq, mt.new_id AS to_object_id " +
                    "FROM dhp_outbound_references r " +
                    "JOIN dhp_gc_map mf ON r.from_object_id = mf.old_id " +
                    "JOIN dhp_gc_map mt ON r.to_object_id = mt.old_id");
            stmt.execute("DROP TABLE dhp_outbound_references" + (isPostgres ? " CASCADE" : ""));
            stmt.execute("ALTER TABLE dhp_outbound_clean RENAME TO dhp_outbound_references");

            // Rebuild dhp_inbound_references
            stmt.execute("DROP TABLE IF EXISTS dhp_inbound_clean");
            stmt.execute("CREATE " + unlogged + "TABLE dhp_inbound_clean AS " +
                    "SELECT mt.new_id AS to_object_id, mf.new_id AS from_object_id " +
                    "FROM dhp_inbound_references r " +
                    "JOIN dhp_gc_map mt ON r.to_object_id = mt.old_id " +
                    "JOIN dhp_gc_map mf ON r.from_object_id = mf.old_id");
            stmt.execute("DROP TABLE dhp_inbound_references" + (isPostgres ? " CASCADE" : ""));
            stmt.execute("ALTER TABLE dhp_inbound_clean RENAME TO dhp_inbound_references");

            // Rebuild dhp_gc_roots
            stmt.execute("DROP TABLE IF EXISTS dhp_gc_roots_clean");
            stmt.execute("CREATE " + unlogged + "TABLE dhp_gc_roots_clean AS " +
                    "SELECT o.object_id, r.object_address, r.referrer_address, r.root_type, r.thread_address, " +
                    "coalesce((SELECT t.object_id FROM dhp_objects t WHERE t.object_address = r.thread_address), -1) AS thread_object_id " +
                    "FROM dhp_gc_roots r " +
                    "JOIN dhp_objects o ON r.object_address = o.object_address");
            stmt.execute("DROP TABLE dhp_gc_roots" + (isPostgres ? " CASCADE" : ""));
            stmt.execute("ALTER TABLE dhp_gc_roots_clean RENAME TO dhp_gc_roots");

            stmt.execute("DROP TABLE dhp_gc_map");
        }
        connection.commit();
        log.info("Database table purge and re-indexing finished in {} ms.", System.currentTimeMillis() - t0);
    }

    @Override
    public void finishIngestion() throws SQLException {
        log.info("Ingestion completed. Creating primary unique indexes and secondary B-trees in bulk...");
        long start = System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            // 1. Primary/unique indexes built in bulk
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_dhp_objects_pk ON dhp_objects(object_id);");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_dhp_dominator_pk ON dhp_dominator_tree(object_id);");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_dhp_outbound_pk ON dhp_outbound_references(from_object_id, seq);");

            // 2. Secondary lookup indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_objects_address ON dhp_objects(object_address);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_objects_class ON dhp_objects(class_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_objects_arrays ON dhp_objects(object_id) WHERE is_array = 1;");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_outbound_to ON dhp_outbound_references(to_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_inbound_from ON dhp_inbound_references(from_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_inbound_to ON dhp_inbound_references(to_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_gc_roots_obj ON dhp_gc_roots(object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_dom_dominator ON dhp_dominator_tree(dominator_id, retained_size DESC);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_dom_retained ON dhp_dominator_tree(retained_size);");

            // 3. Precompute class stats table for instant snapshot opening
            stmt.execute("DELETE FROM dhp_class_stats;");
            stmt.execute("INSERT INTO dhp_class_stats(class_id, instance_count, total_size) " +
                    "SELECT class_id, count(*), coalesce(sum(used_size), 0) FROM dhp_objects GROUP BY class_id;");

            // 4. Pre-resolve and backfill metadata in dhp_classes
            stmt.execute("UPDATE dhp_classes SET " +
                    "class_obj_id = coalesce((SELECT o.object_id FROM dhp_objects o WHERE o.object_address = dhp_classes.class_id), -1), " +
                    "super_class_obj_id = coalesce((SELECT o.object_id FROM dhp_objects o WHERE o.object_address = dhp_classes.super_class_id), -1), " +
                    "class_loader_obj_id = coalesce((SELECT o.object_id FROM dhp_objects o WHERE o.object_address = dhp_classes.class_loader_id), -1), " +
                    "used_size = coalesce((SELECT o.used_size FROM dhp_objects o WHERE o.object_address = dhp_classes.class_id), 0);");

            // 5. Pre-resolve object_id and thread_object_id in dhp_gc_roots
            stmt.execute("UPDATE dhp_gc_roots SET " +
                    "object_id = coalesce((SELECT o.object_id FROM dhp_objects o WHERE o.object_address = dhp_gc_roots.object_address), -1), " +
                    "thread_object_id = coalesce((SELECT o.object_id FROM dhp_objects o WHERE o.object_address = dhp_gc_roots.thread_address), -1);");

            // 6. Pre-calculate total heap size and object count into dhp_snapshot_info
            long totalHeap = 0L;
            try (ResultSet rs = stmt.executeQuery("SELECT coalesce(sum(total_size), 0) FROM dhp_class_stats")) {
                if (rs.next()) totalHeap = rs.getLong(1);
            }
            saveSnapshotInfo("totalHeapSize", String.valueOf(totalHeap));

            int count = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM dhp_objects")) {
                if (rs.next()) count = rs.getInt(1);
            }
            saveSnapshotInfo("numberOfObjects", String.valueOf(count));

            // 7. Database optimizer statistics
            if (isSqlite) {
                stmt.execute("PRAGMA optimize;");
            } else if (isPostgres) {
                stmt.execute("ANALYZE;");
            }
        }
        connection.commit();
        log.info("Bulk indexing successfully finished in {} ms.", System.currentTimeMillis() - start);
    }

    @Override
    public int getObjectCount() throws SQLException {
        String cached = getSnapshotInfo("numberOfObjects");
        if (cached != null && !cached.isBlank()) {
            try {
                return Integer.parseInt(cached.trim());
            } catch (NumberFormatException ignored) {}
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM dhp_objects")) {
            if (rs.next()) {
                int count = rs.getInt(1);
                saveSnapshotInfo("numberOfObjects", String.valueOf(count));
                return count;
            }
        }
        return 0;
    }

    @Override
    public long getObjectAddress(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_address FROM dhp_objects WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    @Override
    public int getObjectIdByAddress(long address) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM dhp_objects WHERE object_address = ?")) {
            ps.setLong(1, address);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    @Override
    public long getObjectClassId(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT class_id FROM dhp_objects WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    @Override
    public long getObjectUsedSize(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT used_size FROM dhp_objects WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    @Override
    public long getTotalHeapSize() throws SQLException {
        String val = getSnapshotInfo("totalHeapSize");
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT coalesce(sum(used_size), 0) FROM dhp_objects")) {
            if (rs.next()) {
                long total = rs.getLong(1);
                saveSnapshotInfo("totalHeapSize", String.valueOf(total));
                return total;
            }
        }
        return 0L;
    }

    @Override
    public long getObjectFilePosition(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT file_position FROM dhp_objects WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    @Override
    public int[] getOutboundReferences(int objectId) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT to_object_id FROM dhp_outbound_references WHERE from_object_id = ? ORDER BY seq ASC")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt(1));
                }
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public int[] getInboundReferences(int objectId) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT from_object_id FROM dhp_inbound_references WHERE to_object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt(1));
                }
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public List<HeapRecords.GcRootRecord> getGcRoots() throws SQLException {
        List<HeapRecords.GcRootRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            boolean hasExtendedCols = false;
            try (ResultSet rs = stmt.executeQuery("SELECT object_id, object_address, referrer_address, root_type, thread_address, thread_object_id FROM dhp_gc_roots")) {
                hasExtendedCols = true;
                while (rs.next()) {
                    list.add(new HeapRecords.GcRootRecord(
                            rs.getInt(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getInt(4),
                            rs.getLong(5),
                            rs.getInt(6)
                    ));
                }
            } catch (SQLException ignored) {}
            if (!hasExtendedCols) {
                try (ResultSet rs = stmt.executeQuery("SELECT object_address, referrer_address, root_type, thread_address FROM dhp_gc_roots")) {
                    while (rs.next()) {
                        list.add(new HeapRecords.GcRootRecord(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getInt(3),
                                rs.getLong(4)
                        ));
                    }
                }
            }
        }
        return list;
    }

    @Override
    public void saveDominatorTreeBatch(List<DominatorNode> dominators) throws SQLException {
        String sql = "INSERT INTO dhp_dominator_tree(object_id, dominator_id, retained_size) VALUES(?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (DominatorNode node : dominators) {
                ps.setInt(1, node.objectId());
                ps.setInt(2, node.dominatorId());
                ps.setLong(3, node.retainedSize());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public int getDominatorId(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT dominator_id FROM dhp_dominator_tree WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    @Override
    public long getRetainedSize(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT retained_size FROM dhp_dominator_tree WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    @Override
    public int getObjectIdByRetainedSize(long retainedSize) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM dhp_dominator_tree WHERE retained_size = ? LIMIT 1")) {
            ps.setLong(1, retainedSize);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    @Override
    public int[] getImmediateDominatedIds(int objectId) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM dhp_dominator_tree WHERE dominator_id = ? ORDER BY retained_size DESC")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt(1));
                }
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public boolean isArray(int objectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT is_array FROM dhp_objects WHERE object_id = ?")) {
            ps.setInt(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) == 1;
            }
        }
        return false;
    }

    @Override
    public boolean[] getAllArrayFlags(int objectCount) throws SQLException {
        boolean[] flags = new boolean[objectCount];
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_id FROM dhp_objects WHERE is_array = 1")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                if (id >= 0 && id < objectCount) {
                    flags[id] = true;
                }
            }
        }
        return flags;
    }

    @Override
    public void populateArrayBitField(java.util.function.IntConsumer setBit) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_id FROM dhp_objects WHERE is_array = 1")) {
            while (rs.next()) {
                setBit.accept(rs.getInt(1));
            }
        }
    }

    @Override
    public int[][] loadAllOutboundReferences(int objectCount) throws SQLException {
        int[][] outAdj = new int[objectCount][];
        int[] counts = new int[objectCount];
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT from_object_id, count(*) FROM dhp_outbound_references GROUP BY from_object_id")) {
            while (rs.next()) {
                int from = rs.getInt(1);
                if (from >= 0 && from < objectCount) {
                    counts[from] = rs.getInt(2);
                }
            }
        }
        for (int i = 0; i < objectCount; i++) {
            outAdj[i] = new int[counts[i]];
        }
        int[] pos = new int[objectCount];
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT from_object_id, to_object_id FROM dhp_outbound_references ORDER BY from_object_id, seq")) {
            while (rs.next()) {
                int from = rs.getInt(1);
                int to = rs.getInt(2);
                if (from >= 0 && from < objectCount && pos[from] < outAdj[from].length) {
                    outAdj[from][pos[from]++] = to;
                }
            }
        }
        return outAdj;
    }

    @Override
    public long[] loadAllObjectUsedSizes(int objectCount) throws SQLException {
        long[] sizes = new long[objectCount];
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_id, used_size FROM dhp_objects")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                if (id >= 0 && id < objectCount) {
                    sizes[id] = rs.getLong(2);
                }
            }
        }
        return sizes;
    }

    @Override
    public int[] getObjectsByClassId(int classObjId) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM dhp_objects WHERE class_id = ? ORDER BY object_id")) {
            ps.setInt(1, classObjId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt(1));
                }
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public Map<Integer, ClassStats> getClassStats() throws SQLException {
        Map<Integer, ClassStats> map = new HashMap<>();
        try (Statement stmt = connection.createStatement()) {
            boolean hasStatsTable = false;
            try (ResultSet rs = stmt.executeQuery("SELECT class_id, instance_count, total_size FROM dhp_class_stats")) {
                hasStatsTable = true;
                while (rs.next()) {
                    int classId = rs.getInt(1);
                    int count = rs.getInt(2);
                    long total = rs.getLong(3);
                    map.put(classId, new ClassStats(classId, count, total));
                }
            } catch (SQLException ignored) {}
            if (!hasStatsTable || map.isEmpty()) {
                try (ResultSet rs = stmt.executeQuery("SELECT class_id, count(*), coalesce(sum(used_size), 0) FROM dhp_objects GROUP BY class_id")) {
                    while (rs.next()) {
                        int classId = rs.getInt(1);
                        int count = rs.getInt(2);
                        long total = rs.getLong(3);
                        map.put(classId, new ClassStats(classId, count, total));
                    }
                }
            }
        }
        return map;
    }

    @Override
    public void close() throws IOException {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new IOException("Error closing JDBC connection", e);
        }
    }
}
