package org.example.columnardb.vector;

import java.util.Arrays;
import org.example.columnardb.Preconditions;

/**
 * Бітова маска валідності на основі long[]: біт 1 = значення валідне, 0 = NULL.
 * Елемент i лежить у words[i >> 6], біт (1L << (i & 63)). Невикористані біти хвоста завжди 0.
 */
public final class NullBitmap {
    private final long[] words;
    private final int size;

    /** Створює маску, де всі {@code size} елементів валідні. */
    public NullBitmap(int size) {
        Preconditions.check(size >= 0, "Bitmap size must be non-negative: " + size);
        this.size = size;
        this.words = new long[(size + 63) >>> 6];
        Arrays.fill(words, -1L);
        int rem = size & 63;
        if (rem != 0) {
            words[words.length - 1] = (1L << rem) - 1;
        }
    }

    private NullBitmap(long[] words, int size) {
        this.words = words;
        this.size = size;
    }

    public static NullBitmap allNull(int size) {
        Preconditions.check(size >= 0, "Bitmap size must be non-negative: " + size);
        return new NullBitmap(new long[(size + 63) >>> 6], size);
    }

    public static NullBitmap fromNullFlags(boolean[] isNull) {
        Preconditions.notNull(isNull, "isNull");
        NullBitmap bitmap = new NullBitmap(isNull.length);
        for (int i = 0; i < isNull.length; i++) {
            if (isNull[i]) {
                bitmap.setNull(i);
            }
        }
        return bitmap;
    }

    public int size() {
        return size;
    }

    public boolean isNull(int i) {
        Preconditions.index(i, size);
        return (words[i >> 6] & (1L << (i & 63))) == 0;
    }

    public boolean isValid(int i) {
        return !isNull(i);
    }

    public void setNull(int i) {
        Preconditions.index(i, size);
        words[i >> 6] &= ~(1L << (i & 63));
    }

    public void setNotNull(int i) {
        Preconditions.index(i, size);
        words[i >> 6] |= (1L << (i & 63));
    }

    public int countValid() {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    public int countNulls() {
        return size - countValid();
    }

    public boolean hasNulls() {
        return countValid() != size;
    }

    /** Логічне І: елемент валідний лише якщо валідний в обох масках. */
    public NullBitmap and(NullBitmap other) {
        Preconditions.notNull(other, "other");
        Preconditions.check(other.size == size, "Bitmap size mismatch: " + size + " vs " + other.size);
        long[] result = new long[words.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = words[i] & other.words[i];
        }
        return new NullBitmap(result, size);
    }

    public NullBitmap copy() {
        return new NullBitmap(words.clone(), size);
    }

    public NullBitmap slice(int from, int length) {
        Preconditions.range(from, length, size);
        NullBitmap result = new NullBitmap(length);
        for (int i = 0; i < length; i++) {
            if (isNull(from + i)) {
                result.setNull(i);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NullBitmap other && other.size == size && Arrays.equals(words, other.words);
    }

    @Override
    public int hashCode() {
        return 31 * size + Arrays.hashCode(words);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NullBitmap[");
        for (int i = 0; i < Math.min(size, 64); i++) {
            sb.append(isNull(i) ? '0' : '1');
        }
        return sb.append(size > 64 ? "...]" : "]").toString();
    }
}
