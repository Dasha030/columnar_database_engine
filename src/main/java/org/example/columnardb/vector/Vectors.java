package org.example.columnardb.vector;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.Preconditions;

/** Допоміжні операції над векторами незалежно від їх кодування. */
public final class Vectors {
    private Vectors() {
    }

    public static ColumnVector of(DataType type, Object... values) {
        VectorBuilder builder = new VectorBuilder(type, values.length);
        for (Object v : values) {
            builder.appendObject(v);
        }
        return builder.build();
    }

    public static List<Object> toList(ColumnVector v) {
        List<Object> list = new ArrayList<>(v.size());
        for (int i = 0; i < v.size(); i++) {
            list.add(v.getObject(i));
        }
        return list;
    }

    /** Порівняння значень двох елементів: NULL == NULL; double порівнюється побітово (канонічний NaN). */
    public static boolean sameValue(ColumnVector a, int i, ColumnVector b, int j) {
        boolean na = a.isNull(i);
        boolean nb = b.isNull(j);
        if (na || nb) {
            return na && nb;
        }
        return switch (a.type()) {
            case INT64 -> a.getLong(i) == b.getLong(j);
            case FLOAT64 -> Double.doubleToLongBits(a.getDouble(i)) == Double.doubleToLongBits(b.getDouble(j));
            case STRING -> a.getString(i).equals(b.getString(j));
        };
    }

    public static boolean sameValue(ColumnVector v, int i, int j) {
        return sameValue(v, i, v, j);
    }

    /** Логічна рівність вмісту, байдуже яке кодування мають вектори. */
    public static boolean contentEquals(ColumnVector a, ColumnVector b) {
        if (a.type() != b.type() || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameValue(a, i, b, i)) {
                return false;
            }
        }
        return true;
    }

    /** Склеює вектори одного типу в один FLAT-вектор. */
    public static ColumnVector concat(DataType type, List<? extends ColumnVector> parts) {
        long total = 0;
        for (ColumnVector part : parts) {
            Preconditions.check(part.type() == type, "Type mismatch in concat: " + part.type());
            total += part.size();
        }
        Preconditions.check(total <= Integer.MAX_VALUE, "Concatenated vector too large");
        VectorBuilder builder = new VectorBuilder(type, (int) total);
        for (ColumnVector part : parts) {
            for (int i = 0; i < part.size(); i++) {
                builder.appendFrom(part, i);
            }
        }
        return builder.build();
    }

    /** Збирає елементи source за позиціями indices[from..to). */
    public static ColumnVector gather(ColumnVector source, int[] indices, int from, int to) {
        Preconditions.range(from, to - from, indices.length);
        VectorBuilder builder = new VectorBuilder(source.type(), to - from);
        for (int k = from; k < to; k++) {
            builder.appendFrom(source, indices[k]);
        }
        return builder.build();
    }

    /** Вектор довжини length з одного й того ж значення (null дозволено). */
    public static ColumnVector constant(DataType type, Object value, int length) {
        Preconditions.check(length >= 0, "Negative length: " + length);
        VectorBuilder builder = new VectorBuilder(type, length);
        for (int i = 0; i < length; i++) {
            builder.appendObject(value);
        }
        return builder.build();
    }

    static String describe(ColumnVector v) {
        StringBuilder sb = new StringBuilder(v.type() + "/" + v.encoding() + "[" + v.size() + "]{");
        for (int i = 0; i < Math.min(v.size(), 8); i++) {
            sb.append(i == 0 ? "" : ", ").append(v.getObject(i));
        }
        return sb.append(v.size() > 8 ? ", ...}" : "}").toString();
    }
}
