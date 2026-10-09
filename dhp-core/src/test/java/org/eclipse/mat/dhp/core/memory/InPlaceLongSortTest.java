package org.eclipse.mat.dhp.core.memory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class InPlaceLongSortTest {

    @Test
    void testEmptyAndSmallArrays() {
        long[] empty = new long[0];
        InPlaceLongSort.sort(empty);
        assertThat(empty).isEmpty();

        long[] single = new long[]{42L};
        InPlaceLongSort.sort(single);
        assertThat(single).containsExactly(42L);

        long[] two = new long[]{100L, 50L};
        InPlaceLongSort.sort(two);
        assertThat(two).containsExactly(50L, 100L);

        long[] twoEqual = new long[]{100L, 100L};
        InPlaceLongSort.sort(twoEqual);
        assertThat(twoEqual).containsExactly(100L, 100L);
    }

    @Test
    void testAlreadySortedAndReverseSorted() {
        int N = 10_000;
        long[] sorted = new long[N];
        long[] reversed = new long[N];
        for (int i = 0; i < N; i++) {
            sorted[i] = i * 8L;
            reversed[i] = (N - i) * 8L;
        }

        InPlaceLongSort.sort(sorted);
        for (int i = 0; i < N; i++) {
            assertThat(sorted[i]).isEqualTo(i * 8L);
        }

        InPlaceLongSort.sort(reversed);
        for (int i = 0; i < N; i++) {
            assertThat(reversed[i]).isEqualTo((i + 1) * 8L);
        }
    }

    @Test
    void testAllIdenticalElements() {
        int N = 50_000;
        long[] identical = new long[N];
        Arrays.fill(identical, 0x12345678L);

        InPlaceLongSort.sort(identical);
        for (long v : identical) {
            assertThat(v).isEqualTo(0x12345678L);
        }
    }

    @Test
    void testRandomAndHeapPatternLargeArray() {
        int N = 1_000_000;
        long[] data = new long[N];
        long[] expected = new long[N];
        Random rnd = new Random(1337);

        long cur = 0x700000000L;
        for (int i = 0; i < N; i++) {
            if (rnd.nextInt(100) < 5) {
                cur = 0x700000000L + (long) rnd.nextInt(500) * 1024L;
            } else {
                cur += (rnd.nextInt(64) + 16);
            }
            data[i] = cur;
            expected[i] = cur;
        }

        Arrays.sort(expected);
        InPlaceLongSort.sort(data);

        assertThat(data).isEqualTo(expected);
    }

    @Test
    void testSequentialVsParallelParity() {
        int N = 200_000;
        long[] data1 = new long[N];
        long[] data2 = new long[N];
        Random rnd = new Random(999);

        for (int i = 0; i < N; i++) {
            long val = rnd.nextLong();
            data1[i] = val;
            data2[i] = val;
        }

        InPlaceLongSort.sort(data1, 0, N, 1); // 1 thread (sequential)
        InPlaceLongSort.sort(data2, 0, N, 4); // 4 threads (parallel)

        assertThat(data1).isEqualTo(data2);
    }
}
