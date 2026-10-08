package org.eclipse.mat.dhp.tools;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

/**
 * Generates an authentic, highly complex Java HPROF heap dump
 * sized strictly between 2.0 GB and 3.0 GB (target: ~2.35 GB - 2.50 GB).
 *
 * Rich topologies included:
 *  - Multi-level class hierarchy with all 8 Java primitive types
 *  - Diamond DAG topologies (testing dominator convergence)
 *  - Circular references & loops
 *  - Binary search trees and linked lists
 *  - Collections (HashMap, TreeMap, ArrayList, HashSet)
 *  - Active thread local GC roots
 *  - Varied root dominator retained sizes (650MB, 550MB, 450MB, 350MB, 250MB)
 */
public class ComplexHeapDumpGenerator {

    // Retained roots preventing GC
    public static final List<Object> GC_ROOTS = new ArrayList<>();

    // 1. Multi-level Inheritance Models
    public static class BaseEntity {
        protected long id;
        protected long timestamp;
        protected boolean active;
        protected byte flags;
        protected short priority;
        protected int status;
        protected float weight;
        protected double score;

        public BaseEntity(long id) {
            this.id = id;
            this.timestamp = System.currentTimeMillis();
            this.active = (id % 2 == 0);
            this.flags = (byte) (id & 0x7F);
            this.priority = (short) (id % 100);
            this.status = (int) (id % 10);
            this.weight = (float) (id * 1.5);
            this.score = id * 2.71828;
        }
    }

    public static class AbstractUser extends BaseEntity {
        protected String username;
        protected String email;
        protected char tier;
        protected int accessLevel;

        public AbstractUser(long id, String username) {
            super(id);
            this.username = username;
            this.email = username + "@example.com";
            this.tier = (char) ('A' + (id % 5));
            this.accessLevel = (int) (id % 4);
        }
    }

    public static class CustomerAccount extends AbstractUser {
        protected double balance;
        protected long creditLimit;
        protected String[] preferences;
        protected Map<String, Object> attributes = new HashMap<>();

        public CustomerAccount(long id, String username, double balance) {
            super(id, username);
            this.balance = balance;
            this.creditLimit = (long) (balance * 2);
            this.preferences = new String[]{"pref_notifications", "pref_dark_mode", "pref_auto_renew"};
            this.attributes.put("region", "US-WEST");
            this.attributes.put("verified", Boolean.TRUE);
        }
    }

    public static class EnterpriseCustomer extends CustomerAccount {
        protected String corporateName;
        protected long orgId;
        protected int[] departmentCodes;
        protected byte[] payloadBlob;

        public EnterpriseCustomer(long id, String username, String corporateName, int blobSize) {
            super(id, username, 10000.0 + id);
            this.corporateName = corporateName;
            this.orgId = 100000L + id;
            this.departmentCodes = new int[]{101, 202, 303, 404};
            this.payloadBlob = new byte[blobSize];
        }
    }

    // 2. Diamond DAG Nodes (Dominator convergence testing)
    public static class DiamondNode {
        public String name;
        public DiamondNode left;
        public DiamondNode right;
        public DiamondNode bottom;
        public byte[] payload;

        public DiamondNode(String name, int payloadSize) {
            this.name = name;
            if (payloadSize > 0) {
                this.payload = new byte[payloadSize];
            }
        }
    }

    // 3. Circular Reference Loop
    public static class CycleNode {
        public int id;
        public CycleNode next;
        public CycleNode prev;
        public String tag;
        public byte[] data;

        public CycleNode(int id, int dataSize) {
            this.id = id;
            this.tag = "cycle_node_" + id;
            if (dataSize > 0) {
                this.data = new byte[dataSize];
            }
        }
    }

    // 4. Binary Tree Node
    public static class TreeNode {
        public int value;
        public String label;
        public TreeNode left;
        public TreeNode right;

