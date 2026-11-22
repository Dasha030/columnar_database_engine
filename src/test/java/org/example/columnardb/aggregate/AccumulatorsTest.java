package org.example.columnardb.aggregate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.corpus.Fixtures;
import org.example.columnardb.engine.ScanOperator;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class AccumulatorsTest {

    private static List<Accumulator> allKinds() {
        return List.of(
                Accumulators.create(AggregateFunc.sum("x", "a"), DataType.INT64),
                Accumulators.create(AggregateFunc.sum("x", "a"), DataType.FLOAT64),
                Accumulators.create(AggregateFunc.avg("x", "a"), DataType.INT64),
                Accumulators.create(AggregateFunc.count("x", "a"), DataType.INT64),
                Accumulators.create(AggregateFunc.countStar("a"), DataType.INT64),
                Accumulators.create(AggregateFunc.min("x", "a"), DataType.INT64),
                Accumulators.create(AggregateFunc.max("x", "a"), DataType.FLOAT64),
                Accumulators.create(AggregateFunc.min("x", "a"), DataType.STRING));
    }

    @Test
    void capacityDoublesOnlyWhenExceeded() {
        for (Accumulator acc : allKinds()) {
            String name = acc.getClass().getSimpleName();
            assertEquals(16, acc.capacity(), name);
            acc.ensureCapacity(1);
            acc.ensureCapacity(16);
            assertEquals(16, acc.capacity(), name + " must not grow for exactly 16 groups");
            acc.ensureCapacity(17);
            assertEquals(32, acc.capacity(), name + " must double");
            acc.ensureCapacity(100);
            assertEquals(100, acc.capacity(), name + " must grow to the requested size when doubling is not enough");
        }
    }

    @Test
    void minAndMaxKeepTheFirstOfEqualValues() {
        // 0.0 і -0.0 рівні за порівнянням, але відрізняються знаком: перемагає перше значення
        ColumnVector zeros = new Float64Vector(new double[] {0.0, -0.0});
        ColumnVector negFirst = new Float64Vector(new double[] {-0.0, 0.0});
        for (boolean min : new boolean[] {true, false}) {
            AggregateFunc f = min ? AggregateFunc.min("x", "r") : AggregateFunc.max("x", "r");
            Accumulator acc = Accumulators.create(f, DataType.FLOAT64);
            acc.ensureCapacity(1);
            acc.update(0, zeros, 0);
            acc.update(0, zeros, 1);
            assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(acc.finish(0, 1).getDouble(0)));
            Accumulator acc2 = Accumulators.create(f, DataType.FLOAT64);
            acc2.ensureCapacity(1);
            acc2.update(0, negFirst, 0);
            acc2.update(0, negFirst, 1);
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(acc2.finish(0, 1).getDouble(0)));
        }
    }

    @Test
    void finishCoversOnlyRequestedGroups() {
        Accumulator count = Accumulators.create(AggregateFunc.countStar("n"), DataType.INT64);
        count.ensureCapacity(3);
        count.update(0, null, 0);
        count.update(1, null, 0);
        count.update(1, null, 0);
        ColumnVector tail = count.finish(1, 3);
        assertEquals(2, tail.size());
        assertEquals(2, tail.getLong(0));
        assertEquals(0, tail.getLong(1));
    }

    @Test
    void dictionaryKeyHelperRecognisesOnlySingleDictionaryKeys() {
        ColumnVector dict = org.example.columnardb.encoding.DictionaryEncoder.encode(new StringVector(new String[] {"a", "b"}));
        ColumnVector flat = new StringVector(new String[] {"a", "b"});
        assertSame(dict, HashAggregateOperator.dictionaryKey(new ColumnVector[] {dict}));
        assertNull(HashAggregateOperator.dictionaryKey(new ColumnVector[] {flat}));
        assertNull(HashAggregateOperator.dictionaryKey(new ColumnVector[] {dict, dict}));
        assertNull(HashAggregateOperator.dictionaryKey(new ColumnVector[0]));
    }

    @Test
    void operatorRequiresGroupKeysOrFunctions() {
        InMemoryTable t = Fixtures.table(Fixtures.r(1L, 1.0, "x"));
        ColumnarDbException e = assertThrows(ColumnarDbException.class,
                () -> new HashAggregateOperator(new ScanOperator(t), List.of(), List.of()));
        assertTrue(e.getMessage().contains("GROUP BY"), e.getMessage());
        assertNotNull(new HashAggregateOperator(new ScanOperator(t), List.of("s"), List.of()));
        assertNotNull(new HashAggregateOperator(new ScanOperator(t), List.of(), List.of(AggregateFunc.countStar("n"))));
        assertThrows(ColumnarDbException.class, () -> new HashAggregateOperator(null, List.of("s"), List.of()));
    }
}
