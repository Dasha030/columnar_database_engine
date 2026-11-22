package org.example.columnardb.encoding;

import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Encoding;

/** Евристичний вибір найвигіднішого кодування для вектора. */
public final class EncodingSelector {
    /** Мінімальна середня довжина серії, за якої обирається RLE. */
    public static final int MIN_AVG_RUN_LENGTH = 4;
    /** Словник вигідний, якщо унікальних значень не більше половини рядків. */
    public static final int MIN_ROWS_PER_DISTINCT = 2;
    /** Максимальна ширина біт-пакування, що ще дає суттєву економію. */
    public static final int MAX_PACK_WIDTH = 32;

    private EncodingSelector() {
    }

    public static Encoding choose(ColumnVector input) {
        Preconditions.notNull(input, "input");
        int n = input.size();
        if (n == 0) {
            return Encoding.FLAT;
        }
        int runs = RleEncoder.countRuns(input);
        if ((long) runs * MIN_AVG_RUN_LENGTH <= n) {
            return Encoding.RLE;
        }
        int distinct = DictionaryEncoder.cardinality(input);
        if ((long) distinct * MIN_ROWS_PER_DISTINCT <= n) {
            return Encoding.DICTIONARY;
        }
        if (input.type() == DataType.INT64 && BitPackedEncoder.encode(input).bitWidth() <= MAX_PACK_WIDTH) {
            return Encoding.BIT_PACKED;
        }
        return Encoding.FLAT;
    }

    /** Стискає вектор у вибране кодування (або повертає FLAT-версію). */
    public static ColumnVector compress(ColumnVector input) {
        return encodeWith(input, choose(input));
    }

    public static ColumnVector encodeWith(ColumnVector input, Encoding encoding) {
        Preconditions.notNull(encoding, "encoding");
        return switch (encoding) {
            case FLAT -> input.flatten();
            case RLE -> RleEncoder.encode(input);
            case DICTIONARY -> DictionaryEncoder.encode(input);
            case BIT_PACKED -> BitPackedEncoder.encode(input);
        };
    }
}
