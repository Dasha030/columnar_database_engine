package org.example.columnardb.encoding;

import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Encoding;
import org.example.columnardb.vector.NullBitmap;

/**
 * Bit-Packing для INT64: frame-of-reference (мінімум) + фіксована ширина в бітах для різниці
 * value - min. Елементи щільно упаковані в long[], можуть перетинати межу слова.
 */
public final class BitPackedVector implements ColumnVector {
    private final long base;
    private final int bitWidth;
    private final long[] words;
    private final NullBitmap nulls;
    private final int size;

    public BitPackedVector(long base, int bitWidth, long[] words, int size, NullBitmap nulls) {
        Preconditions.check(bitWidth >= 0 && bitWidth <= 64, "Bit width out of range: " + bitWidth);
        Preconditions.notNull(words, "words");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(size == nulls.size(), "Size != bitmap size");
        Preconditions.check(words.length >= wordCount(size, bitWidth), "Packed buffer too short");
        this.base = base;
        this.bitWidth = bitWidth;
        this.words = words.clone();
        this.size = size;
        this.nulls = nulls.copy();
    }

    static int wordCount(int size, int bitWidth) {
        return (int) (((long) size * bitWidth + 63) >>> 6);
    }

    static void write(long[] words, long bitPos, int width, long value) {
        if (width == 0) {
            return;
        }
        int w = (int) (bitPos >>> 6);
        int off = (int) (bitPos & 63);
        words[w] |= value << off;
        if (off + width > 64) {
            words[w + 1] |= value >>> (64 - off);
        }
    }

    static long read(long[] words, long bitPos, int width) {
        if (width == 0) {
            return 0;
        }
        int w = (int) (bitPos >>> 6);
        int off = (int) (bitPos & 63);
        long result = words[w] >>> off;
        if (off + width > 64) {
            result |= words[w + 1] << (64 - off);
        }
        return width == 64 ? result : result & ((1L << width) - 1);
    }

    public int bitWidth() {
        return bitWidth;
    }

    public long base() {
        return base;
    }

    /** Розмір упакованих даних у байтах. */
    public int packedBytes() {
        return wordCount(size, bitWidth) * 8;
    }

    @Override
    public DataType type() {
        return DataType.INT64;
    }

    @Override
    public Encoding encoding() {
        return Encoding.BIT_PACKED;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isNull(int i) {
        return nulls.isNull(i);
    }

    @Override
    public long getLong(int i) {
        Preconditions.index(i, size);
        return base + read(words, (long) i * bitWidth, bitWidth);
    }

    @Override
    public int nullCount() {
        return nulls.countNulls();
    }

    @Override
    public String toString() {
        return "BitPackedVector{size=" + size + ", width=" + bitWidth + ", base=" + base + "}";
    }
}