        public TreeNode(int value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    // 5. Large Dominator Subsystems
    public static class CacheSubsystem {
        public String name = "GlobalObjectCacheSubsystem";
        public List<byte[]> chunks = new ArrayList<>();
        public Map<String, EnterpriseCustomer> customerCache = new HashMap<>();

        public CacheSubsystem(long totalBytes) {
            int chunkSize = 10 * 1024 * 1024; // 10MB chunks
            long allocated = 0;
            while (allocated < totalBytes) {
                int s = (int) Math.min(chunkSize, totalBytes - allocated);
                chunks.add(new byte[s]);
                allocated += s;
            }
        }
    }

    public static class ReportBufferPool {
        public String name = "AnalyticsReportBufferPool";
        public List<int[]> matrices = new ArrayList<>();
        public TreeMap<Integer, String> reportIndex = new TreeMap<>();

        public ReportBufferPool(long totalBytes) {
            int intsPerChunk = 2 * 1024 * 1024; // 8MB per int array
            long allocated = 0;
            int counter = 0;
            while (allocated < totalBytes) {
                int count = (int) Math.min(intsPerChunk, (totalBytes - allocated) / 4);
                if (count <= 0) break;
                matrices.add(new int[count]);
                allocated += (long) count * 4;
                reportIndex.put(counter++, "report_section_" + counter);
            }
        }
    }

    public static class ImageProcessingCache {
        public String name = "RenderedImageProcessingCache";
        public List<byte[]> buffers = new ArrayList<>();

        public ImageProcessingCache(long totalBytes) {
            int chunkSize = 8 * 1024 * 1024;
            long allocated = 0;
            while (allocated < totalBytes) {
                int s = (int) Math.min(chunkSize, totalBytes - allocated);
                buffers.add(new byte[s]);
                allocated += s;
            }
        }
    }

    public static class TransactionJournal {
        public String name = "WalTransactionJournal";
        public List<byte[]> segments = new ArrayList<>();

        public TransactionJournal(long totalBytes) {
            int chunkSize = 5 * 1024 * 1024;
            long allocated = 0;
            while (allocated < totalBytes) {
                int s = (int) Math.min(chunkSize, totalBytes - allocated);
                segments.add(new byte[s]);
                allocated += s;
            }
        }
    }

    public static class SessionStore {
        public String name = "UserSessionStore";
        public List<EnterpriseCustomer> customers = new ArrayList<>();
        public List<DiamondNode> diamonds = new ArrayList<>();
        public List<CycleNode> cycles = new ArrayList<>();
        public TreeNode rootTree;
        public List<byte[]> sessionBlobs = new ArrayList<>();

        public SessionStore(long totalBytes) {
            int chunkSize = 4 * 1024 * 1024;
            long allocated = 0;
            while (allocated < totalBytes) {
                int s = (int) Math.min(chunkSize, totalBytes - allocated);
                sessionBlobs.add(new byte[s]);
                allocated += s;
            }
        }
    }

    private static TreeNode buildTree(int depth, int val) {
        if (depth <= 0) return null;
        TreeNode node = new TreeNode(val, "tree_node_" + val);
        node.left = buildTree(depth - 1, val * 2 + 1);
        node.right = buildTree(depth - 1, val * 2 + 2);
        return node;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ComplexHeapDumpGenerator <output-path.hprof>");
            System.exit(1);
        }

        String outputPath = args[0];
        File outputFile = new File(outputPath);
        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }
        if (outputFile.exists()) {
            outputFile.delete();
        }

        System.out.println("================================================================================");
        System.out.println("Complex HPROF Heap Dump Generator");
        System.out.println("Target Destination: " + outputFile.getAbsolutePath());
        System.out.println("Target Size Bounds: 2.0 GB (min) to 3.0 GB (max)");
        System.out.println("================================================================================");

        // 1. Build Subsystems with carefully calibrated allocations:
        // Total byte payload = 650M + 550M + 450M + 350M + 250M = 2,250 MB (~2.20 GiB / 2.36 GB file)
        System.out.println("[Step 1/5] Building CacheSubsystem (~650 MB)...");
        CacheSubsystem cacheSubsystem = new CacheSubsystem(650L * 1024 * 1024);
        for (int i = 0; i < 500; i++) {
            cacheSubsystem.customerCache.put("cust_" + i, new EnterpriseCustomer(i, "user_" + i, "Corp_" + i, 2048));
        }
        GC_ROOTS.add(cacheSubsystem);

        System.out.println("[Step 2/5] Building ReportBufferPool (~550 MB)...");
        ReportBufferPool reportPool = new ReportBufferPool(550L * 1024 * 1024);
        GC_ROOTS.add(reportPool);

        System.out.println("[Step 3/5] Building ImageProcessingCache (~450 MB)...");
        ImageProcessingCache imageCache = new ImageProcessingCache(450L * 1024 * 1024);
        GC_ROOTS.add(imageCache);

