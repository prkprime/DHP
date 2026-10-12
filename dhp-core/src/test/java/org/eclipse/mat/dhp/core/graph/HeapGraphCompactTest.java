package org.eclipse.mat.dhp.core.graph;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.eclipse.mat.dhp.core.memory.SortedAddressToIdMap;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;

class HeapGraphCompactTest {

    /** ids: 0 = system loader, 1 = a class, 2..5 = instances at 0x100..0x400 */
    private static HeapGraph sample() {
        Long2IntOpenHashMap classes = new Long2IntOpenHashMap();
        classes.put(0L, 0);
        classes.put(0xC1A55L, 1);
        var map = new SortedAddressToIdMap(classes, new long[]{0x100, 0x200, 0x300, 0x400}, 2);
        var g = new HeapGraph(6, 2, map);
        g.setObject(0, 1, 32, 0, false);
        g.setObject(1, 1, 16, 0, false);
        g.setObject(2, 1, 24, 10, false);
        g.setObject(3, 1, 40, 20, true);
        g.setObject(4, 1, 24, 30, false);
        g.setObject(5, 1, 48, 40, true);
        g.setEdges(2, new int[]{1, 3}, 2);
        g.setEdges(3, new int[]{1}, 1);
        g.setEdges(4, new int[]{1, 5}, 2);
        g.setEdges(5, new int[]{1, 3}, 2);
        return g;
    }

    @Test
    void removesUnreachableObjectsAndRenumbersInAscendingOrder() {
        HeapGraph g = sample();
        BitSet reachable = new BitSet();
        reachable.set(0); reachable.set(1); reachable.set(3); reachable.set(5); // 2 and 4 are garbage

        g.compact(reachable);

        assertThat(g.objectCount()).isEqualTo(4);
        assertThat(g.newIdOf(3)).isEqualTo(2);
        assertThat(g.newIdOf(5)).isEqualTo(3);
        assertThat(g.newIdOf(2)).isEqualTo(-1);
        assertThat(g.newIdOf(4)).isEqualTo(-1);

        // columns follow the survivors, class ids are remapped
        assertThat(g.addressOf(2)).isEqualTo(0x200);
        assertThat(g.addressOf(3)).isEqualTo(0x400);
        assertThat(g.addressOf(1)).isEqualTo(0xC1A55L);
        assertThat(g.usedSize(2)).isEqualTo(40);
        assertThat(g.usedSize(3)).isEqualTo(48);
        assertThat(g.isArray(2)).isTrue();
        assertThat(g.isArray(3)).isTrue();
        assertThat(g.filePosition(3)).isEqualTo(40);
        assertThat(g.classObjId(2)).isEqualTo(1);

        // edges are rewritten to the new ids (old 5 -> [1, 3] becomes new 3 -> [1, 2])
        int[] e = new int[4];
        assertThat(g.copyOutEdges(2, e)).isEqualTo(1);
        assertThat(e[0]).isEqualTo(1);
        assertThat(g.copyOutEdges(3, e)).isEqualTo(2);
        assertThat(e[0]).isEqualTo(1);
        assertThat(e[1]).isEqualTo(2);
        assertThat(g.edgeCount()).isEqualTo(3);
    }

    @Test
    void shiftsAllIdsDownWhenTheFirstObjectIsRemoved() {
        HeapGraph g = sample();
        BitSet reachable = new BitSet();
        reachable.set(1); reachable.set(2); reachable.set(3); // the system loader (0) is unreachable, so ids shift down
        reachable.set(4); reachable.set(5);

        g.compact(reachable);

        assertThat(g.objectCount()).isEqualTo(5);
        assertThat(g.newIdOf(1)).isEqualTo(0);
        assertThat(g.addressOf(0)).isEqualTo(0xC1A55L);
        assertThat(g.addressOf(1)).isEqualTo(0x100);
        assertThat(g.classObjId(1)).isEqualTo(0);
    }

    @Test
    void unreachableRootsHeuristicPromotesUnreferencedGarbageToRoots() {
        HeapGraph g = sample();
        // roots: only the class (1). 2 -> {1,3}, 3 -> {1}, 4 -> {1,5}, 5 -> {1,3}: 2 and 4 have no referrers.
        int[] extra = UnreachableRoots.findExtraRoots(g, 6, new int[]{1});
        assertThat(extra).contains(2, 4);
        // everything is covered once those roots are added
        BitSet all = new BitSet();
        int[] roots = new int[1 + extra.length];
        roots[0] = 1;
        System.arraycopy(extra, 0, roots, 1, extra.length);
        assertThat(UnreachableRoots.mark(g, 6, all, roots, roots.length)).isGreaterThanOrEqualTo(5);
    }
}
