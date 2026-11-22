package org.example.columnardb.encoding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.NullBitmap;
import org.example.columnardb.vector.VectorBuilder;

/** Словникове кодування: словник будується в порядку першої появи значень. */
public final class DictionaryEncoder {
    private DictionaryEncoder() {
    }

    public static DictionaryVector encode(ColumnVector input) {
        Preconditions.notNull(input, "input");
        int n = input.size();
        Map<Object, Integer> valueToCode = new HashMap<>();
        VectorBuilder dictionary = new VectorBuilder(input.type());
        int[] codes = new int[n];
        NullBitmap nulls = new NullBitmap(n);
        for (int i = 0; i < n; i++) {
            if (input.isNull(i)) {
                nulls.setNull(i);
                continue;
            }
            Object value = input.getObject(i);
            Integer code = valueToCode.get(value);
            if (code == null) {
                code = valueToCode.size();
                valueToCode.put(value, code);
                dictionary.appendFrom(input, i);
            }
            codes[i] = code;
        }
        return new DictionaryVector(dictionary.build(), PackedCodes.of(codes, valueToCode.size()), nulls);
    }

    public static ColumnVector decode(DictionaryVector encoded) {
        Preconditions.notNull(encoded, "encoded");
        VectorBuilder out = new VectorBuilder(encoded.type(), encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
            if (encoded.isNull(i)) {
                out.appendNull();
            } else {
                out.appendFrom(encoded.dictionary(), encoded.code(i));
            }
        }
        return out.build();
    }

    /** Кількість різних не-NULL значень. */
    public static int cardinality(ColumnVector input) {
        Set<Object> distinct = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            if (!input.isNull(i)) {
                distinct.add(input.getObject(i));
            }
        }
        return distinct.size();
    }
}
