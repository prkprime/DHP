package org.eclipse.mat.dhp.core.memory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * High-performance, zero-allocation in-place parallel sorting algorithm for primitive long[] arrays.
 * <p>
 * Unlike {@link java.util.Arrays#parallelSort(long[])} and OpenJDK's DualPivotQuicksort,
 * which allocate an auxiliary array of equal size (new long[N]) and trigger
 * OutOfMemoryErrors in tryMergeRuns on multi-gigabyte heap dumps,
 * this implementation partitions and sorts strictly in-place with ZERO heap buffer allocation.
 */
public final class InPlaceLongSort {

    private static final int INSERTION_SORT_THRESHOLD = 32;
    private static final int SEQUENTIAL_THRESHOLD = 65536;

    private InPlaceLongSort() {
    }

    /**
     * Sorts the specified range of the array in-place with guaranteed zero auxiliary array allocation.
     *
     * @param a the array to be sorted
     * @param fromIndex the index of the first element (inclusive)
     * @param toIndex the index of the last element (exclusive)
     * @param parallelism maximum parallel tasks/threads (1 for sequential)
     */
    public static void sort(long[] a, int fromIndex, int toIndex, int parallelism) {
        if (a == null || toIndex - fromIndex <= 1) {
            return;
        }
        int length = toIndex - fromIndex;
        if (parallelism <= 1 || length < SEQUENTIAL_THRESHOLD) {
            dualPivotQuicksort(a, fromIndex, toIndex - 1);
        } else {
            ForkJoinPool pool = ForkJoinPool.commonPool();
            pool.invoke(new ParallelSortTask(a, fromIndex, toIndex - 1));
        }
    }

    public static void sort(long[] a, int fromIndex, int toIndex) {
        sort(a, fromIndex, toIndex, Runtime.getRuntime().availableProcessors());
    }

    public static void sort(long[] a) {
        if (a != null) {
            sort(a, 0, a.length);
        }
    }

    private static final class ParallelSortTask extends RecursiveAction {
        private final long[] a;
        private final int low;
        private final int high;

        ParallelSortTask(long[] a, int low, int high) {
            this.a = a;
            this.low = low;
            this.high = high;
        }

        @Override
        protected void compute() {
            if (high - low < SEQUENTIAL_THRESHOLD) {
                dualPivotQuicksort(a, low, high);
                return;
            }

            int p = partition(a, low, high);
            invokeAll(
                    new ParallelSortTask(a, low, p - 1),
                    new ParallelSortTask(a, p + 1, high)
            );
        }
    }

    private static int partition(long[] a, int low, int high) {
        int mid = (low + high) >>> 1;

        // Median-of-three pivot selection
        if (a[low] > a[mid]) swap(a, low, mid);
        if (a[low] > a[high]) swap(a, low, high);
        if (a[mid] > a[high]) swap(a, mid, high);

        swap(a, mid, high - 1);
        long pivot = a[high - 1];

        int i = low;
        int j = high - 1;
        while (true) {
            while (a[++i] < pivot) ;
            while (a[--j] > pivot) ;
            if (i >= j) break;
            swap(a, i, j);
        }
        swap(a, i, high - 1);
        return i;
    }

    /**
     * In-place Dual-Pivot Quicksort (Yaroslavskiy algorithm) with zero auxiliary array allocation.
     */
    static void dualPivotQuicksort(long[] a, int low, int high) {
        if (low < high) {
            if (high - low < INSERTION_SORT_THRESHOLD) {
                insertionSort(a, low, high);
                return;
            }

            if (a[low] > a[high]) {
                swap(a, low, high);
            }
            long p = a[low];
            long q = a[high];

            int l = low + 1;
            int g = high - 1;
            int k = l;

            while (k <= g) {
                if (a[k] < p) {
                    swap(a, k, l);
                    l++;
                } else if (a[k] >= q) {
                    while (a[g] > q && k < g) {
                        g--;
                    }
                    swap(a, k, g);
                    g--;
                    if (a[k] < p) {
                        swap(a, k, l);
                        l++;
                    }
                }
                k++;
            }
            l--;
            g++;
            swap(a, low, l);
            swap(a, high, g);

            dualPivotQuicksort(a, low, l - 1);
            if (p < q) {
                dualPivotQuicksort(a, l + 1, g - 1);
            }
            dualPivotQuicksort(a, g + 1, high);
        }
    }

    private static void insertionSort(long[] a, int low, int high) {
        for (int i = low + 1; i <= high; i++) {
            long val = a[i];
            int j = i - 1;
            while (j >= low && a[j] > val) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = val;
        }
    }

    private static void swap(long[] a, int i, int j) {
        long t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
