import com.sun.management.HotSpotDiagnosticMXBean;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Builds a deliberately complex object graph inside a real JVM and lets HotSpot write the heap dump, to exercise
 * everything the Eclipse MAT UI shows (histogram, dominator tree, object inspector, class hierarchy, statics, OQL ...).
 *
 * <p>About 400 classes are generated and compiled at run time: 40 inheritance families of 8 levels with interfaces,
 * instance fields and static fields of every type, enums, records, inner/anonymous classes, a 120-field class. They are
 * loaded into several sibling class loaders (so class names repeat across loaders) and instantiated in bulk next to JDK
 * collections, strings (Latin1 and UTF16), multi-dimensional arrays, weak/soft/phantom references, proxies, lambdas,
 * threads with deep stacks and locals, monitors, cycles, shared structures, duplicates and unreachable garbage.</p>
 *
 * Usage (no package so that the single-file source launcher works):
 *   java -Xmx6g -XX:+UseParallelGC tools/dump-generator/ComplexJvmHeapDumpGenerator.java out.hprof [liveGB] [seed] [live|all] [payloadBytes]
 * payloadBytes > 0 adds bulk byte[] buffers (up to that size) to the batches, lowering the object count per GB like real dumps.
 * With "all" (default) unreachable objects are included in the dump (no GC before dumping).
 */
public class ComplexJvmHeapDumpGenerator {

    static final String[] PRIMS = {"boolean", "byte", "char", "short", "int", "long", "float", "double"};
    static final String[] FIELD_TYPES = {"boolean", "byte", "char", "short", "int", "long", "float", "double",
            "String", "Object", "int[]", "java.util.List<Object>", "long[][]", "String[]"};
    static final int FAMILIES = 40;
    static final int LEVELS = 8;
    static final int ENUMS = 12;

    // ----------------------------------------------------------------------------------------- source generation

    static String valueFor(String type, int seedVal) {
        return switch (type) {
            case "boolean" -> (seedVal % 2 == 0) ? "true" : "false";
            case "byte" -> "(byte) " + (seedVal % 100);
            case "char" -> "(char) " + (65 + seedVal % 26);
            case "short" -> "(short) " + (seedVal * 7 % 3000);
            case "int" -> String.valueOf(seedVal * 1_000_003);
            case "long" -> (seedVal * 99_999_989L) + "L";
            case "float" -> (seedVal * 1.25f) + "f";
            case "double" -> (seedVal * 3.14159) + "d";
            case "String" -> "\"str-" + seedVal + (seedVal % 5 == 0 ? "-\\u00e9\\u4e2d" : "") + "\"";
            default -> "null";
        };
    }

    static String randomInit(String type, String r) {
        return switch (type) {
            case "boolean" -> r + ".nextBoolean()";
            case "byte" -> "(byte) " + r + ".nextInt()";
            case "char" -> "(char) (32 + " + r + ".nextInt(90))";
            case "short" -> "(short) " + r + ".nextInt()";
            case "int" -> r + ".nextInt()";
            case "long" -> r + ".nextLong()";
            case "float" -> r + ".nextFloat()";
            case "double" -> r + ".nextDouble()";
            case "String" -> "\"s\" + " + r + ".nextInt(5000)";
            case "Object" -> "(" + r + ".nextInt(4) == 0 ? (Object) Integer.valueOf(" + r + ".nextInt(100)) : (Object) (\"o\" + " + r + ".nextInt(100)))";
            case "int[]" -> "new int[" + r + ".nextInt(6)]";
            case "java.util.List<Object>" -> "new java.util.ArrayList<Object>(java.util.List.of(" + r + ".nextInt(9), \"e\" + " + r + ".nextInt(9)))";
            case "long[][]" -> "new long[" + r + ".nextInt(3) + 1][" + r + ".nextInt(3) + 1]";
            case "String[]" -> "new String[]{\"a\" + " + r + ".nextInt(50), null, \"b\"}";
            default -> "null";
        };
    }

