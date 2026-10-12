import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a varied, realistic live object graph inside a real JVM and asks HotSpot to write the heap dump, so the
 * resulting HPROF has genuine segments, java.lang.Class objects, thread roots and class loaders (unlike the
 * streaming synthetic generator).
 *
 * Usage (no package on purpose, so the single-file source launcher works):
 *   java -Xmx13g -XX:+UseParallelGC tools/dump-generator/RealHeapDumpGenerator.java out.hprof [targetLiveGB] [seed] [payloadScaleBytes]
 */
public class RealHeapDumpGenerator {

    static final class Address { String street; String city; String zip; Address(String s, String c, String z) { street = s; city = c; zip = z; } }
    static final class LineItem { final String sku; final int qty; final BigDecimal price; Order order; LineItem(String s, int q, BigDecimal p) { sku = s; qty = q; price = p; } }
    static final class Order { final long id; final List<LineItem> items = new ArrayList<>(); Customer customer; Map<String, String> attributes; Order(long id) { this.id = id; } }
    static final class Customer {
        final long id; final String name; final Address address; final List<Order> orders = new ArrayList<>();
        Optional<String> nickname; char[] notes; byte[] avatar; Customer referrer; Customer(long id, String name, Address a) { this.id = id; this.name = name; this.address = a; }
    }
    static final class Session { final String token; final Customer customer; final Map<String, Object> state = new HashMap<>(); long lastSeen; Session(String t, Customer c) { token = t; customer = c; } }
    static final class CacheEntry { final String key; byte[] payload; WeakReference<Object> weak; CacheEntry next; CacheEntry(String k, byte[] p) { key = k; payload = p; } }
    static final class Node { Node left, right; Object value; int depth; }

    static Node buildTree(Random r, int depth) {
        Node n = new Node();
        n.depth = depth;
        n.value = Integer.valueOf(r.nextInt());
        if (depth > 0) { n.left = buildTree(r, depth - 1); n.right = r.nextInt(4) == 0 ? null : buildTree(r, depth - 1); }
        return n;
    }

    // Anchors that stay reachable through static fields / threads
    static final Map<Long, Customer> CUSTOMERS = new ConcurrentHashMap<>();
    static final List<Session> SESSIONS = new ArrayList<>();
    static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>();
    static final TreeMap<String, Customer> BY_NAME = new TreeMap<>();
    static final List<Node> TREES = new ArrayList<>();
    static final List<Object> LOADERS = new ArrayList<>();
    static final ArrayDeque<LinkedList<String>> QUEUES = new ArrayDeque<>();

