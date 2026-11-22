package org.example.columnardb.engine;

import org.example.columnardb.Preconditions;

/** Стабільне сортування злиттям перестановки індексів 0..n-1 за довільним компаратором. */
public final class IndexSorter {
    @FunctionalInterface
    public interface IntComparator {
        int compare(int a, int b);
    }

    private IndexSorter() {
    }

    /** Повертає перестановку p, де cmp(p[i], p[i+1]) <= 0; рівні елементи зберігають вихідний порядок. */
    public static int[] sort(int n, IntComparator cmp) {
        Preconditions.check(n >= 0, "Negative size: " + n);
        Preconditions.notNull(cmp, "comparator");
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        mergeSort(perm, new int[n], 0, n, cmp);
        return perm;
    }

    private static void mergeSort(int[] a, int[] tmp, int lo, int hi, IntComparator cmp) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, cmp);
        mergeSort(a, tmp, mid, hi, cmp);
        if (cmp.compare(a[mid - 1], a[mid]) <= 0) {
            return; // половини вже впорядковані
        }
        int left = lo;
        int right = mid;
        int out = lo;
        while (left < mid && right < hi) {
            tmp[out++] = cmp.compare(a[right], a[left]) < 0 ? a[right++] : a[left++];
        }
        while (left < mid) {
            tmp[out++] = a[left++];
        }
        while (right < hi) {
            tmp[out++] = a[right++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }
}
