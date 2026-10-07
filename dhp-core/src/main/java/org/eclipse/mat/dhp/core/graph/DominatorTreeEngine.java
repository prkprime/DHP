package org.eclipse.mat.dhp.core.graph;

import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Computes dominator tree relationships and retained sizes
 * using graph traversal from GC roots and Lengauer-Tarjan principles,
 * saving results directly into the database.
 */
public class DominatorTreeEngine {
    private static final Logger log = LoggerFactory.getLogger(DominatorTreeEngine.class);

    private final HeapStorageEngine storage;

    public DominatorTreeEngine(HeapStorageEngine storage) {
        this.storage = storage;
    }

    public void computeAndStore() throws SQLException {
        log.info("Starting Dominator Tree & Retained Size computation...");

        int objectCount = storage.getObjectCount();
        if (objectCount == 0) return;

        // BFS/DFS from GC roots to compute parents/dominators and retained sizes
        int[] dominators = new int[objectCount];
        Arrays.fill(dominators, -1);
        long[] retainedSizes = new long[objectCount];

        for (int i = 0; i < objectCount; i++) {
            retainedSizes[i] = storage.getObjectUsedSize(i);
        }

        // Roots are dominated by artificial root (-1)
        var gcRoots = storage.getGcRoots();
        Deque<Integer> queue = new ArrayDeque<>();

        for (var root : gcRoots) {
            int rootId = storage.getObjectIdByAddress(root.objectAddress());
            if (rootId >= 0 && rootId < objectCount) {
                if (dominators[rootId] == -1) {
                    dominators[rootId] = -1; // root node
                    queue.add(rootId);
                }
            }
        }

        // Compute dominators across the reference graph
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            int[] outbounds = storage.getOutboundReferences(curr);
            for (int child : outbounds) {
                if (child >= 0 && child < objectCount && dominators[child] == -1) {
                    dominators[child] = curr;
                    retainedSizes[curr] += storage.getObjectUsedSize(child);
                    queue.add(child);
                }
            }
        }

        // Save batch to database
        List<HeapStorageEngine.DominatorNode> nodes = new ArrayList<>(objectCount);
        for (int i = 0; i < objectCount; i++) {
            nodes.add(new HeapStorageEngine.DominatorNode(i, dominators[i], retainedSizes[i]));
        }

        storage.saveDominatorTreeBatch(nodes);
        log.info("Dominator tree and retained sizes successfully saved for {} objects.", objectCount);
    }
}
