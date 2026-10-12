package org.eclipse.mat.dhp.core.graph;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.BitSet;

/**
 * Port of the way Eclipse MAT keeps unreachable objects (GarbageCleaner.markUnreachableAsGCRoots): objects that
 * cannot be reached from any GC root are promoted to artificial roots so that they still get dominators and retained
 * sizes. The selection is a deterministic multi-pass heuristic, which is replicated here step by step so that the
 * resulting dominator tree matches MAT's.
 *
 * <p>Object IDs of instances are assigned in address order in both MAT and DHP, so iterating unreachable IDs in
 * ascending order visits them in the same order as MAT does.</p>
 */
public final class UnreachableRoots {
    private static final int PASSES = 10;

    private UnreachableRoots() {}

    /** Returns the additional (artificial) root IDs, in the order MAT would choose them. Empty if none are needed. */
    public static int[] findExtraRoots(HeapGraph graph, int n, int[] gcRootIds) {
        BitSet reachable = new BitSet(n);
        int reachableCount = mark(graph, n, reachable, gcRootIds, gcRootIds.length);
        if (reachableCount >= n) {
            return new int[0];
        }

        // Inbound counts from unreachable objects only (reachable ones already marked their referents), saturating at 255
        int[] inbounds = new int[n];
        int[] buf = new int[256];
        for (int u = reachable.nextClearBit(0); u >= 0 && u < n; u = reachable.nextClearBit(u + 1)) {
            int deg = graph.outDegree(u);
            if (buf.length < deg) buf = new int[Math.max(deg, buf.length * 2)];
            graph.copyOutEdges(u, buf);
            for (int k = 0; k < deg; k++) {
                int out = buf[k];
                if (out != u && out >= 0 && out < n && inbounds[out] < 255) inbounds[out]++;
            }
        }

        IntArrayList extra = new IntArrayList();

        // First pass: objects that no other object refers to
        IntArrayList unref = new IntArrayList();
        for (int u = reachable.nextClearBit(0); u >= 0 && u < n; u = reachable.nextClearBit(u + 1)) {
            if (inbounds[u] == 0) {
                unref.add(u);
                extra.add(u);
            }
        }
        reachableCount += mark(graph, n, reachable, unref.toIntArray(), unref.size());

        // Remaining unreachable objects
        unref = new IntArrayList();
        for (int u = reachable.nextClearBit(0); u >= 0 && u < n; u = reachable.nextClearBit(u + 1)) {
            unref.add(u);
        }

        int[] single = new int[1];
        for (int pass = 0; pass < PASSES; pass++) {
            IntArrayList unref2 = new IntArrayList();
            int[] outbounds = new int[n];
            for (int i = 0; i < unref.size(); i++) {
                int u = unref.getInt(i);
                if (!reachable.get(u)) {
                    unref2.add(u);
                    int deg = graph.outDegree(u);
                    if (buf.length < deg) buf = new int[Math.max(deg, buf.length * 2)];
                    graph.copyOutEdges(u, buf);
                    for (int k = 0; k < deg; k++) {
                        int out = buf[k];
                        if (out != u && out >= 0 && out < n && !reachable.get(out) && outbounds[u] < 255) outbounds[u]++;
                    }
                }
            }
            unref = unref2;

            for (int i = 0; i < unref.size() && reachableCount < n; i++) {
                int u = selectRoot(graph, unref.getInt(i), pass, reachable, n, outbounds, inbounds);
                if (u >= 0) {
                    extra.add(u);
                    single[0] = u;
                    reachableCount += mark(graph, n, reachable, single, 1);
                }
            }
        }
        return extra.toIntArray();
    }

    private static int selectRoot(HeapGraph g, int ii, int pass, BitSet reachable, int n, int[] outbounds, int[] inbounds) {
        if (reachable.get(ii)) {
            return -1;
        }
        if (pass == 0) {
            // Cycle with a single entry each: no external way in, so it must be a root
            if (inbounds[ii] == 1) {
                int deg = g.outDegree(ii);
                for (int k = 0; k < deg; k++) {
                    int out = g.outEdge(ii, k);
                    if (out != ii && out >= 0 && out < n && !reachable.get(out)) {
                        if (inbounds[out] != 1) {
                            continue;
                        }
                        int deg2 = g.outDegree(out);
                        for (int j = 0; j < deg2; j++) {
                            if (g.outEdge(out, j) == ii) {
                                return ii;
                            }
                        }
                    }
                }
            }
            return -1;
        }
        boolean choose = outbounds[ii] > 0
                && (pass == PASSES - 1 || (inbounds[ii] > 1 && outbounds[ii] - inbounds[ii] >= PASSES - pass - 2));
        return choose ? ii : -1;
    }

    /** Marks everything reachable from the first {@code count} roots (roots included); returns how many were newly marked. */
    public static int mark(HeapGraph g, int n, BitSet reachable, int[] roots, int count) {
        final int[] starts = g.rawStarts();
        final int[][] chunks = g.rawChunks();
        final int shift = HeapGraph.RAW_CHUNK_SHIFT;
        final int mask = HeapGraph.RAW_CHUNK_MASK;
        int[] stack = new int[1024];
        int top = 0;
        int marked = 0;
        for (int i = 0; i < count; i++) {
            int r = roots[i];
            if (r >= 0 && r < n && !reachable.get(r)) {
                reachable.set(r);
                marked++;
                if (top == stack.length) stack = java.util.Arrays.copyOf(stack, top * 2);
                stack[top++] = r;
            }
        }
        while (top > 0) {
            int u = stack[--top];
            int s = starts[u];
            int[] src;
            int from;
            int to;
            if (s > 0) {
                src = chunks[s >>> shift];
                int off = s & mask;
                from = off + 1;
                to = off + 1 + src[off];
            } else if (s < 0) {
                src = g.hugeEdgesOf(u);
                from = 0;
                to = src.length;
            } else {
                continue;
            }
            for (int k = from; k < to; k++) {
                int v = src[k];
                if (v >= 0 && v < n && !reachable.get(v)) {
                    reachable.set(v);
                    marked++;
                    if (top == stack.length) stack = java.util.Arrays.copyOf(stack, top * 2);
                    stack[top++] = v;
                }
            }
        }
        return marked;
    }
}
