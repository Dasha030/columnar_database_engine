package org.example.columnardb.vector;

import org.example.columnardb.Preconditions;

/** Нестиснутий вектор long[] + NullBitmap. */
public final class Int64Vector implements ColumnVector {
    private final long[] values;
    private final NullBitmap nulls;

    public Int64Vector(long[] values, NullBitmap nulls) {
        Preconditions.notNull(values, "values");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(values.length == nulls.size(),
                "Values length " + values.length + " != bitmap size " + nulls.size());
        this.values = values.clone();
        this.nulls = nulls.copy();
    }

    public Int64Vector(long[] values) {
        this(values, new NullBitmap(Preconditions.notNull(values, "values").length));
    }

    private Int64Vector(long[] values, NullBitmap nulls, boolean trusted) {
        this.values = values;
        this.nulls = nulls;
    }

    /** Без копіювання: масиви належать новому вектору (внутрішнє використання). */
    static Int64Vector trusted(long[] values, NullBitmap nulls) {
        return new Int64Vector(values, nulls, true);
    }

    public static Int64Vector ofNullable(Long... items) {
        long[] values = new long[items.length];
        NullBitmap nulls = new NullBitmap(items.length);
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                nulls.setNull(i);
            } else {
                values[i] = items[i];
            }
        }
        return new Int64Vector(values, nulls, true);
    }

    @Override
    public DataType type() {
        return DataType.INT64;
    }

    @Override
    public Encoding encoding() {
        return Encoding.FLAT;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean isNull(int i) {
        return nulls.isNull(i);
    }

    @Override
    public long getLong(int i) {
        Preconditions.index(i, values.length);
        return values[i];
    }

    @Override
    public int nullCount() {
        return nulls.countNulls();
    }

    public long[] toArray() {
        return values.clone();
    }

    @Override
    public String toString() {
        return Vectors.describe(this);
    }
}
