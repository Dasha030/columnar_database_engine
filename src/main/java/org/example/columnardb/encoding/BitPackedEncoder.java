package org.example.columnardb.encoding;

import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Int64Vector;
import org.example.columnardb.vector.NullBitmap;

/** Кодувальник Bit-Packing для INT64-векторів. */
public final class BitPackedEncoder {
    private BitPackedEncoder() {
    }

    /** Мінімальна кількість бітів для беззнакового числа range (0 -> 0 бітів). */
    public static int bitsFor(long range) {
        return 64 - Long.numberOfLeadingZeros(range);
    }

    public static BitPackedVector encode(ColumnVector input) {
        Preconditions.notNull(input, "input");
        Preconditions.check(input.type() == DataType.INT64, "Bit-packing supports INT64 only");
        int n = input.size();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        NullBitmap nulls = new NullBitmap(n);
        for (int i = 0; i < n; i++) {
            if (input.isNull(i)) {
                nulls.setNull(i);
                continue;
            }
            long v = input.getLong(i);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min > max) { // порожній вектор або лише NULL
            min = 0;
            max = 0;
        }
        int width = bitsFor(max - min); // різниця як беззнакове число
        long[] words = new long[BitPackedVector.wordCount(n, width)];
        for (int i = 0; i < n; i++) {
            if (nulls.isValid(i)) {
                BitPackedVector.write(words, (long) i * width, width, input.getLong(i) - min);
            }
        }
        return new BitPackedVector(min, width, words, n, nulls);
    }

    public static ColumnVector decode(BitPackedVector encoded) {
        Preconditions.notNull(encoded, "encoded");
        long[] values = new long[encoded.size()];
        NullBitmap nulls = new NullBitmap(encoded.size());
        for (int i = 0; i < values.length; i++) {
            if (encoded.isNull(i)) {
                nulls.setNull(i);
            } else {
                values[i] = encoded.getLong(i);
            }
        }
        return new Int64Vector(values, nulls);
    }
}
