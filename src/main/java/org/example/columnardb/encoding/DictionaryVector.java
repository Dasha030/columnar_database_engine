package org.example.columnardb.encoding;

import java.util.HashMap;
import java.util.Map;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Encoding;
import org.example.columnardb.vector.NullBitmap;

/**
 * Словникове кодування: масив унікальних значень (без NULL) + компактні коди + маска NULL.
 * Фільтри можна обчислювати над словником, а потім лише розкидати результат по кодах.
 */
public final class DictionaryVector implements ColumnVector {
    private final ColumnVector dictionary;
    private final PackedCodes codes;
    private final NullBitmap nulls;
    private Map<Object, Integer> lookup;

    public DictionaryVector(ColumnVector dictionary, PackedCodes codes, NullBitmap nulls) {
        Preconditions.notNull(dictionary, "dictionary");
        Preconditions.notNull(codes, "codes");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(dictionary.encoding() == Encoding.FLAT, "Dictionary must be FLAT");
        Preconditions.check(dictionary.nullCount() == 0, "Dictionary must not contain NULL");
        Preconditions.check(codes.size() == nulls.size(), "Codes length != bitmap size");
        for (int i = 0; i < codes.size(); i++) {
            if (nulls.isValid(i)) {
                Preconditions.check(codes.get(i) < dictionary.size(),
                        "Code " + codes.get(i) + " at " + i + " is outside dictionary of " + dictionary.size());
            }
        }
        this.dictionary = dictionary;
        this.codes = codes;
        this.nulls = nulls.copy();
    }

    public ColumnVector dictionary() {
        return dictionary;
    }

    public int dictionarySize() {
        return dictionary.size();
    }

    /** Код елемента i (визначений лише для не-NULL елементів). */
    public int code(int i) {
        return codes.get(i);
    }

    public int codeWidth() {
        return codes.width();
    }

    /** Код значення у словнику або -1, якщо значення відсутнє. */
    public int findCode(Object value) {
        if (lookup == null) {
            Map<Object, Integer> map = new HashMap<>();
            for (int d = 0; d < dictionary.size(); d++) {
                map.put(dictionary.getObject(d), d);
            }
            lookup = map;
        }
        Integer code = lookup.get(value);
        return code == null ? -1 : code;
    }

    @Override
    public DataType type() {
        return dictionary.type();
    }

    @Override
    public Encoding encoding() {
        return Encoding.DICTIONARY;
    }

    @Override
    public int size() {
        return codes.size();
    }

    @Override
    public boolean isNull(int i) {
        return nulls.isNull(i);
    }

    @Override
    public long getLong(int i) {
        return dictionary.getLong(codes.get(i));
    }

    @Override
    public double getDouble(int i) {
        return dictionary.getDouble(codes.get(i));
    }

    @Override
    public String getString(int i) {
        return dictionary.getString(codes.get(i));
    }

    @Override
    public int nullCount() {
        return nulls.countNulls();
    }

    @Override
    public String toString() {
        return "DictionaryVector{" + type() + ", size=" + size() + ", dict=" + dictionarySize() + "}";
    }
}
