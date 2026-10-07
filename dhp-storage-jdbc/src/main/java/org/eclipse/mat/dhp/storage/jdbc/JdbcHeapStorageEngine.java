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
import java.util.List;

/**
 * High-performance JDBC storage engine supporting SQLite and PostgreSQL
 * with tuned batching, PRAGMAs, and indexing.
 */
public class JdbcHeapStorageEngine implements HeapStorageEngine {
    private static final Logger log = LoggerFactory.getLogger(JdbcHeapStorageEngine.class);

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
            stmt.execute("PRAGMA mmap_size = 2147483648;"); // 2GB mmap
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
                    "instance_size INT NOT NULL" +
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
                    "thread_address BIGINT" +
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
                ? "INSERT INTO dhp_classes(class_id, super_class_id, class_loader_id, class_name, instance_size) VALUES(?, ?, ?, ?, ?) ON CONFLICT (class_id) DO NOTHING"
                : "INSERT OR REPLACE INTO dhp_classes(class_id, super_class_id, class_loader_id, class_name, instance_size) VALUES(?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (HeapRecords.ClassRecord cls : classes) {
                ps.setLong(1, cls.classId());
                ps.setLong(2, cls.superClassId());
                ps.setLong(3, cls.classLoaderId());
                ps.setString(4, cls.name());
                ps.setInt(5, cls.instanceSize());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        connection.commit();
    }

    @Override
    public List<HeapRecords.ClassRecord> getAllClasses() throws SQLException {
        List<HeapRecords.ClassRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT class_id, super_class_id, class_loader_id, class_name, instance_size FROM dhp_classes")) {
            while (rs.next()) {
                list.add(new HeapRecords.ClassRecord(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getInt(5),
                        List.of(),
                        List.of()
                ));
            }
        }
        return list;
    }

    @Override
    public HeapRecords.ClassRecord getClassById(long classId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT class_id, super_class_id, class_loader_id, class_name, instance_size FROM dhp_classes WHERE class_id = ?")) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HeapRecords.ClassRecord(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getInt(5),
                            List.of(),
                            List.of()
                    );
                }
            }
        }
        return null;
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_outbound_to ON dhp_outbound_references(to_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_inbound_from ON dhp_inbound_references(from_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_inbound_to ON dhp_inbound_references(to_object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_gc_roots_obj ON dhp_gc_roots(object_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dhp_dom_dominator ON dhp_dominator_tree(dominator_id);");

            // 3. Database optimizer statistics
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
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM dhp_objects")) {
            if (rs.next()) {
                return rs.getInt(1);
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
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT object_address, referrer_address, root_type, thread_address FROM dhp_gc_roots")) {
            while (rs.next()) {
                list.add(new HeapRecords.GcRootRecord(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        rs.getLong(4)
                ));
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
    public int[] getImmediateDominatedIds(int objectId) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT object_id FROM dhp_dominator_tree WHERE dominator_id = ?")) {
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
