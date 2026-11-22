package org.example.columnardb.batch;

import java.util.Arrays;
import org.example.columnardb.Preconditions;

/**
 * Вектор вибору: суворо зростаючі індекси рядків батчу, що пройшли фільтр. Дозволяє
 * наступним операторам читати дані без копіювання та алокацій під час фільтрації.
 */
public final class SelectionVector {
    private static final SelectionVector EMPTY = new SelectionVector(new int[0], 0);

    private final int[] indices;
    private final int length;

    public SelectionVector(int[] indices, int length) {
        Preconditions.notNull(indices, "indices");
        Preconditions.check(length >= 0 && length <= indices.length,
                "Length " + length + " outside [0, " + indices.length + "]");
        for (int i = 0; i < length; i++) {
            Preconditions.check(indices[i] >= 0, "Negative index at " + i);
            Preconditions.check(i == 0 || indices[i] > indices[i - 1], "Indices must be strictly increasing at " + i);
        }
        this.indices = indices;
        this.length = length;
    }

    /** Вибір усіх рядків 0..rowCount-1. */
    public static SelectionVector all(int rowCount) {
        Preconditions.check(rowCount >= 0, "Negative row count: " + rowCount);
        int[] identity = new int[rowCount];
        for (int i = 0; i < rowCount; i++) {
            identity[i] = i;
        }
        return new SelectionVector(identity, rowCount);
    }

    public static SelectionVector empty() {
        return EMPTY;
    }

    public int size() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public int getIndex(int i) {
        Preconditions.index(i, length);
        return indices[i];
    }

    public int[] toArray() {
        return Arrays.copyOf(indices, length);
    }

    /** Перетин двох векторів вибору злиттям за O(n + m). */
    public SelectionVector intersect(SelectionVector other) {
        Preconditions.notNull(other, "other");
        int[] result = new int[Math.min(length, other.length)];
        int a = 0;
        int b = 0;
        int n = 0;
        while (a < length && b < other.length) {
            if (indices[a] == other.indices[b]) {
                result[n++] = indices[a];
                a++;
                b++;
            } else if (indices[a] < other.indices[b]) {
                a++;
            } else {
                b++;
            }
        }
        return new SelectionVector(result, n);
    }

    public SelectionVector union(SelectionVector other) {
        Preconditions.notNull(other, "other");
        int[] result = new int[length + other.length];
        int a = 0;
        int b = 0;
        int n = 0;
        while (a < length || b < other.length) {
            if (b == other.length || a < length && indices[a] < other.indices[b]) {
                result[n++] = indices[a++];
            } else if (a == length || other.indices[b] < indices[a]) {
                result[n++] = other.indices[b++];
            } else {
                result[n++] = indices[a];
                a++;
                b++;
            }
        }
        return new SelectionVector(result, n);
    }

    /** Доповнення до повного набору рядків 0..totalRows-1. */
    public SelectionVector complement(int totalRows) {
        Preconditions.check(totalRows >= 0, "Negative row count: " + totalRows);
        Preconditions.check(length == 0 || indices[length - 1] < totalRows, "Selection exceeds row count");
        int[] result = new int[totalRows - length];
        int n = 0;
        int k = 0;
        for (int row = 0; row < totalRows; row++) {
            if (k < length && indices[k] == row) {
                k++;
            } else {
                result[n++] = row;
            }
        }
        return new SelectionVector(result, n);
    }

    /** Перші count елементів (або всі, якщо їх менше). */
    public SelectionVector head(int count) {
        Preconditions.check(count >= 0, "Negative count: " + count);
        return new SelectionVector(indices, Math.min(count, length));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SelectionVector other && other.length == length
                && Arrays.equals(indices, 0, length, other.indices, 0, length);
    }

    @Override
    public int hashCode() {
        int h = length;
        for (int i = 0; i < length; i++) {
            h = 31 * h + indices[i];
        }
        return h;
    }

    @Override
    public String toString() {
        return "SelectionVector" + Arrays.toString(toArray());
    }
}
