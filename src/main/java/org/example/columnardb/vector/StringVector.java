package org.example.columnardb.vector;

import java.nio.charset.StandardCharsets;
import org.example.columnardb.Preconditions;

/**
 * Рядковий вектор за схемою Arrow Variable-Size Binary: суцільний UTF-8 буфер
 * {@code valueBuffer} і масив зсувів {@code offsetBuffer} довжиною N+1.
 */
public final class StringVector implements ColumnVector {
    private final byte[] valueBuffer;
    private final int[] offsetBuffer;
    private final NullBitmap nulls;

    public StringVector(String[] values, NullBitmap nulls) {
        Preconditions.notNull(values, "values");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(values.length == nulls.size(),
                "Values length " + values.length + " != bitmap size " + nulls.size());
        byte[][] encoded = new byte[values.length][];
        long total = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                Preconditions.check(nulls.isNull(i), "Null string at " + i + " is marked valid");
                encoded[i] = new byte[0];
            } else {
                encoded[i] = values[i].getBytes(StandardCharsets.UTF_8);
            }
            total += encoded[i].length;
        }
        Preconditions.check(total <= Integer.MAX_VALUE, "String data exceeds 2 GiB");
        this.valueBuffer = new byte[(int) total];
        this.offsetBuffer = new int[values.length + 1];
        int pos = 0;
        for (int i = 0; i < values.length; i++) {
            System.arraycopy(encoded[i], 0, valueBuffer, pos, encoded[i].length);
            pos += encoded[i].length;
            offsetBuffer[i + 1] = pos;
        }
        this.nulls = nulls.copy();
    }

    /** Створює вектор із готових Arrow-буферів, перевіряючи їх цілісність. */
    public StringVector(byte[] valueBuffer, int[] offsetBuffer, NullBitmap nulls) {
        Preconditions.notNull(valueBuffer, "valueBuffer");
        Preconditions.notNull(offsetBuffer, "offsetBuffer");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(offsetBuffer.length == nulls.size() + 1,
                "Offset buffer must have N+1 entries, got " + offsetBuffer.length);
        Preconditions.check(offsetBuffer[0] == 0, "First offset must be 0");
        for (int i = 1; i < offsetBuffer.length; i++) {
            Preconditions.check(offsetBuffer[i] >= offsetBuffer[i - 1], "Offsets must be non-decreasing at " + i);
        }
        Preconditions.check(offsetBuffer[offsetBuffer.length - 1] == valueBuffer.length,
                "Last offset must equal value buffer length");
        this.valueBuffer = valueBuffer.clone();
        this.offsetBuffer = offsetBuffer.clone();
        this.nulls = nulls.copy();
    }

    public StringVector(String[] values) {
        this(values, new NullBitmap(Preconditions.notNull(values, "values").length));
    }

    public static StringVector ofNullable(String... items) {
        NullBitmap nulls = new NullBitmap(items.length);
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                nulls.setNull(i);
            }
        }
        return new StringVector(items, nulls);
    }

    @Override
    public DataType type() {
        return DataType.STRING;
    }

    @Override
    public Encoding encoding() {
        return Encoding.FLAT;
    }

    @Override
    public int size() {
        return offsetBuffer.length - 1;
    }

    @Override
    public boolean isNull(int i) {
        return nulls.isNull(i);
    }

    @Override
    public String getString(int i) {
        Preconditions.index(i, size());
        return new String(valueBuffer, offsetBuffer[i], offsetBuffer[i + 1] - offsetBuffer[i],
                StandardCharsets.UTF_8);
    }

    /** Довжина UTF-8 представлення елемента в байтах. */
    public int byteLength(int i) {
        Preconditions.index(i, size());
        return offsetBuffer[i + 1] - offsetBuffer[i];
    }

    @Override
    public int nullCount() {
        return nulls.countNulls();
    }

    @Override
    public String toString() {
        return Vectors.describe(this);
    }
}
