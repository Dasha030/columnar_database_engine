package org.example.columnardb.vector;

import org.example.columnardb.Preconditions;

/** Нестиснутий вектор double[] + NullBitmap. */
public final class Float64Vector implements ColumnVector {
    private final double[] values;
    private final NullBitmap nulls;

    public Float64Vector(double[] values, NullBitmap nulls) {
        Preconditions.notNull(values, "values");
        Preconditions.notNull(nulls, "nullBitmap");
        Preconditions.check(values.length == nulls.size(),
                "Values length " + values.length + " != bitmap size " + nulls.size());
        this.values = values.clone();
        this.nulls = nulls.copy();
    }

    public Float64Vector(double[] values) {
        this(values, new NullBitmap(Preconditions.notNull(values, "values").length));
    }

    private Float64Vector(double[] values, NullBitmap nulls, boolean trusted) {
        this.values = values;
        this.nulls = nulls;
    }

    /** Без копіювання: масиви належать новому вектору (внутрішнє використання). */
    static Float64Vector trusted(double[] values, NullBitmap nulls) {
        return new Float64Vector(values, nulls, true);
    }

    public static Float64Vector ofNullable(Double... items) {
        double[] values = new double[items.length];
        NullBitmap nulls = new NullBitmap(items.length);
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                nulls.setNull(i);
            } else {
                values[i] = items[i];
            }
        }
        return new Float64Vector(values, nulls, true);
    }

    @Override
    public DataType type() {
        return DataType.FLOAT64;
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
    public double getDouble(int i) {
        Preconditions.index(i, values.length);
        return values[i];
    }

    @Override
    public int nullCount() {
        return nulls.countNulls();
    }

    public double[] toArray() {
        return values.clone();
    }

    @Override
    public String toString() {
        return Vectors.describe(this);
    }
}