        System.out.println("[Step 4/5] Building TransactionJournal (~350 MB)...");
        TransactionJournal journal = new TransactionJournal(350L * 1024 * 1024);
        GC_ROOTS.add(journal);

        System.out.println("[Step 5/5] Building SessionStore & Complex Topologies (~250 MB + graphs)...");
        SessionStore sessionStore = new SessionStore(250L * 1024 * 1024);

        // Add 5,000 Customer Accounts with deep inheritance
        for (int i = 0; i < 5000; i++) {
            sessionStore.customers.add(new EnterpriseCustomer(i + 1000, "enterprise_user_" + i, "AcmeCorp_" + i, 1024));
        }

        // Add Diamond DAGs (A -> B, A -> C; B -> D, C -> D => idom(D) = A)
        for (int i = 0; i < 100; i++) {
            DiamondNode top = new DiamondNode("diamond_top_" + i, 64);
            DiamondNode left = new DiamondNode("diamond_left_" + i, 64);
            DiamondNode right = new DiamondNode("diamond_right_" + i, 64);
            DiamondNode bottom = new DiamondNode("diamond_bottom_" + i, 256);
            top.left = left;
            top.right = right;
            left.bottom = bottom;
            right.bottom = bottom;
            sessionStore.diamonds.add(top);
        }

        // Add Circular reference loops
        for (int i = 0; i < 50; i++) {
            CycleNode c1 = new CycleNode(i * 3 + 1, 128);
            CycleNode c2 = new CycleNode(i * 3 + 2, 128);
            CycleNode c3 = new CycleNode(i * 3 + 3, 128);
            c1.next = c2;
            c2.next = c3;
            c3.next = c1;
            c1.prev = c3;
            c3.prev = c2;
            c2.prev = c1;
            sessionStore.cycles.add(c1);
        }

        // Add balanced binary tree (depth 14 = 16,383 nodes)
        sessionStore.rootTree = buildTree(14, 1);
        GC_ROOTS.add(sessionStore);

        // Start background worker threads holding GC roots during dump
        CountDownLatch latch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        Thread worker1 = new Thread(() -> {
            List<String> threadLocals = new ArrayList<>();
            for (int i = 0; i < 10000; i++) threadLocals.add("worker1_local_string_" + i);
            latch.countDown();
            try {
                releaseLatch.await();
            } catch (InterruptedException ignored) {}
            if (threadLocals.isEmpty()) System.out.println();
        }, "WorkerThread-Analytics-1");

        Thread worker2 = new Thread(() -> {
            Map<Integer, Object> localMap = new HashMap<>();
            for (int i = 0; i < 5000; i++) localMap.put(i, new BaseEntity(i + 50000));
            latch.countDown();
            try {
                releaseLatch.await();
            } catch (InterruptedException ignored) {}
            if (localMap.isEmpty()) System.out.println();
        }, "WorkerThread-Sync-2");

        worker1.start();
        worker2.start();
        latch.await();

        System.out.println("All topologies allocated. Triggering HPROF heap dump...");
        long start = System.currentTimeMillis();

        HotSpotDiagnosticMXBean mxBean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        mxBean.dumpHeap(outputFile.getAbsolutePath(), false);

        releaseLatch.countDown();
        worker1.join();
        worker2.join();

        long duration = System.currentTimeMillis() - start;
        long bytes = outputFile.length();
        double gigabytes = (double) bytes / (1024.0 * 1024.0 * 1024.0);

        System.out.println("================================================================================");
        System.out.println(String.format("Dump completed in %.2f seconds!", duration / 1000.0));
        System.out.println(String.format("Dump File: %s", outputFile.getAbsolutePath()));
        System.out.println(String.format("Dump Size: %d bytes (%.3f GB)", bytes, gigabytes));
        System.out.println("================================================================================");

        // Assert strictly between 2.0 GB and 3.0 GB
        long minBytes = 2L * 1024L * 1024L * 1024L;
        long maxBytes = 3L * 1024L * 1024L * 1024L;

        if (bytes < minBytes) {
            throw new IllegalStateException(String.format("Generated heap dump is under 2 GB! Size = %.3f GB", gigabytes));
        }
        if (bytes > maxBytes) {
            throw new IllegalStateException(String.format("Generated heap dump is over 3 GB! Size = %.3f GB", gigabytes));
        }

        System.out.println("SUCCESS: Heap dump size is strictly within the 2.0 GB - 3.0 GB range!");
    }
}
