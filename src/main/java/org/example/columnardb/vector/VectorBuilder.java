package org.example.columnardb.vector;

import java.util.Arrays;
import org.example.columnardb.Preconditions;

/** Послідовна побудова FLAT-вектора заданого типу з підтримкою NULL. */
public final class VectorBuilder {
    private final DataType type;
    private long[] longs;
    private double[] doubles;
    private String[] strings;
    private boolean[] nullFlags;
    private int size;

    public VectorBuilder(DataType type) {
        this(type, 16);
    }

    public VectorBuilder(DataType type, int initialCapacity) {
        this.type = Preconditions.notNull(type, "type");
        int capacity = Math.max(initialCapacity, 4);
        this.nullFlags = new boolean[capacity];
        switch (type) {
            case INT64 -> longs = new long[capacity];
            case FLOAT64 -> doubles = new double[capacity];
            case STRING -> strings = new String[capacity];
        }
    }

    public DataType type() {
        return type;
    }

    public int size() {
        return size;
    }

    private void ensureCapacity() {
        if (size < nullFlags.length) {
            return;
        }
        int capacity = nullFlags.length * 2;
        nullFlags = Arrays.copyOf(nullFlags, capacity);
        switch (type) {
            case INT64 -> longs = Arrays.copyOf(longs, capacity);
            case FLOAT64 -> doubles = Arrays.copyOf(doubles, capacity);
            case STRING -> strings = Arrays.copyOf(strings, capacity);
        }
    }

    private void expect(DataType expected) {
        Preconditions.check(type == expected, "Cannot append " + expected + " to " + type + " builder");
    }

    public VectorBuilder appendLong(long value) {
        expect(DataType.INT64);
        ensureCapacity();
        longs[size++] = value;
        return this;
    }

    public VectorBuilder appendDouble(double value) {
        expect(DataType.FLOAT64);
        ensureCapacity();
        doubles[size++] = value;
        return this;
    }

    public VectorBuilder appendString(String value) {
        expect(DataType.STRING);
        if (value == null) {
            return appendNull();
        }
        ensureCapacity();
        strings[size++] = value;
        return this;
    }

    public VectorBuilder appendNull() {
        ensureCapacity();
        nullFlags[size++] = true;
        return this;
    }

    /** Додає елемент i вектора source (разом зі станом NULL). */
    public VectorBuilder appendFrom(ColumnVector source, int i) {
        Preconditions.check(source.type() == type, "Type mismatch: " + source.type() + " vs " + type);
        if (source.isNull(i)) {
            return appendNull();
        }
        return switch (type) {
            case INT64 -> appendLong(source.getLong(i));
            case FLOAT64 -> appendDouble(source.getDouble(i));
            case STRING -> appendString(source.getString(i));
        };
    }

    /** Додає Java-об'єкт: Number для числових типів, String для STRING, null для NULL. */
    public VectorBuilder appendObject(Object value) {
        if (value == null) {
            return appendNull();
        }
        switch (type) {
            case INT64 -> {
                Preconditions.check(value instanceof Long || value instanceof Integer
                        || value instanceof Short || value instanceof Byte, "Expected integral number, got " + value);
                return appendLong(((Number) value).longValue());
            }
            case FLOAT64 -> {
                Preconditions.check(value instanceof Number, "Expected number, got " + value);
                return appendDouble(((Number) value).doubleValue());
            }
            default -> {
                Preconditions.check(value instanceof String, "Expected string, got " + value);
                return appendString((String) value);
            }
        }
    }

    public ColumnVector build() {
        NullBitmap nulls = new NullBitmap(size);
        for (int i = 0; i < size; i++) {
            if (nullFlags[i]) {
                nulls.setNull(i);
            }
        }
        return switch (type) {
            case INT64 -> Int64Vector.trusted(Arrays.copyOf(longs, size), nulls);
            case FLOAT64 -> Float64Vector.trusted(Arrays.copyOf(doubles, size), nulls);
            case STRING -> new StringVector(Arrays.copyOf(strings, size), nulls);
        };
    }
}
