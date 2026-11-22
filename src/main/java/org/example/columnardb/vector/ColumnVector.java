package org.example.columnardb.vector;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.Preconditions;

/**
 * Імутабельний векторизований масив однотипних значень. Зовнішні оператори працюють лише
 * через цей інтерфейс, тому кодування (RLE, Dictionary, BitPacking) прозоре для виконувача.
 * Значення NULL-елемента через getXxx не визначене: спочатку перевіряйте {@link #isNull}.
 */
public interface ColumnVector {
    DataType type();

    Encoding encoding();

    int size();

    boolean isNull(int i);

    default long getLong(int i) {
        throw mismatch("getLong");
    }

    default double getDouble(int i) {
        throw mismatch("getDouble");
    }

    default String getString(int i) {
        throw mismatch("getString");
    }

    default ColumnarDbException mismatch(String method) {
        return new ColumnarDbException(method + " is not supported for " + type() + " vector");
    }

    /** Числове значення як double (для арифметики та AVG). */
    default double asDouble(int i) {
        return switch (type()) {
            case INT64 -> (double) getLong(i);
            case FLOAT64 -> getDouble(i);
            case STRING -> throw mismatch("asDouble");
        };
    }

    /** Значення у вигляді об'єкта (Long, Double, String) або null. */
    default Object getObject(int i) {
        if (isNull(i)) {
            return null;
        }
        return switch (type()) {
            case INT64 -> getLong(i);
            case FLOAT64 -> getDouble(i);
            case STRING -> getString(i);
        };
    }

    default int nullCount() {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (isNull(i)) {
                count++;
            }
        }
        return count;
    }

    /** Копія діапазону у FLAT-кодуванні. */
    default ColumnVector slice(int from, int length) {
        Preconditions.range(from, length, size());
        VectorBuilder builder = new VectorBuilder(type(), length);
        for (int i = 0; i < length; i++) {
            builder.appendFrom(this, from + i);
        }
        return builder.build();
    }

    /** Розпаковує вектор у FLAT; FLAT-вектор повертається без копіювання. */
    default ColumnVector flatten() {
        return encoding() == Encoding.FLAT ? this : slice(0, size());
    }
}