    static void generateSources(Path dir) throws IOException {
        Path pkg = dir.resolve("gen");
        Files.createDirectories(pkg);
        StringBuilder sb = new StringBuilder();
        // Base class and interfaces
        Files.writeString(pkg.resolve("Base.java"), """
                package gen;
                public abstract class Base {
                    public static int COUNT;
                    public static final String KIND = "base";
                    public int id;
                    public Object link;
                    public String tag;
                    protected Base(java.util.Random r) { id = COUNT++; tag = "t" + r.nextInt(1000); }
                }
                """);
        for (int i = 0; i < 10; i++) {
            Files.writeString(pkg.resolve("Marker" + i + ".java"),
                    "package gen;\npublic interface Marker" + i + " { int M" + i + " = " + (i * 11) + "; default int mark" + i + "() { return " + i + "; } }\n");
        }
        for (int f = 0; f < FAMILIES; f++) {
            for (int l = 0; l < LEVELS; l++) {
                String name = "F" + f + "L" + l;
                sb.setLength(0);
                sb.append("package gen;\npublic class ").append(name).append(" extends ")
                        .append(l == 0 ? "Base" : "F" + f + "L" + (l - 1))
                        .append(" implements Marker").append((f + l) % 10).append(" {\n");
                List<String[]> fields = new ArrayList<>();
                for (int k = 0; k < 4; k++) {
                    String type = FIELD_TYPES[(f * 31 + l * 7 + k * 3) % FIELD_TYPES.length];
                    fields.add(new String[]{type, "f" + l + "_" + k});
                    sb.append("    ").append(k % 3 == 0 ? "public " : k % 3 == 1 ? "protected " : "private ").append(type).append(' ').append("f" + l + "_" + k).append(";\n");
                }
                // statics of every primitive type plus references
                for (int t = 0; t < PRIMS.length; t++) {
                    sb.append("    public static ").append(PRIMS[t]).append(" S").append(l).append('_').append(PRIMS[t]).append(" = ")
                            .append(valueFor(PRIMS[t], f * 10 + l + t)).append(";\n");
                }
                sb.append("    public static final String SS").append(l).append(" = ").append(valueFor("String", f * 13 + l)).append(";\n");
                sb.append("    public static long[] SA").append(l).append(" = new long[]{").append(f).append("L, ").append(l).append("L, 3L};\n");
                sb.append("    public static Object SR").append(l).append(";\n");
                sb.append("    public static int COUNTER").append(l).append(";\n");
                sb.append("    public ").append(name).append("(java.util.Random r) {\n        super(r);\n");
                for (String[] fd : fields) {
                    sb.append("        this.").append(fd[1]).append(" = ").append(randomInit(fd[0], "r")).append(";\n");
                }
                sb.append("        COUNTER").append(l).append("++;\n    }\n");
                sb.append("    public int level() { return ").append(l).append("; }\n}\n");
                Files.writeString(pkg.resolve(name + ".java"), sb.toString());
            }
        }
        for (int e = 0; e < ENUMS; e++) {
            Files.writeString(pkg.resolve("En" + e + ".java"), "package gen;\npublic enum En" + e + " {\n"
                    + "    A(" + e + ", \"a" + e + "\", 1.5), B(" + (e + 1) + ", \"b" + e + "\", 2.5), C(" + (e + 2) + ", \"c" + e + "\", 3.5), D(" + (e + 3) + ", null, 0.0);\n"
                    + "    public final int x; public final String s; public final double d; public static int USES;\n"
                    + "    En" + e + "(int x, String s, double d) { this.x = x; this.s = s; this.d = d; }\n}\n");
            Files.writeString(pkg.resolve("Rec" + e + ".java"), "package gen;\npublic record Rec" + e + "(int a, String b, long[] c, Object d, boolean e, double f) {}\n");
            Files.writeString(pkg.resolve("Outer" + e + ".java"), "package gen;\npublic class Outer" + e + " {\n"
                    + "    public int base = " + e + ";\n    public Object payload;\n"
                    + "    public class Inner { public int v = base + 1; public Object ref = payload; public int outer() { return base; } }\n"
                    + "    public static class Nested { public long n = " + e + "L; public String name = \"nested" + e + "\"; }\n"
                    + "    public Object anon() { final int cap = base; return new Object() { public int captured = cap; public String toString() { return \"anon\" + captured; } }; }\n"
                    + "    public Runnable lambda(String s, int k) { return () -> { String t = s + k; if (t.length() > 100000) System.out.println(t); }; }\n"
                    + "    public Inner makeInner() { return new Inner(); }\n}\n");
        }
        // Wide class with 120 mixed fields
        sb.setLength(0);
        sb.append("package gen;\npublic class Wide extends F0L7 {\n");
        for (int i = 0; i < 120; i++) {
            String t = FIELD_TYPES[i % FIELD_TYPES.length];
            sb.append("    public ").append(t).append(" w").append(i).append(";\n");
        }
        sb.append("    public static int WIDE_STATIC = 7; public static Object WIDE_REF;\n");
        sb.append("    public Wide(java.util.Random r) {\n        super(r);\n");
        for (int i = 0; i < 120; i++) {
            sb.append("        w").append(i).append(" = ").append(randomInit(FIELD_TYPES[i % FIELD_TYPES.length], "r")).append(";\n");
        }
        sb.append("    }\n}\n");
        Files.writeString(pkg.resolve("Wide.java"), sb.toString());
    }

