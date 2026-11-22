package org.example.columnardb.aggregate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/** Тести внутрішньої хеш-таблиці груп (той самий пакет, щоб бачити package-private API). */
class GroupKeyTableTest {
    private static final int NULL_HASH = 0x2545F491;

    private static GroupKeyTable intTable() {
        return new GroupKeyTable(new DataType[] {DataType.INT64});
    }

    private static ColumnVector[] one(ColumnVector v) {
        return new ColumnVector[] {v};
    }

    private static int refMix(long x) { // MurmurHash3 fmix64, усічений до int
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) x;
    }

    @Test
    void mixIsTheMurmur3FinalizerWithKnownVector() {
        assertEquals(0, GroupKeyTable.mix(0));
        assertEquals((int) 0xB456BCFC34C2CB2CL, GroupKeyTable.mix(1)); // відомий тестовий вектор fmix64(1)
        Random rnd = new Random(1);
        for (int i = 0; i < 2000; i++) {
            long v = i < 5 ? new long[] {-1, Long.MIN_VALUE, Long.MAX_VALUE, 4119438558L, 42}[i] : rnd.nextLong();
            assertEquals(refMix(v), GroupKeyTable.mix(v), "value " + v);
        }
    }

    @Test
    void hashOfIntegerKeyFollowsTheDocumentedFormula() {
        GroupKeyTable t = intTable();
        for (long v : new long[] {0, 1, 7, -3, Long.MAX_VALUE}) {
            assertEquals(refMix(31 + refMix(v)), t.hashRow(one(new Int64Vector(new long[] {v})), 0));
        }
    }

    @Test
    void hashCombinesColumnsInOrder() {
        GroupKeyTable t = new GroupKeyTable(new DataType[] {DataType.INT64, DataType.INT64});
        ColumnVector[] ab = {new Int64Vector(new long[] {3}), new Int64Vector(new long[] {9})};
        ColumnVector[] ba = {new Int64Vector(new long[] {9}), new Int64Vector(new long[] {3})};
        assertEquals(refMix((31 + refMix(3)) * 31 + refMix(9)), t.hashRow(ab, 0));
        assertNotEquals(t.hashRow(ab, 0), t.hashRow(ba, 0));
    }

    @Test
    void hashOfNullStringDoubleAndCanonicalZero() {
        GroupKeyTable nulls = intTable();
        assertEquals(refMix(31 + NULL_HASH), nulls.hashRow(one(Int64Vector.ofNullable((Long) null)), 0));
        GroupKeyTable strings = new GroupKeyTable(new DataType[] {DataType.STRING});
        assertEquals(refMix(31 + refMix("ab".hashCode())), strings.hashRow(one(new StringVector(new String[] {"ab"})), 0));
        GroupKeyTable doubles = new GroupKeyTable(new DataType[] {DataType.FLOAT64});
        ColumnVector zeros = new Float64Vector(new double[] {0.0, -0.0, Double.NaN});
        assertEquals(doubles.hashRow(one(zeros), 0), doubles.hashRow(one(zeros), 1));
        assertEquals(refMix(31 + refMix(Double.doubleToLongBits(Double.NaN))), doubles.hashRow(one(zeros), 2));
    }

    @Test
    void equalsRowComparesValuesPerType() {
        GroupKeyTable t = intTable();
        t.groupFor(one(new Int64Vector(new long[] {5})), 0);
        assertTrue(t.equalsRow(0, one(new Int64Vector(new long[] {5})), 0));
        assertFalse(t.equalsRow(0, one(new Int64Vector(new long[] {6})), 0));
        assertFalse(t.equalsRow(0, one(Int64Vector.ofNullable((Long) null)), 0));

        GroupKeyTable strings = new GroupKeyTable(new DataType[] {DataType.STRING});
        strings.groupFor(one(new StringVector(new String[] {"a"})), 0);
        assertTrue(strings.equalsRow(0, one(new StringVector(new String[] {"a"})), 0));
        assertFalse(strings.equalsRow(0, one(new StringVector(new String[] {"b"})), 0));

        GroupKeyTable doubles = new GroupKeyTable(new DataType[] {DataType.FLOAT64});
        doubles.groupFor(one(new Float64Vector(new double[] {Double.NaN})), 0);
        assertTrue(doubles.equalsRow(0, one(new Float64Vector(new double[] {Double.NaN})), 0));
        assertFalse(doubles.equalsRow(0, one(new Float64Vector(new double[] {1.0})), 0));
        GroupKeyTable zero = new GroupKeyTable(new DataType[] {DataType.FLOAT64});
        zero.groupFor(one(new Float64Vector(new double[] {-0.0})), 0);
        assertTrue(zero.equalsRow(0, one(new Float64Vector(new double[] {0.0})), 0));
    }

    @Test
    void nullGroupNeverEqualsZeroOrEmptyValue() {
        GroupKeyTable ints = intTable();
        ints.groupFor(one(Int64Vector.ofNullable((Long) null)), 0);
        assertFalse(ints.equalsRow(0, one(new Int64Vector(new long[] {0})), 0));
        assertTrue(ints.equalsRow(0, one(Int64Vector.ofNullable((Long) null)), 0));
        GroupKeyTable strings = new GroupKeyTable(new DataType[] {DataType.STRING});
        strings.groupFor(one(StringVector.ofNullable((String) null)), 0);
        assertFalse(strings.equalsRow(0, one(new StringVector(new String[] {""})), 0));
        GroupKeyTable valueGroup = new GroupKeyTable(new DataType[] {DataType.STRING});
        valueGroup.groupFor(one(new StringVector(new String[] {""})), 0);
        assertFalse(valueGroup.equalsRow(0, one(StringVector.ofNullable((String) null)), 0));
    }

    @Test
    void equalsRowChecksEveryKeyColumn() {
        GroupKeyTable t = new GroupKeyTable(new DataType[] {DataType.INT64, DataType.STRING});
        t.groupFor(new ColumnVector[] {new Int64Vector(new long[] {1}), new StringVector(new String[] {"x"})}, 0);
        assertTrue(t.equalsRow(0, new ColumnVector[] {new Int64Vector(new long[] {1}), new StringVector(new String[] {"x"})}, 0));
        assertFalse(t.equalsRow(0, new ColumnVector[] {new Int64Vector(new long[] {1}), new StringVector(new String[] {"y"})}, 0));
        assertFalse(t.equalsRow(0, new ColumnVector[] {new Int64Vector(new long[] {2}), new StringVector(new String[] {"x"})}, 0));
    }

    @Test
    void groupsAreNumberedInFirstSeenOrderAndReused() {
        GroupKeyTable t = intTable();
        ColumnVector v = new Int64Vector(new long[] {10, 20, 10, 30, 20});
        int[] expected = {0, 1, 0, 2, 1};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], t.groupFor(one(v), i));
        }
        assertEquals(3, t.groupCount());
    }

    @Test
    void capacitiesGrowExactlyWhenFull() {
        GroupKeyTable t = intTable();
        assertEquals(16, t.groupCapacity());
        assertEquals(32, t.slotCapacity());
        long[] values = new long[40];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        ColumnVector v = new Int64Vector(values);
        for (int i = 0; i < 16; i++) {
            t.groupFor(one(v), i);
            assertEquals(i + 1, t.occupiedSlots());
        }
        assertEquals(16, t.groupCapacity());  // 16 груп ще вміщаються
        assertEquals(32, t.slotCapacity());   // завантаження рівно 50% — без rehash
        t.groupFor(one(v), 16);
        assertEquals(32, t.groupCapacity());
        assertEquals(64, t.slotCapacity());
        assertEquals(17, t.occupiedSlots());
        for (int i = 17; i < 40; i++) {
            t.groupFor(one(v), i);
        }
        assertEquals(40, t.occupiedSlots());
        for (int i = 0; i < 40; i++) {
            assertEquals(i, t.groupFor(one(v), i)); // після rehash усі групи знаходяться
        }
    }

    @Test
    void keyColumnCountMustMatchSchema() {
        GroupKeyTable t = intTable();
        assertThrows(ColumnarDbException.class, () -> t.groupFor(new ColumnVector[0], 0));
        assertThrows(ColumnarDbException.class,
                () -> t.groupFor(new ColumnVector[] {new Int64Vector(new long[1]), new Int64Vector(new long[1])}, 0));
    }

    @Test
    void materializeReturnsRequestedGroupRange() {
        GroupKeyTable t = new GroupKeyTable(new DataType[] {DataType.INT64, DataType.STRING, DataType.FLOAT64});
        ColumnVector a = Int64Vector.ofNullable(1L, null, 3L);
        ColumnVector b = StringVector.ofNullable("x", "y", null);
        ColumnVector c = Float64Vector.ofNullable(0.5, 1.5, 2.5);
        for (int i = 0; i < 3; i++) {
            t.groupFor(new ColumnVector[] {a, b, c}, i);
        }
        ColumnVector[] all = t.materialize(0, 3);
        assertEquals(java.util.Arrays.asList(1L, null, 3L), Vectors.toList(all[0]));
        assertEquals(java.util.Arrays.asList("x", "y", null), Vectors.toList(all[1]));
        assertEquals(java.util.Arrays.asList(0.5, 1.5, 2.5), Vectors.toList(all[2]));
        ColumnVector[] tail = t.materialize(1, 3);
        assertEquals(2, tail[0].size());
        assertTrue(tail[0].isNull(0));
        assertEquals(3L, tail[0].getLong(1));
        assertEquals(0, t.materialize(2, 2)[1].size());
    }
}
