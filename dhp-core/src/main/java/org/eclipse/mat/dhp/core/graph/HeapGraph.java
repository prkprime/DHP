package org.eclipse.mat.dhp.core.graph;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.eclipse.mat.dhp.core.memory.IAddressToIdMap;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.memory.PagedIntArray;
import org.eclipse.mat.dhp.core.memory.PagedLongArray;
import org.eclipse.mat.dhp.core.model.HeapRecords;

import java.io.File;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * In-memory, object-ID indexed result of Pass 2.
 *
 * <p>Holds one primitive column per object attribute and a compact edge log. The out-edges of an object are stored
 * contiguously in the log as {@code [degree, target0, target1, ...]} and located through {@code edgeStart[id]}.
 * Objects are written in file order, so the log is not ordered by ID; a start pointer per object makes that
 * irrelevant and avoids a second pass to re-sort edges into a classic CSR layout.</p>
 *
 * <p>Footprint is about 28 bytes per object plus 4 bytes per edge (plus 4 bytes per object for the degree header).
 * In the {@link MemoryGovernor.MemoryMode#TIGHT TIGHT} and more restrictive modes the cold per-object columns (class,
 * size, file position) live in memory-mapped temporary files instead of the JVM heap, which removes about 20 bytes
 * per object from the heap. Columns that are only needed to persist the objects are released by
 * {@link #releaseObjectColumns()} so that the dominator phase has the heap to itself.</p>
 */
public final class HeapGraph {
    private static final int CHUNK_SHIFT = 22;                 // 4M ints (16 MB) per edge chunk
    private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;
    private static final int HUGE_DEGREE = 1 << 16;            // lists above this are kept out of line

    /** Sequential int column: either a plain heap array or a memory-mapped temp file. */
    public interface IntColumn {
        int get(int i);

        void set(int i, int v);

        void close();
    }

    /** Sequential long column: either a plain heap array or a memory-mapped temp file. */
    public interface LongColumn {
        long get(int i);

        void set(int i, long v);

        void close();
    }

    private static IntColumn heapInt(int[] a) {
        return new IntColumn() {
            @Override public int get(int i) { return a[i]; }
            @Override public void set(int i, int v) { a[i] = v; }
            @Override public void close() { }
        };
    }

    private static LongColumn heapLong(long[] a) {
        return new LongColumn() {
            @Override public long get(int i) { return a[i]; }
            @Override public void set(int i, long v) { a[i] = v; }
            @Override public void close() { }
        };
    }

    private int objectCount;
    private int baseInstanceId;
    // Set by compact(): old ID of every surviving object, and rank tables to map old IDs to new ones
    private int[] oldIdOf;
    private long[] reachWords;
    private int[] wordRank;
    private IAddressToIdMap addressMap;
    private IntColumn classObjId;
    private LongColumn usedSize;
    private LongColumn filePosition;
    private BitSet arrayFlags;
    private final boolean fileBackedColumns;
    private List<HeapRecords.GcRootRecord> gcRoots = List.of();

    private int[] edgeStart;
    private int[][] edgeChunks = new int[16][];
    private int chunkCount;
    private int cursor;
    private final Int2ObjectOpenHashMap<int[]> hugeEdges = new Int2ObjectOpenHashMap<>();
    private long edgeCount;

    public HeapGraph(int objectCount, int baseInstanceId, IAddressToIdMap addressMap) {
        this(objectCount, baseInstanceId, addressMap, null, MemoryGovernor.MemoryMode.FAST);
    }

    /**
     * @param tempDir directory for the file-backed columns ({@code null} falls back to heap columns, as does any
     *                failure to create the temporary files).
     */
    public HeapGraph(int objectCount, int baseInstanceId, IAddressToIdMap addressMap, File tempDir, MemoryGovernor.MemoryMode mode) {
        this.objectCount = objectCount;
        this.baseInstanceId = baseInstanceId;
        this.addressMap = addressMap;
        this.arrayFlags = new BitSet(objectCount);
        this.edgeStart = new int[objectCount];
        // Chunk 0 starts with a zero-degree sentinel so that edgeStart == 0 means "no edges".
        int firstChunk = (int) Math.min(CHUNK_SIZE, 4L * objectCount + 64);
        this.edgeChunks[0] = new int[firstChunk];
        this.chunkCount = 1;
        this.cursor = 1;

        boolean file = mode != MemoryGovernor.MemoryMode.FAST;
        if (file) {
            try {
                PagedIntArray pc = new PagedIntArray(tempDir, "dhp_col_class_", objectCount);
                PagedLongArray ps = new PagedLongArray(tempDir, "dhp_col_size_", objectCount);
                PagedLongArray pp = new PagedLongArray(tempDir, "dhp_col_pos_", objectCount);
                this.classObjId = new IntColumn() {
                    @Override public int get(int i) { return pc.get(i); }
                    @Override public void set(int i, int v) { pc.set(i, v); }
                    @Override public void close() { pc.close(); }
                };
                this.usedSize = new LongColumn() {
                    @Override public long get(int i) { return ps.get(i); }
                    @Override public void set(int i, long v) { ps.set(i, v); }
                    @Override public void close() { ps.close(); }
                };
                this.filePosition = new LongColumn() {
                    @Override public long get(int i) { return pp.get(i); }
                    @Override public void set(int i, long v) { pp.set(i, v); }
                    @Override public void close() { pp.close(); }
                };
            } catch (Exception e) {
                // Temporary files unavailable: degrade to heap columns rather than failing the ingestion
                org.slf4j.LoggerFactory.getLogger(HeapGraph.class)
                        .warn("Cannot create file-backed object columns ({}), falling back to heap columns", e.toString());
                this.classObjId = heapInt(new int[objectCount]);
                this.usedSize = heapLong(new long[objectCount]);
                this.filePosition = heapLong(new long[objectCount]);
                file = false;
            }
        } else {
            this.classObjId = heapInt(new int[objectCount]);
            this.usedSize = heapLong(new long[objectCount]);
            this.filePosition = heapLong(new long[objectCount]);
        }
        this.fileBackedColumns = file;
    }

    public int objectCount() {
        return objectCount;
    }

    public int baseInstanceId() {
        return baseInstanceId;
    }

    public long edgeCount() {
        return edgeCount;
    }

    public List<HeapRecords.GcRootRecord> gcRoots() {
        return gcRoots;
    }

    public void setGcRoots(List<HeapRecords.GcRootRecord> roots) {
        this.gcRoots = roots;
    }

    // ---------------------------------------------------------------- object columns

    public void setObject(int id, int classObj, long size, long position, boolean isArray) {
        classObjId.set(id, classObj);
        usedSize.set(id, size);
        filePosition.set(id, position);
        if (isArray) {
            arrayFlags.set(id);
        }
    }

    public long addressOf(int id) {
        return addressMap.addressOf(oldIdOf != null ? oldIdOf[id] : id);
    }

    /** New ID of an object that survived {@link #compact}; -1 if it was removed. Identity if never compacted. */
    public int newIdOf(int oldId) {
        if (reachWords == null) {
            return oldId;
        }
        if (oldId < 0 || (oldId >>> 6) >= reachWords.length) {
            return -1;
        }
        long w = reachWords[oldId >>> 6];
        long bit = 1L << (oldId & 63);
        if ((w & bit) == 0) {
            return -1;
        }
        return wordRank[oldId >>> 6] + Long.bitCount(w & (bit - 1));
    }

    /**
     * Removes every object that is not marked in {@code reachable}, renumbers the survivors in ascending old-ID order
     * (like MAT's garbage cleaner) and rewrites class references and edge targets. Edges of survivors only point to
     * survivors because a marked object's referents are marked as well.
     */
    public void compact(java.util.BitSet reachable) {
        final int n = objectCount;
        final int nw = (n + 63) >>> 6;
        long[] w = Arrays.copyOf(reachable.toLongArray(), nw);
        int[] rank = new int[nw + 1];
        for (int i = 0; i < nw; i++) {
            rank[i + 1] = rank[i] + Long.bitCount(w[i]);
        }
        final int n2 = rank[nw];
        this.reachWords = w;
        this.wordRank = rank;
        int[] oldIds = new int[n2];

        BitSet newFlags = new BitSet(n2);
        Int2ObjectOpenHashMap<int[]> newHuge = new Int2ObjectOpenHashMap<>();
        int j = 0;
        for (int old = reachable.nextSetBit(0); old >= 0 && old < n; old = reachable.nextSetBit(old + 1), j++) {
            oldIds[j] = old;
            int cls = classObjId.get(old);
            classObjId.set(j, cls >= 0 ? newIdOf(cls) : cls);
            usedSize.set(j, usedSize.get(old));
            filePosition.set(j, filePosition.get(old));
            if (arrayFlags.get(old)) {
                newFlags.set(j);
            }
            int st = edgeStart[old];
            edgeStart[j] = st;
            if (st < 0) {
                newHuge.put(j, hugeEdges.get(old));
            }
        }
        this.arrayFlags = newFlags;
        this.hugeEdges.clear();
        this.hugeEdges.putAll(newHuge);

        long edges = 0;
        for (int id = 0; id < n2; id++) {
            int s = edgeStart[id];
            if (s > 0) {
                int[] chunk = edgeChunks[s >>> CHUNK_SHIFT];
                int off = s & CHUNK_MASK;
                int len = chunk[off];
                edges += len;
                for (int e = off + 1; e <= off + len; e++) {
                    chunk[e] = newIdOf(chunk[e]);
                }
            } else if (s < 0) {
                int[] huge = hugeEdges.get(id);
                edges += huge.length;
                for (int e = 0; e < huge.length; e++) {
                    huge[e] = newIdOf(huge[e]);
                }
            }
        }
        this.edgeCount = edges;
        this.oldIdOf = oldIds;
        this.objectCount = n2;
    }

    public int classObjId(int id) {
        return classObjId.get(id);
    }

    public long usedSize(int id) {
        return usedSize.get(id);
    }

    public long filePosition(int id) {
        return filePosition.get(id);
    }

    public boolean isArray(int id) {
        return arrayFlags.get(id);
    }

    /** True when the object columns are memory-mapped temp files instead of heap arrays. */
    public boolean fileBackedColumns() {
        return fileBackedColumns;
    }

    /**
     * Releases the columns that are only needed to persist objects (everything except sizes and edges). Any
     * memory-mapped column files are unmapped and deleted. Idempotent.
     */
    public void releaseObjectColumns() {
        if (classObjId != null) {
            classObjId.close();
        }
        if (filePosition != null) {
            filePosition.close();
        }
        arrayFlags = null;
        addressMap = null;
        oldIdOf = null;
        reachWords = null;
        wordRank = null;
        classObjId = null;
        filePosition = null;
    }

    /**
     * Releases the shallow size column (the dominator phase reloads it from storage when it needs it). Any
     * memory-mapped file is unmapped and deleted. Idempotent.
     */
    public void releaseUsedSizes() {
        if (usedSize != null) {
            usedSize.close();
        }
        usedSize = null;
    }

    /**
     * Releases everything still mapped (columns, sizes and the edge log). Call when the graph is no longer needed.
     */
    public void close() {
        releaseObjectColumns();
        releaseUsedSizes();
        releaseEdges();
    }

    // ---------------------------------------------------------------- edges

    /** Stores the out-edge list of {@code id}. The caller keeps ownership of {@code src}. */
    public void setEdges(int id, int[] src, int len) {
        if (len == 0) {
            edgeStart[id] = 0;
            return;
        }
        edgeCount += len;
        if (len > HUGE_DEGREE) {
            hugeEdges.put(id, Arrays.copyOf(src, len));
            edgeStart[id] = -1;
            return;
        }
        int need = len + 1;
        int[] chunk = edgeChunks[chunkCount - 1];
        if (cursor + need > chunk.length) {
            if (chunkCount > (Integer.MAX_VALUE >>> CHUNK_SHIFT)) {
                throw new IllegalStateException("Edge count exceeds the 2^31 limit of the in-memory edge log");
            }
            if (chunkCount == edgeChunks.length) {
                edgeChunks = Arrays.copyOf(edgeChunks, chunkCount * 2);
            }
            chunk = new int[CHUNK_SIZE];
            edgeChunks[chunkCount++] = chunk;
            cursor = 0;
        }
        edgeStart[id] = ((chunkCount - 1) << CHUNK_SHIFT) | cursor;
        chunk[cursor] = len;
        System.arraycopy(src, 0, chunk, cursor + 1, len);
        cursor += need;
    }

    // Raw access for hot loops (see DominatorTreeEngine): start >= 0 -> chunk[start >>> CHUNK_SHIFT][start & CHUNK_MASK] holds
    // the degree followed by the targets; start < 0 -> the list is in hugeEdgesOf(id); start == 0 -> no edges.
    public static final int RAW_CHUNK_SHIFT = CHUNK_SHIFT;
    public static final int RAW_CHUNK_MASK = CHUNK_MASK;

    public int[] rawStarts() {
        return edgeStart;
    }

    public int[][] rawChunks() {
        return edgeChunks;
    }

    public int[] hugeEdgesOf(int id) {
        return hugeEdges.get(id);
    }

    public int outDegree(int id) {
        int s = edgeStart[id];
        return s >= 0 ? edgeChunks[s >>> CHUNK_SHIFT][s & CHUNK_MASK] : hugeEdges.get(id).length;
    }

    public int outEdge(int id, int index) {
        int s = edgeStart[id];
        return s >= 0 ? edgeChunks[s >>> CHUNK_SHIFT][(s & CHUNK_MASK) + 1 + index] : hugeEdges.get(id)[index];
    }

    /** Copies the out-edges of {@code id} into {@code dst} (which must be large enough) and returns the degree. */
    public int copyOutEdges(int id, int[] dst) {
        int s = edgeStart[id];
        if (s >= 0) {
            int[] chunk = edgeChunks[s >>> CHUNK_SHIFT];
            int off = s & CHUNK_MASK;
            int len = chunk[off];
            System.arraycopy(chunk, off + 1, dst, 0, len);
            return len;
        }
        int[] huge = hugeEdges.get(id);
        System.arraycopy(huge, 0, dst, 0, huge.length);
        return huge.length;
    }

    /**
     * Brings every out-edge list into the form MAT stores it in: the class reference stays first, the remaining targets
     * follow in ascending id order, without duplicates (and without a repeat of the class) or dangling targets. Object ids
     * are address ranks, so this is MAT's address order. Run once after the final ids are known.
     */
    public void normalizeEdges() {
        final int n = objectCount;
        final int block = 1 << 14;
        java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        java.util.stream.IntStream.range(0, (n + block - 1) / block).parallel().forEach(b -> {
            long sum = 0;
            for (int id = b * block, end = Math.min(n, id + block); id < end; id++) {
                int s = edgeStart[id];
                if (s > 0) {
                    int[] chunk = edgeChunks[s >>> CHUNK_SHIFT];
                    int off = s & CHUNK_MASK;
                    int len = normalizeList(chunk, off + 1, chunk[off], n);
                    chunk[off] = len;
                    sum += len;
                }
            }
            total.addAndGet(sum);
        });
        long edges = total.get();
        // out-of-line lists are replaced (the map must not be touched concurrently)
        for (int id : hugeEdges.keySet().toIntArray()) {
            int[] list = hugeEdges.get(id);
            int len = normalizeList(list, 0, list.length, n);
            hugeEdges.put(id, len == list.length ? list : Arrays.copyOf(list, len));
            edges += len;
        }
        this.edgeCount = edges;
    }

    /** Normalizes {@code a[from, from+len)} in place and returns the new length. */
    private static int normalizeList(int[] a, int from, int len, int n) {
        if (len <= 1) {
            return len;
        }
        int first = a[from];
        Arrays.sort(a, from + 1, from + len);
        int w = from + 1;
        for (int r = from + 1; r < from + len; r++) {
            int v = a[r];
            if (v < 0 || v >= n || v == first || (w > from + 1 && a[w - 1] == v)) {
                continue;
            }
            a[w++] = v;
        }
        return w - from;
    }

    /** Frees the edge log (after the edges were persisted and the dominator phase no longer needs them). */
    public void releaseEdges() {
        edgeStart = null;
        edgeChunks = null;
        hugeEdges.clear();
        chunkCount = 0;
    }
}