    public static void main(String[] args) throws Exception {
        String out = args[0];
        double targetGb = args.length > 1 ? Double.parseDouble(args[1]) : 10.5;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;
        // Bytes of bulk payload per customer (0 = none): raises bytes per object, lowering the object count of a dump of a given size
        final int payloadScale = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        long target = (long) (targetGb * 1024L * 1024L * 1024L);
        Random r = new Random(seed);
        Runtime rt = Runtime.getRuntime();

        // A few extra class loaders holding classes and a couple of background threads, like a real app server
        for (int i = 0; i < 20; i++) {
            URLClassLoader cl = new URLClassLoader(new URL[0], RealHeapDumpGenerator.class.getClassLoader());
            LOADERS.add(cl);
            LOADERS.add(Class.forName("java.util.concurrent.ConcurrentSkipListMap", true, cl));
        }
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> { try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) {} }, "worker-" + t);
            ThreadLocal<byte[]> tl = new ThreadLocal<>();
            th.setDaemon(true);
            th.start();
            LOADERS.add(th);
            LOADERS.add(tl);
        }

        long id = 0;
        long lastReport = System.currentTimeMillis();
        while (true) {
            for (int b = 0; b < 20_000; b++, id++) {
                Address a = new Address("Street " + r.nextInt(100_000), "City" + r.nextInt(5000), String.format("%05d", r.nextInt(99999)));
                Customer c = new Customer(id, "customer-" + id + "-" + Long.toHexString(r.nextLong()), a);
                if (r.nextInt(3) == 0) c.nickname = Optional.of("nick" + r.nextInt(1_000_000));
                if (r.nextInt(8) == 0) { c.notes = new char[40 + r.nextInt(400)]; java.util.Arrays.fill(c.notes, 'n'); }
                if (id > 10 && r.nextInt(5) == 0) c.referrer = CUSTOMERS.get((long) r.nextInt((int) id));
                if (payloadScale > 0) {
                    // Bulk payload (images, serialized blobs, buffers): mostly small, occasionally large
                    c.avatar = new byte[512 + (r.nextInt(10) == 0 ? r.nextInt(payloadScale * 20) : r.nextInt(payloadScale))];
                }
                int orders = r.nextInt(6);
                for (int o = 0; o < orders; o++) {
                    Order order = new Order(id * 10 + o);
                    order.customer = c;
                    int items = 1 + r.nextInt(8);
                    for (int i = 0; i < items; i++) {
                        LineItem li = new LineItem("SKU-" + r.nextInt(50_000), 1 + r.nextInt(9), BigDecimal.valueOf(r.nextInt(100_000), 2));
                        li.order = order;
                        order.items.add(li);
                    }
                    if (r.nextInt(4) == 0) {
                        order.attributes = new HashMap<>();
                        for (int k = 0; k < 1 + r.nextInt(5); k++) order.attributes.put("k" + k, "v" + r.nextInt(10_000));
                    }
                    c.orders.add(order);
                }
                CUSTOMERS.put(id, c);
                if (id % 7 == 0) BY_NAME.put(c.name, c);
                if (id % 25 == 0) {
                    Session s = new Session(Long.toHexString(r.nextLong()) + Long.toHexString(r.nextLong()), c);
                    s.state.put("cart", new ArrayList<>(List.of(1, 2, 3)));
                    s.state.put("locale", "en-" + r.nextInt(50));
                    SESSIONS.add(s);
                }
                if (id % 20 == 0) {
                    // cache payloads are the bulk of the bytes, like real caches / buffers
                    byte[] payload = new byte[256 + r.nextInt(r.nextInt(50) == 0 ? 256 * 1024 : 8 * 1024)];
                    r.nextBytes(payload);
                    CacheEntry e = new CacheEntry("key-" + id, payload);
                    e.weak = new WeakReference<>(c.address);
                    CacheEntry prev = CACHE.put(e.key, e);
                    if (prev != null) e.next = prev;
                }
                if (id % 50_000 == 0) TREES.add(buildTree(r, 12));
                if (id % 5_000 == 0) {
                    LinkedList<String> q = new LinkedList<>();
                    for (int k = 0; k < 50; k++) q.add("msg-" + r.nextInt());
                    QUEUES.add(q);
                }
            }
            long used = rt.totalMemory() - rt.freeMemory();
            long now = System.currentTimeMillis();
            if (now - lastReport > 5000) {
                System.out.printf("customers=%,d used=%.2f GB / target %.2f GB%n", id, used / 1073741824.0, targetGb);
                lastReport = now;
            }
            if (used >= target) {
                System.gc();
                used = rt.totalMemory() - rt.freeMemory();
                if (used >= target * 0.97) break;
            }
        }

        System.out.printf("Built %,d customers; live heap about %.2f GB. Writing heap dump to %s ...%n",
                id, (rt.totalMemory() - rt.freeMemory()) / 1073741824.0, out);
        long t0 = System.currentTimeMillis();
        HotSpotDiagnosticMXBean mx = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        mx.dumpHeap(out, true);
        System.out.printf("Dump written in %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
        // keep anchors reachable until after the dump
        System.out.println(CUSTOMERS.size() + SESSIONS.size() + CACHE.size() + BY_NAME.size() + TREES.size() + LOADERS.size() + QUEUES.size());
    }
}
