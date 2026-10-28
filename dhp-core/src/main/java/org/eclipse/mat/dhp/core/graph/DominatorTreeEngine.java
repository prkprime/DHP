package org.eclipse.mat.dhp.core.graph;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Computes dominator tree relationships and retained sizes using the Lengauer-Tarjan algorithm and bottom-up
 * retained size accumulation on the in-memory {@link HeapGraph}, persisting inbound references and dominator
 * results through the storage engine.
 *
 * <p>Memory is managed in stages so that the peak stays well below the sum of all structures: the outbound edge
 * log is released as soon as the DFS is done, the inbound CSR as soon as the semi-dominator pass is done, and the
 * shallow-size column is only reloaded for the final accumulation.</p>
 */
public class DominatorTreeEngine {
    private static final Logger log = LoggerFactory.getLogger(DominatorTreeEngine.class);

    private final HeapStorageEngine storage;
    private final boolean fileBackedInbound;
    private final File tempDir;

    /** Small {@code int} sequence: a heap array or a memory-mapped temp file (for tight-memory modes). */
    private interface IntSeq extends AutoCloseable {
        int get(int i);

        void set(int i, int v);

        @Override
        default void close() {
        }
    }

    private static final class InHeap implements IntSeq {
        final int[] array;

        InHeap(int size) {
            this.array = new int[size];
        }

        @Override public int get(int i) { return array[i]; }
        @Override public void set(int i, int v) { array[i] = v; }
    }

    private static final class InFile implements IntSeq {
        final org.eclipse.mat.dhp.core.memory.PagedIntArray storage;

        InFile(org.eclipse.mat.dhp.core.memory.PagedIntArray storage) {
            this.storage = storage;
        }

        @Override public int get(int i) { return storage.get(i); }
        @Override public void set(int i, int v) { storage.set(i, v); }
        @Override public void close() { storage.close(); }
    }

    public DominatorTreeEngine(HeapStorageEngine storage) {
        this(storage, false, null);
    }

    /**
     * @param fileBackedInbound when true, the inbound CSR (one 4-byte entry per reference) is built in a
     *        memory-mapped temp file in {@code tempDir} instead of the JVM heap (tight-memory modes).
     */
    public DominatorTreeEngine(HeapStorageEngine storage, boolean fileBackedInbound, File tempDir) {
        this.storage = storage;
        this.fileBackedInbound = fileBackedInbound;
        this.tempDir = tempDir;
    }

    private IntSeq newInboundArray(int size) {
        if (fileBackedInbound) {
            try {
                return new InFile(new org.eclipse.mat.dhp.core.memory.PagedIntArray(tempDir, "dhp_inbound_", size));
            } catch (Exception e) {
                log.warn("Cannot create file-backed inbound CSR ({}), keeping it in the heap", e.toString());
            }
        }
        return new InHeap(size);
    }

