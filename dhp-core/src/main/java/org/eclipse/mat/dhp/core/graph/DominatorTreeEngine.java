package org.eclipse.mat.dhp.core.graph;

import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes dominator tree relationships and retained sizes
 * using the Lengauer-Tarjan algorithm and bottom-up retained size accumulation,
 * persisting results directly into the database.
 */
public class DominatorTreeEngine {
    private static final Logger log = LoggerFactory.getLogger(DominatorTreeEngine.class);

    private final HeapStorageEngine storage;

    public DominatorTreeEngine(HeapStorageEngine storage) {
        this.storage = storage;
    }

    public void computeAndStore() throws SQLException {
        log.info("Starting Lengauer-Tarjan Dominator Tree & Retained Size computation...");

        int objectCount = storage.getObjectCount();
        if (objectCount == 0) return;

        int n = objectCount;
        int superRoot = n; // Artificial super root index
        int totalVertices = n + 2; // Supports 1-indexed vertex array up to n + 1

        // 1. Load outbound adjacency and shallow sizes
        int[][] outAdj = storage.loadAllOutboundReferences(n);
        long[] shallowSizes = storage.loadAllObjectUsedSizes(n);

        // 2. Collect unique GC root object IDs
        Set<Integer> gcRootsSet = new LinkedHashSet<>();
        for (var root : storage.getGcRoots()) {
            int rootId = storage.getObjectIdByAddress(root.objectAddress());
            if (rootId >= 0 && rootId < n) {
                gcRootsSet.add(rootId);
            }
        }
        int[] gcRootIds = gcRootsSet.stream().mapToInt(Integer::intValue).toArray();
        log.info("Collected {} unique GC root objects for dominator analysis", gcRootIds.length);

        // 3. Build inAdj graph (with superRoot as predecessor to all GC roots)
        int[] inDegree = new int[n];
        for (int u = 0; u < n; u++) {
            for (int v : outAdj[u]) {
                if (v >= 0 && v < n) {
                    inDegree[v]++;
                }
            }
        }
        for (int r : gcRootIds) {
            inDegree[r]++; // Edge from superRoot -> r
        }

        int[][] inAdj = new int[n][];
        for (int i = 0; i < n; i++) {
            inAdj[i] = new int[inDegree[i]];
        }
        int[] inPos = new int[n];
        for (int r : gcRootIds) {
            inAdj[r][inPos[r]++] = superRoot;
        }
        for (int u = 0; u < n; u++) {
            for (int v : outAdj[u]) {
                if (v >= 0 && v < n) {
                    inAdj[v][inPos[v]++] = u;
                }
            }
        }

        // 4. Lengauer-Tarjan structures
        int[] semi = new int[totalVertices];
        int[] vertex = new int[totalVertices];
        int[] parent = new int[totalVertices];
        int[] ancestor = new int[totalVertices];
        int[] label = new int[totalVertices];
        int[] dom = new int[totalVertices];

        Arrays.fill(ancestor, -1);
        Arrays.fill(dom, -1);
        for (int i = 0; i < totalVertices; i++) {
            label[i] = i;
        }

        // 5. Iterative DFS from superRoot
        int[] dfsStack = new int[totalVertices];
        int[] edgePos = new int[totalVertices];
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
            int[] succ = (u == superRoot) ? gcRootIds : outAdj[u];

            if (e < succ.length) {
                int w = succ[e];
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
        log.info("DFS complete: reached {} of {} heap objects from GC roots", dfsCount - 1, n);

        // 6. Lengauer-Tarjan algorithm: compute semi-dominators and dominators
        int[] bucketHead = new int[totalVertices];
        int[] bucketNext = new int[totalVertices];
        Arrays.fill(bucketHead, -1);

        for (int i = dfsCount; i >= 2; i--) {
            int w = vertex[i];

            for (int v : inAdj[w]) {
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

        // 7. Retained size calculation: build dominator tree children and post-order traversal
        int[] domHead = new int[totalVertices];
        int[] domNext = new int[totalVertices];
        Arrays.fill(domHead, -1);

        for (int i = 0; i < n; i++) {
            if (semi[i] > 0) {
                int p = dom[i];
                domNext[i] = domHead[p];
                domHead[p] = i;
            }
        }

        long[] retainedSizes = new long[n];
        for (int i = 0; i < n; i++) {
            retainedSizes[i] = shallowSizes[i];
        }

        // Iterative post-order traversal starting at superRoot
        int[] postStack = new int[totalVertices];
        int[] childPointer = new int[totalVertices];
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
                    if (p != superRoot) {
                        retainedSizes[p] += retainedSizes[curr];
                    }
                }
            }
        }

        // 8. Batch save dominator tree to database
        int batchSize = 10000;
        List<HeapStorageEngine.DominatorNode> nodes = new ArrayList<>(batchSize);
        for (int i = 0; i < n; i++) {
            int d = (semi[i] > 0 && dom[i] != superRoot) ? dom[i] : -1;
            nodes.add(new HeapStorageEngine.DominatorNode(i, d, retainedSizes[i]));
            if (nodes.size() >= batchSize) {
                storage.saveDominatorTreeBatch(nodes);
                nodes.clear();
            }
        }
        if (!nodes.isEmpty()) {
            storage.saveDominatorTreeBatch(nodes);
            nodes.clear();
        }

        log.info("Lengauer-Tarjan Dominator tree and retained sizes successfully persisted for {} objects.", n);
    }

    private static int eval(int v, int[] ancestor, int[] label, int[] semi) {
        if (ancestor[v] == -1) {
            return v;
        }
        compress(v, ancestor, label, semi);
        return label[v];
    }

    private static void compress(int v, int[] ancestor, int[] label, int[] semi) {
        if (ancestor[ancestor[v]] != -1) {
            compress(ancestor[v], ancestor, label, semi);
            if (semi[label[ancestor[v]]] < semi[label[v]]) {
                label[v] = label[ancestor[v]];
            }
            ancestor[v] = ancestor[ancestor[v]];
        }
    }
}
