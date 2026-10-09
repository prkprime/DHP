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

        // 1. Load outbound CSR and shallow sizes directly into retainedSizes buffer
        CsrGraph outGraph = storage.loadOutboundCsr(n);
        long[] retainedSizes = storage.loadAllObjectUsedSizes(n);

        // 2. Collect unique GC root object IDs
        Set<Integer> gcRootsSet = new LinkedHashSet<>();
        for (var root : storage.getGcRoots()) {
            int rootId = root.objectId() >= 0 ? root.objectId() : storage.getObjectIdByAddress(root.objectAddress());
            if (rootId >= 0 && rootId < n) {
                gcRootsSet.add(rootId);
            }
        }
        int[] gcRootIds = gcRootsSet.stream().mapToInt(Integer::intValue).toArray();
        log.info("Collected {} unique GC root objects for dominator analysis", gcRootIds.length);

        // 3. Build inHead and inTo CSR graph (with superRoot as predecessor to all GC roots)
        int[] inHead = new int[n + 1];
        for (int u = 0; u < n; u++) {
            int start = outGraph.edgeStart(u);
            int end = outGraph.edgeEnd(u);
            for (int e = start; e < end; e++) {
                int v = outGraph.to()[e];
                if (v >= 0 && v < n) {
                    inHead[v + 1]++;
                }
            }
        }
        for (int r : gcRootIds) {
            if (r >= 0 && r < n) {
                inHead[r + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            inHead[i + 1] += inHead[i];
        }
        int totalInEdges = inHead[n];
        int[] inTo = new int[totalInEdges];

        // Allocate scratch arrays (reused across phases to bound memory footprint)
        int[] dfsStack = new int[totalVertices];
        int[] edgePos = new int[totalVertices];

        // Temporarily reuse dfsStack as inPos
        int[] inPos = dfsStack;
        System.arraycopy(inHead, 0, inPos, 0, n);

        for (int r : gcRootIds) {
            if (r >= 0 && r < n) {
                inTo[inPos[r]++] = superRoot;
            }
        }
        for (int u = 0; u < n; u++) {
            int start = outGraph.edgeStart(u);
            int end = outGraph.edgeEnd(u);
            for (int e = start; e < end; e++) {
                int v = outGraph.to()[e];
                if (v >= 0 && v < n) {
                    inTo[inPos[v]++] = u;
                }
            }
        }
        // Reset dfsStack before DFS traversal
        Arrays.fill(dfsStack, 0);

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
                int start = outGraph.edgeStart(u);
                int end = outGraph.edgeEnd(u);
                if (start + e < end) {
                    w = outGraph.to()[start + e];
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
        log.info("DFS complete: reached {} of {} heap objects from GC roots", dfsCount - 1, n);

        // Outbound graph is no longer needed: release memory immediately
        outGraph = null;

        // 6. Lengauer-Tarjan algorithm: compute semi-dominators and dominators
        // Reuse dfsStack and edgePos for bucketHead and bucketNext
        int[] bucketHead = dfsStack;
        int[] bucketNext = edgePos;
        Arrays.fill(bucketHead, -1);

        for (int i = dfsCount; i >= 2; i--) {
            int w = vertex[i];

            int inStart = inHead[w];
            int inEnd = inHead[w + 1];
            for (int idx = inStart; idx < inEnd; idx++) {
                int v = inTo[idx];
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

        // Inbound graph is no longer needed: release memory immediately
        inHead = null;
        inTo = null;

        // 7. Retained size calculation: build dominator tree children and post-order traversal
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

        // 8. Batch save dominator tree to database
        int batchSize = 50000;
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

        // Build Dominator Tree bulk indexes
        storage.finishDominatorTree();

        log.info("Lengauer-Tarjan Dominator tree and retained sizes successfully persisted for {} objects.", n);
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
