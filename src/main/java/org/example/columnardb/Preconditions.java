package org.example.columnardb;

/** Централізовані перевірки аргументів. Усі помилки перетворюються на {@link ColumnarDbException}. */
public final class Preconditions {
    private Preconditions() {
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new ColumnarDbException(message);
        }
    }

    public static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new ColumnarDbException(name + " must not be null");
        }
        return value;
    }

    public static void index(int i, int size) {
        if (i < 0 || i >= size) {
            throw new ColumnarDbException("Index " + i + " out of bounds for size " + size);
        }
    }

    /** Перевіряє, що [from, from+length) лежить усередині [0, size). */
    public static void range(int from, int length, int size) {
        if (from < 0 || length < 0 || from > size - length) {
            throw new ColumnarDbException(
                    "Range [" + from + ", +" + length + ") out of bounds for size " + size);
        }
    }
}
