package org.example.columnardb.aggregate;

import java.util.Arrays;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.VectorBuilder;

/**
 * Відкрита хеш-таблиця (лінійне зондування) для ключів групування з кількох стовпчиків.
 * Ключі зберігаються в типізованих масивах, групи нумеруються в порядку першої появи.
 * Рівність ключів: NULL == NULL, double порівнюється за канонічними бітами (-0.0 == 0.0, NaN == NaN).
 */
final class GroupKeyTable {
    private static final int NULL_HASH = 0x2545F491;

    private final DataType[] types;
    private final long[][] longs;
    private final double[][] doubles;
    private final String[][] strings;
    private final boolean[][] nulls;
    private int[] hashes = new int[16];
    private int[] slots = new int[32]; // 0 = порожньо, інакше номер групи + 1
    private int groupCount;

    GroupKeyTable(DataType[] types) {
        this.types = types.clone();
        int k = types.length;
        longs = new long[k][];
        doubles = new double[k][];
        strings = new String[k][];
        nulls = new boolean[k][];
        for (int c = 0; c < k; c++) {
            nulls[c] = new boolean[hashes.length];
            switch (types[c]) {
                case INT64 -> longs[c] = new long[hashes.length];
                case FLOAT64 -> doubles[c] = new double[hashes.length];
                case STRING -> strings[c] = new String[hashes.length];
            }
        }
    }

    int groupCount() {
        return groupCount;
    }

    /** Місткість масивів груп (для діагностики та тестів). */
    int groupCapacity() {
        return hashes.length;
    }

    /** Кількість комірок хеш-таблиці (для діагностики та тестів). */
    int slotCapacity() {
        return slots.length;
    }

    /** Кількість зайнятих комірок; завжди дорівнює кількості груп. */
    int occupiedSlots() {
        int occupied = 0;
        for (int slot : slots) {
            if (slot != 0) {
                occupied++;
            }
        }
        return occupied;
    }

    /** Знаходить групу для рядка row або створює нову. */
    int groupFor(ColumnVector[] keys, int row) {
        Preconditions.check(keys.length == types.length, "Key column count mismatch");
        int hash = hashRow(keys, row);
        int mask = slots.length - 1;
        int pos = hash & mask;
        while (slots[pos] != 0) {
            int group = slots[pos] - 1;
            if (hashes[group] == hash && equalsRow(group, keys, row)) {
                return group;
            }
            pos = (pos + 1) & mask;
        }
        int group = groupCount++;
        ensureGroupCapacity(groupCount);
        store(group, keys, row);
        hashes[group] = hash;
        slots[pos] = group + 1;
        if (groupCount * 2 > slots.length) {
            rehash();
        }
        return group;
    }

    private void ensureGroupCapacity(int needed) {
        if (needed <= hashes.length) {
            return;
        }
        int capacity = hashes.length * 2;
        hashes = Arrays.copyOf(hashes, capacity);
        for (int c = 0; c < types.length; c++) {
            nulls[c] = Arrays.copyOf(nulls[c], capacity);
            switch (types[c]) {
                case INT64 -> longs[c] = Arrays.copyOf(longs[c], capacity);
                case FLOAT64 -> doubles[c] = Arrays.copyOf(doubles[c], capacity);
                case STRING -> strings[c] = Arrays.copyOf(strings[c], capacity);
            }
        }
    }

    private void rehash() {
        int[] bigger = new int[slots.length * 2];
        int mask = bigger.length - 1;
        for (int group = 0; group < groupCount; group++) {
            int pos = hashes[group] & mask;
            while (bigger[pos] != 0) {
                pos = (pos + 1) & mask;
            }
            bigger[pos] = group + 1;
        }
        slots = bigger;
    }

    private void store(int group, ColumnVector[] keys, int row) {
        for (int c = 0; c < types.length; c++) {
            if (keys[c].isNull(row)) {
                nulls[c][group] = true;
                continue;
            }
            switch (types[c]) {
                case INT64 -> longs[c][group] = keys[c].getLong(row);
                case FLOAT64 -> doubles[c][group] = canonical(keys[c].getDouble(row));
                case STRING -> strings[c][group] = keys[c].getString(row);
            }
        }
    }

    boolean equalsRow(int group, ColumnVector[] keys, int row) {
        for (int c = 0; c < types.length; c++) {
            boolean isNull = keys[c].isNull(row);
            if (isNull || nulls[c][group]) {
                if (isNull != nulls[c][group]) {
                    return false;
                }
                continue;
            }
            boolean same = switch (types[c]) {
                case INT64 -> longs[c][group] == keys[c].getLong(row);
                case FLOAT64 -> Double.doubleToLongBits(doubles[c][group])
                        == Double.doubleToLongBits(canonical(keys[c].getDouble(row)));
                case STRING -> strings[c][group].equals(keys[c].getString(row));
            };
            if (!same) {
                return false;
            }
        }
        return true;
    }

    private static double canonical(double d) {
        return d == 0.0 ? 0.0 : d; // зводить -0.0 до 0.0
    }

    static int mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) x;
    }

    int hashRow(ColumnVector[] keys, int row) {
        int h = 1;
        for (int c = 0; c < keys.length; c++) {
            int cell;
            if (keys[c].isNull(row)) {
                cell = NULL_HASH;
            } else {
                cell = switch (types[c]) {
                    case INT64 -> mix(keys[c].getLong(row));
                    case FLOAT64 -> mix(Double.doubleToLongBits(canonical(keys[c].getDouble(row))));
                    case STRING -> mix(keys[c].getString(row).hashCode());
                };
            }
            h = h * 31 + cell;
        }
        return mix(h);
    }

    /** Ключові стовпчики для груп [from, to). */
    ColumnVector[] materialize(int from, int to) {
        ColumnVector[] result = new ColumnVector[types.length];
        for (int c = 0; c < types.length; c++) {
            VectorBuilder builder = new VectorBuilder(types[c]);
            for (int g = from; g < to; g++) {
                if (nulls[c][g]) {
                    builder.appendNull();
                    continue;
                }
                switch (types[c]) {
                    case INT64 -> builder.appendLong(longs[c][g]);
                    case FLOAT64 -> builder.appendDouble(doubles[c][g]);
                    case STRING -> builder.appendString(strings[c][g]);
                }
            }
            result[c] = builder.build();
        }
        return result;
    }
}
