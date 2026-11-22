package org.example.columnardb.aggregate;

import java.util.Arrays;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.ValueComparator;
import org.example.columnardb.vector.VectorBuilder;

/** Фабрика та реалізації акумуляторів SUM, AVG, COUNT, MIN, MAX. NULL-значення ігноруються. */
final class Accumulators {
    private Accumulators() {
    }

    static Accumulator create(AggregateFunc func, DataType inputType) {
        return switch (func.kind()) {
            case SUM -> inputType == DataType.INT64 ? new SumLong() : new SumDouble();
            case AVG -> new Avg();
            case COUNT, COUNT_STAR -> new Count();
            case MIN -> new MinMax(inputType, true);
            case MAX -> new MinMax(inputType, false);
        };
    }

    private static int grow(int current, int needed) {
        return Math.max(needed, current * 2);
    }

    /** SUM для INT64 з контролем переповнення; порожня група дає NULL. */
    static final class SumLong extends Accumulator {
        private long[] sums = new long[16];
        private boolean[] seen = new boolean[16];

        @Override
        int capacity() {
            return sums.length;
        }

        @Override
        void ensureCapacity(int groups) {
            if (groups > sums.length) {
                sums = Arrays.copyOf(sums, grow(sums.length, groups));
                seen = Arrays.copyOf(seen, sums.length);
            }
        }

        @Override
        void update(int group, ColumnVector input, int row) {
            if (input.isNull(row)) {
                return;
            }
            try {
                sums[group] = Math.addExact(sums[group], input.getLong(row));
            } catch (ArithmeticException e) {
                throw new ColumnarDbException("SUM overflow in group " + group, e);
            }
            seen[group] = true;
        }

        @Override
        ColumnVector finish(int from, int to) {
            VectorBuilder out = new VectorBuilder(DataType.INT64);
            for (int g = from; g < to; g++) {
                if (seen[g]) {
                    out.appendLong(sums[g]);
                } else {
                    out.appendNull();
                }
            }
            return out.build();
        }
    }

    static final class SumDouble extends Accumulator {
        private double[] sums = new double[16];
        private boolean[] seen = new boolean[16];

        @Override
        int capacity() {
            return sums.length;
        }

        @Override
        void ensureCapacity(int groups) {
            if (groups > sums.length) {
                sums = Arrays.copyOf(sums, grow(sums.length, groups));
                seen = Arrays.copyOf(seen, sums.length);
            }
        }

        @Override
        void update(int group, ColumnVector input, int row) {
            if (input.isNull(row)) {
                return;
            }
            sums[group] += input.getDouble(row);
            seen[group] = true;
        }

        @Override
        ColumnVector finish(int from, int to) {
            VectorBuilder out = new VectorBuilder(DataType.FLOAT64);
            for (int g = from; g < to; g++) {
                if (seen[g]) {
                    out.appendDouble(sums[g]);
                } else {
                    out.appendNull();
                }
            }
            return out.build();
        }
    }

    /** AVG = сума (double) / кількість не-NULL значень. */
    static final class Avg extends Accumulator {
        private double[] sums = new double[16];
        private long[] counts = new long[16];

        @Override
        int capacity() {
            return sums.length;
        }

        @Override
        void ensureCapacity(int groups) {
            if (groups > sums.length) {
                sums = Arrays.copyOf(sums, grow(sums.length, groups));
                counts = Arrays.copyOf(counts, sums.length);
            }
        }

        @Override
        void update(int group, ColumnVector input, int row) {
            if (input.isNull(row)) {
                return;
            }
            sums[group] += input.asDouble(row);
            counts[group]++;
        }

        @Override
        ColumnVector finish(int from, int to) {
            VectorBuilder out = new VectorBuilder(DataType.FLOAT64);
            for (int g = from; g < to; g++) {
                if (counts[g] == 0) {
                    out.appendNull();
                } else {
                    out.appendDouble(sums[g] / counts[g]);
                }
            }
            return out.build();
        }
    }

    /** COUNT(col) рахує не-NULL; COUNT(*) (input == null) рахує всі рядки. */
    static final class Count extends Accumulator {
        private long[] counts = new long[16];

        @Override
        int capacity() {
            return counts.length;
        }

        @Override
        void ensureCapacity(int groups) {
            if (groups > counts.length) {
                counts = Arrays.copyOf(counts, grow(counts.length, groups));
            }
        }

        @Override
        void update(int group, ColumnVector input, int row) {
            if (input == null || !input.isNull(row)) {
                counts[group]++;
            }
        }

        @Override
        ColumnVector finish(int from, int to) {
            VectorBuilder out = new VectorBuilder(DataType.INT64);
            for (int g = from; g < to; g++) {
                out.appendLong(counts[g]);
            }
            return out.build();
        }
    }

    /** MIN/MAX для будь-якого типу: тримає найкраще значення групи у вигляді одноелементної комірки. */
    static final class MinMax extends Accumulator {
        private final DataType type;
        private final boolean min;
        private long[] longs;
        private double[] doubles;
        private String[] strings;
        private boolean[] seen = new boolean[16];

        MinMax(DataType type, boolean min) {
            this.type = type;
            this.min = min;
            switch (type) {
                case INT64 -> longs = new long[16];
                case FLOAT64 -> doubles = new double[16];
                case STRING -> strings = new String[16];
            }
        }

        @Override
        int capacity() {
            return seen.length;
        }

        @Override
        void ensureCapacity(int groups) {
            if (groups <= seen.length) {
                return;
            }
            int capacity = grow(seen.length, groups);
            seen = Arrays.copyOf(seen, capacity);
            switch (type) {
                case INT64 -> longs = Arrays.copyOf(longs, capacity);
                case FLOAT64 -> doubles = Arrays.copyOf(doubles, capacity);
                case STRING -> strings = Arrays.copyOf(strings, capacity);
            }
        }

        private boolean better(int cmp) {
            return min ? cmp < 0 : cmp > 0;
        }

        @Override
        void update(int group, ColumnVector input, int row) {
            if (input.isNull(row)) {
                return;
            }
            switch (type) {
                case INT64 -> {
                    long v = input.getLong(row);
                    if (!seen[group] || better(Long.compare(v, longs[group]))) {
                        longs[group] = v;
                    }
                }
                case FLOAT64 -> {
                    double v = input.getDouble(row);
                    if (!seen[group] || better(ValueComparator.compareDoubles(v, doubles[group]))) {
                        doubles[group] = v;
                    }
                }
                case STRING -> {
                    String v = input.getString(row);
                    if (!seen[group] || better(Integer.signum(v.compareTo(strings[group])))) {
                        strings[group] = v;
                    }
                }
            }
            seen[group] = true;
        }

        @Override
        ColumnVector finish(int from, int to) {
            VectorBuilder out = new VectorBuilder(type);
            for (int g = from; g < to; g++) {
                if (!seen[g]) {
                    out.appendNull();
                    continue;
                }
                switch (type) {
                    case INT64 -> out.appendLong(longs[g]);
                    case FLOAT64 -> out.appendDouble(doubles[g]);
                    case STRING -> out.appendString(strings[g]);
                }
            }
            return out.build();
        }
    }
}