    static void compile(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK (javac) is required");
        List<String> files = new ArrayList<>();
        try (var s = Files.walk(dir.resolve("gen"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        List<String> args = new ArrayList<>(List.of("-g", "-nowarn", "-d", dir.toString()));
        args.addAll(files);
        int rc = compiler.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("compilation of generated classes failed");
    }

    // ----------------------------------------------------------------------------------------- runtime structure

    static final List<Object> KEEP = new ArrayList<>();          // everything reachable stays reachable through this
    static final Map<String, Object> NAMED = new HashMap<>();
    static Object STATIC_ROOT;
    static final AtomicLong SINK = new AtomicLong();

    static final class Node { Node next, prev, side; Object value; int depth; Node(Object v) { value = v; } }
    static final class TreeNode { TreeNode left, right; Object value; }

    interface Greeter { String greet(String who); }

    static class Leak { final Object[] data; Leak(Object[] d) { data = d; } }

    static Object[] makeMultiDim(Random r) {
        return new Object[]{new int[r.nextInt(5) + 1][r.nextInt(5) + 1], new String[3][2][2], new Object[2][3], new long[0][0],
                new double[4][], new boolean[3][3], new char[2][5], new byte[0], new int[0], new Object[0], new String[0][0],
                new Thread.State[]{Thread.State.NEW, Thread.State.RUNNABLE}, new Class<?>[]{String.class, int.class, int[].class}};
    }

    static void deep(int n, Object a, long b, String c, double d, Object lock, Object[] hold) throws InterruptedException {
        Object local = new int[]{n, (int) b};
        if (n > 0) {
            if (n % 7 == 0) {
                synchronized (lock) { deep(n - 1, local, b + 1, c + n, d * 1.01, lock, hold); }
            } else {
                deep(n - 1, local, b + 1, c, d, lock, hold);
            }
        } else {
            hold[0] = local;
            synchronized (hold) { hold.wait(); }
        }
    }

    public static void main(String[] args) throws Exception {
        String out = args[0];
        double liveGb = args.length > 1 ? Double.parseDouble(args[1]) : 2.8;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 7L;
        boolean live = args.length > 3 && args[3].equalsIgnoreCase("live");
        final int payload = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        long target = (long) (liveGb * 1024L * 1024L * 1024L);
        Random r = new Random(seed);
        Runtime rt = Runtime.getRuntime();

        Path dir = Files.createTempDirectory("complexgen");
        generateSources(dir);
        compile(dir);
        System.out.println("Generated and compiled classes in " + dir);

        URL[] urls = {dir.toUri().toURL()};
        List<ClassLoader> loaders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            loaders.add(new URLClassLoader("loader-" + i, urls, ComplexJvmHeapDumpGenerator.class.getClassLoader()));
        }
        // class lists per loader: loader 0 loads everything, the others overlap on the first families
        List<List<Constructor<?>>> ctors = new ArrayList<>();
        List<Class<?>> allClasses = new ArrayList<>();
        for (int li = 0; li < loaders.size(); li++) {
            ClassLoader cl = loaders.get(li);
            List<Constructor<?>> list = new ArrayList<>();
            int famLimit = li == 0 ? FAMILIES : 6 + li * 4;
            for (int f = 0; f < famLimit; f++) {
                for (int l = 0; l < LEVELS; l++) {
                    Class<?> c = Class.forName("gen.F" + f + "L" + l, true, cl);
                    list.add(c.getConstructor(Random.class));
                    allClasses.add(c);
                }
            }
            list.add(Class.forName("gen.Wide", true, cl).getConstructor(Random.class));
            ctors.add(list);
        }
        List<Object> enumsAndOthers = new ArrayList<>();
        ClassLoader l0 = loaders.get(0);
        for (int e = 0; e < ENUMS; e++) {
            Class<?> en = Class.forName("gen.En" + e, true, l0);
            Object[] consts = en.getEnumConstants();
            enumsAndOthers.add(consts);
            enumsAndOthers.add(new EnumMap(Thread.State.class));
            Class<?> rec = Class.forName("gen.Rec" + e, true, l0);
            enumsAndOthers.add(rec.getDeclaredConstructors()[0].newInstance(e, "rec" + e, new long[]{e, 2}, consts[0], e % 2 == 0, e * 1.5));
            Class<?> outer = Class.forName("gen.Outer" + e, true, l0);
            Object o = outer.getConstructor().newInstance();
            outer.getField("payload").set(o, "payload-" + e);
            enumsAndOthers.add(o);
            enumsAndOthers.add(outer.getMethod("makeInner").invoke(o));
            enumsAndOthers.add(outer.getMethod("anon").invoke(o));
            enumsAndOthers.add(outer.getMethod("lambda", String.class, int.class).invoke(o, "cap" + e, e));
            enumsAndOthers.add(Class.forName("gen.Outer" + e + "$Nested", true, l0).getConstructor().newInstance());
        }
        KEEP.add(enumsAndOthers);
        KEEP.add(allClasses);

        // static references from generated classes (GC roots through class statics)
        for (int f = 0; f < FAMILIES; f += 3) {
            for (int li = 0; li < loaders.size(); li++) {
                try {
                    Class<?> c = Class.forName("gen.F" + f + "L3", true, loaders.get(li));
                    c.getField("SR3").set(null, new Object[]{"static-root-" + f + "-" + li, new int[]{f, li}, loaders.get(li)});
                } catch (ClassNotFoundException | NoSuchFieldException ignored) { }
            }
        }

        // JDK variety
        Map<Object, Object> jdk = new LinkedHashMap<>();
        jdk.put("hashmap", new HashMap<>(Map.of("a", 1, "b", "two", "c", new int[]{3})));
        jdk.put("treemap", new TreeMap<>(Map.of(3, "c", 1, "a", 2, "b")));
        jdk.put("chm", new ConcurrentHashMap<>(Map.of("k", "v")));
        jdk.put("identity", new IdentityHashMap<>(Map.of("x", "y")));
        WeakHashMap<Object, Object> whm = new WeakHashMap<>();
        Object strongKey = new Object();
        whm.put(strongKey, "weakvalue");
        jdk.put("weakhashmap", whm);
        jdk.put("strongKey", strongKey);
        jdk.put("linkedlist", new LinkedList<>(List.of(1, 2, 3, "x")));
        jdk.put("arraydeque", new ArrayDeque<>(List.of("p", "q")));
        jdk.put("pq", new PriorityQueue<>(List.of(5, 3, 9, 1)));
        jdk.put("bitset", BitSet.valueOf(new long[]{0xF0F0F0F0L, 0x1234L}));
        jdk.put("optional", Optional.of("present"));
        jdk.put("atomic", new AtomicReference<>(new Object[]{1, 2}));
        jdk.put("unmodifiable", Collections.unmodifiableList(new ArrayList<>(List.of("u1", "u2"))));
        jdk.put("sync", Collections.synchronizedMap(new HashMap<>(Map.of("s", 1))));
        jdk.put("emptyList", Collections.emptyList());
        jdk.put("listOf", List.of(1, "two", 3.0));
        jdk.put("bigint", new BigInteger("123456789012345678901234567890"));
        jdk.put("bigdec", new BigDecimal("12345.678901234567890"));
        jdk.put("instant", Instant.ofEpochSecond(1_700_000_000L));
        jdk.put("date", LocalDate.of(2026, 10, 12));
        jdk.put("locale", Locale.forLanguageTag("zh-Hant-TW"));
        jdk.put("pattern", Pattern.compile("(a+)(b*)[c-f]{2,5}(?<name>\\d+)"));
        jdk.put("directBuffer", ByteBuffer.allocateDirect(4096));
        jdk.put("heapBuffer", ByteBuffer.wrap(new byte[2048]));
        jdk.put("sbuilder", new StringBuilder("a builder with ").append(12345).append(" and unicode é中文"));
        jdk.put("sbuffer", new StringBuffer("buffer"));
        jdk.put("latin1", "plain ascii string");
        jdk.put("utf16", "unicode 中文é string");
        jdk.put("empty", "");
        jdk.put("long", "x".repeat(70000));
        jdk.put("interned", "interned-literal".intern());
        jdk.put("boxed", new Object[]{Integer.valueOf(1), Integer.valueOf(1000), Long.valueOf(5), Double.valueOf(2.5), Character.valueOf('z'), Boolean.TRUE, Byte.valueOf((byte) 3), Short.valueOf((short) 4), Float.valueOf(1.5f)});
        jdk.put("multi", makeMultiDim(r));
        jdk.put("enummap", new EnumMap<>(Map.of(Thread.State.NEW, "n", Thread.State.BLOCKED, "b")));
        MethodHandle mh = MethodHandles.lookup().findVirtual(String.class, "length", MethodType.methodType(int.class));
        jdk.put("methodhandle", mh);
        Greeter proxy = (Greeter) Proxy.newProxyInstance(ComplexJvmHeapDumpGenerator.class.getClassLoader(), new Class<?>[]{Greeter.class},
                (InvocationHandler) (p, m, a) -> "hi " + a[0]);
        jdk.put("proxy", proxy);
        jdk.put("lambda", (Greeter) w -> "hello " + w);
        KEEP.add(jdk);

        // references
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        List<Object> refs = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Object tgt = new int[]{i};
            KEEP.add(tgt);
            refs.add(new WeakReference<>(tgt, queue));
            refs.add(new SoftReference<>(tgt, queue));
            refs.add(new PhantomReference<>(tgt, queue));
        }
        refs.add(queue);
        KEEP.add(refs);

        // pathological shapes: long chain, cycle, diamond, dense random graph, tree
        Node head = new Node("head");
        Node cur = head;
        for (int i = 0; i < 300_000; i++) {
            Node n = new Node(i % 3 == 0 ? (Object) ("chain" + i) : (Object) new int[]{i});
            n.prev = cur; n.depth = i;
            cur.next = n;
            cur = n;
        }
        cur.next = head; head.prev = cur; // cycle
        KEEP.add(head);
        Node diamond = new Node("diamond-top");
        Node shared = new Node("shared");
        shared.value = new byte[1024];
        diamond.next = new Node(shared); diamond.side = new Node(shared);
        diamond.next.next = shared; diamond.side.next = shared;
        KEEP.add(diamond);
        TreeNode tree = buildTree(r, 16);
        KEEP.add(tree);
        Node[] dense = new Node[20_000];
        for (int i = 0; i < dense.length; i++) dense[i] = new Node("d" + i);
        for (int i = 0; i < dense.length; i++) { dense[i].next = dense[r.nextInt(dense.length)]; dense[i].side = dense[r.nextInt(dense.length)]; dense[i].prev = dense[r.nextInt(dense.length)]; }
        KEEP.add(dense);
        Node selfRef = new Node("self"); selfRef.next = selfRef; selfRef.side = selfRef; selfRef.prev = selfRef;
        KEEP.add(selfRef);

        // threads with deep stacks, locals, held monitors and thread locals
        List<Thread> threads = new ArrayList<>();
        ThreadGroup group = new ThreadGroup("complex-group");
        for (int t = 0; t < 8; t++) {
            final int tt = t;
            final Object lock = new Object();
            final Object[] hold = new Object[1];
            Thread th = new Thread(group, () -> {
                ThreadLocal<Object> tl = new ThreadLocal<>();
                tl.set(new Object[]{"threadlocal-" + tt, new int[]{tt}});
                try { deep(10 + tt * 8, "arg" + tt, tt, "c", 1.5, lock, hold); } catch (InterruptedException ignored) { }
            }, "worker-" + t);
            th.setDaemon(true);
            th.start();
            threads.add(th);
        }
        Object parkLock = new Object();
        Thread parked = new Thread(() -> { Object own = new long[]{1, 2, 3}; SINK.addAndGet(own.hashCode()); LockSupport.park(parkLock); }, "parked");
        parked.setDaemon(true);
        parked.start();
        threads.add(parked);
        Thread sleeper = new Thread(() -> { Object own = new Object[]{"sleeper"}; try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) { } SINK.addAndGet(own.hashCode()); }, "sleeper");
        sleeper.setDaemon(true);
        sleeper.start();
        threads.add(sleeper);
        KEEP.add(threads);
        KEEP.add(group);
        Thread.sleep(500);

        STATIC_ROOT = KEEP;
        NAMED.put("loaders", loaders);

        // bulk instances of generated classes, strings, payloads, collections until the live target is reached
        List<Object[]> bulk = new ArrayList<>();
        List<byte[]> blobs = new ArrayList<>();
        KEEP.add(blobs);
        KEEP.add(bulk);
        long lastReport = System.currentTimeMillis();
        int round = 0;
        while (true) {
            Object[] batch = new Object[20_000];
            for (int i = 0; i < batch.length; i++) {
                int li = r.nextInt(loaders.size());
                List<Constructor<?>> cl = ctors.get(li);
                Object o = cl.get(r.nextInt(cl.size())).newInstance(r);
                switch (r.nextInt(14)) {
                    case 0 -> setLink(o, new int[r.nextInt(20)]);
                    case 1 -> setLink(o, "dup-" + r.nextInt(500));
                    case 2 -> setLink(o, new String("dup-" + r.nextInt(500)));
                    case 3 -> setLink(o, "unicode-中文-" + r.nextInt(1000));
                    case 4 -> setLink(o, new StringBuilder("sb").append(r.nextInt()));
                    case 5 -> setLink(o, new byte[r.nextInt(r.nextInt(30) == 0 ? 60_000 : 600)]);
                    case 6 -> setLink(o, new char[r.nextInt(300)]);
                    case 7 -> setLink(o, new HashMap<>(Map.of("k" + r.nextInt(50), r.nextInt(), "z", new long[2])));
                    case 8 -> setLink(o, new ArrayList<>(List.of(r.nextInt(), "x" + r.nextInt(100), new Object[]{o})));
                    case 9 -> setLink(o, new Object[]{o, o, null, "self"});
                    case 10 -> setLink(o, new BigInteger(60, r));
                    case 11 -> setLink(o, i > 0 ? batch[r.nextInt(i)] : null);
                    case 12 -> setLink(o, new WeakReference<>(i > 0 ? batch[r.nextInt(i)] : o));
                    default -> { }
                }
                batch[i] = o;
                if (payload > 0 && (i & 1) == 0) blobs.add(new byte[256 + r.nextInt(payload)]);
            }
            bulk.add(batch);
            // unreachable garbage with cycles (removed by MAT unless it keeps unreachable objects)
            if (round % 3 == 0) {
                for (int g = 0; g < 2000; g++) {
                    Node a = new Node("garbage" + g);
                    Node b = new Node(new int[g % 50]);
                    a.next = b; b.next = a;
                    SINK.addAndGet(g);
                }
            }
            round++;
            long used = rt.totalMemory() - rt.freeMemory();
            long now = System.currentTimeMillis();
            if (now - lastReport > 5000) {
                System.out.printf("round=%d used=%.2f GB / target %.2f GB%n", round, used / 1073741824.0, liveGb);
                lastReport = now;
            }
            if (used >= target) {
                if (live) System.gc();
                break;
            }
        }
        // a last batch of garbage right before the dump so that it is still present when no GC runs
        for (int g = 0; g < 50_000; g++) {
            Node a = new Node("last-garbage" + g);
            a.next = new Node(new long[g % 20]);
            a.next.next = a;
        }

        System.out.println("Writing heap dump (" + (live ? "live objects only" : "including unreachable objects") + ") to " + out + " ...");
        long t0 = System.currentTimeMillis();
        HotSpotDiagnosticMXBean mx = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        mx.dumpHeap(out, live);
        System.out.printf("Dump written in %.1f s, size %.2f GB%n", (System.currentTimeMillis() - t0) / 1000.0, new File(out).length() / 1073741824.0);
        System.out.println(KEEP.size() + NAMED.size() + bulk.size());
    }

    static Object setLink(Object o, Object v) throws Exception {
        o.getClass().getField("link").set(o, v);
        return null;
    }

    static TreeNode buildTree(Random r, int depth) {
        TreeNode n = new TreeNode();
        n.value = depth % 2 == 0 ? (Object) Integer.valueOf(r.nextInt(10_000)) : (Object) ("v" + depth);
        if (depth > 0) {
            n.left = buildTree(r, depth - 1);
            if (r.nextInt(3) != 0) n.right = buildTree(r, depth - 1);
        }
        return n;
    }
}