    public void computeAndStore(HeapGraph graph) throws SQLException {
        log.info("Starting Lengauer-Tarjan Dominator Tree & Retained Size computation...");
        long t0 = System.currentTimeMillis();

        int n = graph.objectCount();
        if (n == 0) return;

        int superRoot = n; // Artificial super root index
        int totalVertices = n + 2; // Supports 1-indexed vertex array up to n + 1

        // 1. Collect unique GC root object IDs
        BitSet rootSeen = new BitSet(n);
        IntArrayList rootList = new IntArrayList();
        for (var root : graph.gcRoots()) {
            int rootId = root.objectId();
            // Roots of a known thread (stack locals) are reached through the thread object, as in MAT
            if (rootId >= 0 && rootId < n && root.threadAddress() == 0 && !rootSeen.get(rootId)) {
                rootSeen.set(rootId);
                rootList.add(rootId);
            }
        }
        rootSeen = null;
        int[] gcRootIds = rootList.toIntArray();
        log.info("Collected {} unique GC root objects for dominator analysis", gcRootIds.length);

        // Like MAT, keep unreachable objects by promoting some of them to artificial roots
        long tu = System.currentTimeMillis();
        int[] extraRoots = UnreachableRoots.findExtraRoots(graph, n, gcRootIds);
        if (extraRoots.length > 0) {
            int[] all = java.util.Arrays.copyOf(gcRootIds, gcRootIds.length + extraRoots.length);
            System.arraycopy(extraRoots, 0, all, gcRootIds.length, extraRoots.length);
            gcRootIds = all;
            java.util.List<org.eclipse.mat.dhp.core.model.HeapRecords.GcRootRecord> records = new java.util.ArrayList<>(extraRoots.length);
            for (int id : extraRoots) {
                records.add(new org.eclipse.mat.dhp.core.model.HeapRecords.GcRootRecord(
                        id, 0L /* resolved from the stored address column */, 0L, org.eclipse.mat.dhp.core.model.HprofConstants.ROOT_UNREACHABLE, 0L, -1));
            }
            storage.appendGcRoots(records);
        }
        log.info("Unreachable-object roots: {} extra roots in {} ms", extraRoots.length, System.currentTimeMillis() - tu);

        int[] starts = graph.rawStarts();
        int[][] chunks = graph.rawChunks();
        final int shift = HeapGraph.RAW_CHUNK_SHIFT;
        final int mask = HeapGraph.RAW_CHUNK_MASK;

        // 2. Build inHead and inTo CSR graph (with superRoot as predecessor to all GC roots)
        // MAT lists the instances of a class (the objects whose first reference is that class) before all other referrers
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap pseudoCount = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int[] inHead = new int[n + 1];
        for (int u = 0; u < n; u++) {
            int s = starts[u];
            if (s > 0) {
                int[] c = chunks[s >>> shift];
                int off = s & mask;
                int end = off + c[off];
                for (int e = off + 1; e <= end; e++) {
                    int v = c[e];
                    if (v >= 0 && v < n) {
                        inHead[v + 1]++;
                        if (e == off + 1) pseudoCount.addTo(v, 1);
                    }
                }
            } else if (s < 0) {
                int[] huge = graph.hugeEdgesOf(u);
                for (int i = 0; i < huge.length; i++) {
                    int v = huge[i];
                    if (v >= 0 && v < n) {
                        inHead[v + 1]++;
                        if (i == 0) pseudoCount.addTo(v, 1);
                    }
                }
            }
        }
        for (int r : gcRootIds) {
            inHead[r + 1]++;
        }
        for (int i = 0; i < n; i++) {
            inHead[i + 1] += inHead[i];
        }
        int totalInEdges = inHead[n];
        IntSeq inTo = newInboundArray(totalInEdges);

        // Scratch arrays (reused across phases to bound the memory footprint)
        int[] dfsStack = new int[totalVertices];
        int[] edgePos = new int[totalVertices];

        // Temporarily reuse dfsStack as the insertion cursor of each inbound list
        int[] inPos = dfsStack;
        System.arraycopy(inHead, 0, inPos, 0, n);
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap pseudoPos = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(pseudoCount.size());
        for (var pe : pseudoCount.int2IntEntrySet()) {
            pseudoPos.put(pe.getIntKey(), inPos[pe.getIntKey()]);
            inPos[pe.getIntKey()] += pe.getIntValue();
        }
        for (int r : gcRootIds) {
            inTo.set(inPos[r]++, superRoot);
        }
        for (int u = 0; u < n; u++) {
            int s = starts[u];
            if (s > 0) {
                int[] c = chunks[s >>> shift];
                int off = s & mask;
                int end = off + c[off];
                for (int e = off + 1; e <= end; e++) {
                    int v = c[e];
                    if (v >= 0 && v < n) inTo.set(e == off + 1 ? pseudoPos.addTo(v, 1) : inPos[v]++, u);
                }
            } else if (s < 0) {
                int[] huge = graph.hugeEdgesOf(u);
                for (int i = 0; i < huge.length; i++) {
                    int v = huge[i];
                    if (v >= 0 && v < n) inTo.set(i == 0 ? pseudoPos.addTo(v, 1) : inPos[v]++, u);
                }
            }
        }
        Arrays.fill(dfsStack, 0);
        long t1 = System.currentTimeMillis();
        log.info("Inbound CSR built: {} edges in {} ms", totalInEdges, t1 - t0);

        // Persist inbound references (superRoot entries are artificial and skipped)
        if (inTo instanceof InHeap h) {
            storage.saveInboundEdges(inHead, h.array, n, superRoot);
        } else {
            storage.saveInboundEdgesFrom(inHead, inTo::get, n, superRoot);
        }
        long t2 = System.currentTimeMillis();
        log.info("Inbound references persisted in {} ms", t2 - t1);

        // 3. Iterative DFS from superRoot
        int[] semi = new int[totalVertices];
        int[] vertex = new int[totalVertices];
        int[] parent = new int[totalVertices];

        int top = 0;
        dfsStack[0] = superRoot;
        edgePos[0] = 0;
        top = 1;

        int dfsCount = 0;
        semi[superRoot] = ++dfsCount;
        vertex[dfsCount] = superRoot;

        while (top > 0) {
            int u = dfsStack[top - 1];
            int e = edgePos[top - 1]++;
            int w = -1;

            if (u == superRoot) {
                if (e < gcRootIds.length) {
                    w = gcRootIds[e];
                }
            } else {
                int s = starts[u];
                if (s > 0) {
                    int[] c = chunks[s >>> shift];
                    int off = s & mask;
                    if (e < c[off]) {
                        w = c[off + 1 + e];
                    }
                } else if (s < 0) {
                    int[] huge = graph.hugeEdgesOf(u);
                    if (e < huge.length) {
                        w = huge[e];
                    }
                }
            }

            if (w != -1) {
                if (w >= 0 && w < n && semi[w] == 0) {
                    semi[w] = ++dfsCount;
                    vertex[dfsCount] = w;
                    parent[w] = u;
                    dfsStack[top] = w;
                    edgePos[top] = 0;
                    top++;
                }
            } else {
                top--;
            }
        }
        long t3 = System.currentTimeMillis();
        log.info("DFS complete: reached {} of {} heap objects from GC roots in {} ms", dfsCount - 1, n, t3 - t2);

        // The outbound graph is no longer needed: release memory immediately
        starts = null;
        chunks = null;
        graph.releaseEdges();

        // 4. Lengauer-Tarjan: compute semi-dominators and dominators
        int[] ancestor = new int[totalVertices];
        int[] label = new int[totalVertices];
        int[] dom = new int[totalVertices];
        Arrays.fill(ancestor, -1);
        Arrays.fill(dom, -1);
        for (int i = 0; i < totalVertices; i++) {
            label[i] = i;
        }

        // Reuse dfsStack and edgePos for bucketHead and bucketNext
        int[] bucketHead = dfsStack;
        int[] bucketNext = edgePos;
        Arrays.fill(bucketHead, -1);

        for (int i = dfsCount; i >= 2; i--) {
            int w = vertex[i];

            int inStart = inHead[w];
            int inEnd = inHead[w + 1];
            for (int idx = inStart; idx < inEnd; idx++) {
                int v = inTo.get(idx);
                if (semi[v] == 0) continue; // Unreachable predecessor
                int u = eval(v, ancestor, label, semi);
                if (semi[u] < semi[w]) {
                    semi[w] = semi[u];
                }
            }

            // Add w to bucket of vertex[semi[w]]
            int b = vertex[semi[w]];
            bucketNext[w] = bucketHead[b];
            bucketHead[b] = w;

            // Link parent[w] and w
            ancestor[w] = parent[w];

            // Process bucket of parent[w]
            int p = parent[w];
            int node = bucketHead[p];
            while (node != -1) {
                int next = bucketNext[node];
                int u = eval(node, ancestor, label, semi);
                dom[node] = (semi[u] < semi[node]) ? u : p;
                node = next;
            }
            bucketHead[p] = -1;
        }

        for (int i = 2; i <= dfsCount; i++) {
            int w = vertex[i];
            if (dom[w] != vertex[semi[w]]) {
                dom[w] = dom[dom[w]];
            }
        }
        dom[superRoot] = -1;
        long t4 = System.currentTimeMillis();
        log.info("Semi-dominator / dominator pass finished in {} ms", t4 - t3);

        // Inbound graph and DFS scratch are no longer needed: release memory immediately
        inHead = null;
        inTo.close();
        inTo = null;
        vertex = null;
        parent = null;

        // 5. Retained size calculation: build dominator tree children and post-order traversal
        // Reuse ancestor and label for domHead and domNext
        int[] domHead = ancestor;
        int[] domNext = label;
        Arrays.fill(domHead, -1);

        for (int i = 0; i < n; i++) {
            if (semi[i] > 0) {
                int p = dom[i];
                if (p >= 0 && p < totalVertices) {
                    domNext[i] = domHead[p];
                    domHead[p] = i;
                }
            }
        }

        long[] retainedSizes = storage.loadAllObjectUsedSizes(n);

        // Reuse bucketHead and bucketNext for postStack and childPointer
        int[] postStack = bucketHead;
        int[] childPointer = bucketNext;
        int postTop = 0;

        postStack[0] = superRoot;
        childPointer[0] = domHead[superRoot];
        postTop = 1;

        while (postTop > 0) {
            int ch = childPointer[postTop - 1];
            if (ch != -1) {
                childPointer[postTop - 1] = domNext[ch];
                postStack[postTop] = ch;
                childPointer[postTop] = domHead[ch];
                postTop++;
            } else {
                int curr = postStack[--postTop];
                if (postTop > 0) {
                    int p = postStack[postTop - 1];
                    if (p != superRoot && p >= 0 && p < n) {
                        retainedSizes[p] += retainedSizes[curr];
                    }
                }
            }
        }

        // Final immediate dominator per object (-1 for top-level / unreachable objects)
        for (int i = 0; i < n; i++) {
            dom[i] = (semi[i] > 0 && dom[i] != superRoot) ? dom[i] : -1;
        }
        long t5 = System.currentTimeMillis();
        log.info("Retained sizes accumulated in {} ms", t5 - t4);

        semi = null;
        ancestor = null;
        label = null;
        domHead = null;
        domNext = null;
        bucketHead = null;
        bucketNext = null;
        postStack = null;
        childPointer = null;
        dfsStack = null;
        edgePos = null;

        // 6. Persist dominator tree
        storage.saveDominators(dom, retainedSizes, n);
        log.info("Lengauer-Tarjan Dominator tree and retained sizes successfully persisted for {} objects in {} ms (total {} ms).",
                n, System.currentTimeMillis() - t5, System.currentTimeMillis() - t0);
    }

    private int[] compressStack = new int[128];

    private int eval(int v, int[] ancestor, int[] label, int[] semi) {
        if (ancestor[v] == -1) {
            return v;
        }
        compress(v, ancestor, label, semi);
        return label[v];
    }

    private void compress(int v, int[] ancestor, int[] label, int[] semi) {
        int a = ancestor[v];
        if (a == -1 || ancestor[a] == -1) {
            return;
        }
        int top = 0;
        int curr = v;
        while (ancestor[curr] != -1 && ancestor[ancestor[curr]] != -1) {
            if (top == compressStack.length) {
                compressStack = Arrays.copyOf(compressStack, compressStack.length * 2);
            }
            compressStack[top++] = curr;
            curr = ancestor[curr];
        }
        while (top > 0) {
            int node = compressStack[--top];
            int p = ancestor[node];
            if (semi[label[p]] < semi[label[node]]) {
                label[node] = label[p];
            }
            ancestor[node] = ancestor[p];
        }
    }
}
