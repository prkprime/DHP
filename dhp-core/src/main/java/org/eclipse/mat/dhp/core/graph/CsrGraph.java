package org.eclipse.mat.dhp.core.graph;

/**
 * Compressed Sparse Row (CSR) representation of a directed graph.
 * Uses strictly primitive integer arrays with 0 object header overhead.
 * For 50M objects and 100M edges, footprint is strictly ~592 MB (vs ~2.1 GB for int[][]).
 */
public record CsrGraph(int[] head, int[] to) {

    public int vertexCount() {
        return head.length > 0 ? head.length - 1 : 0;
    }

    public int edgeCount() {
        return to.length;
    }

    public int degree(int u) {
        if (u < 0 || u >= head.length - 1) return 0;
        return head[u + 1] - head[u];
    }

    public int getEdge(int u, int index) {
        return to[head[u] + index];
    }

    public int edgeStart(int u) {
        return head[u];
    }

    public int edgeEnd(int u) {
        return head[u + 1];
    }
}
