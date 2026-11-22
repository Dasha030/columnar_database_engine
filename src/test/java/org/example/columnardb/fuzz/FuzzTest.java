package org.example.columnardb.fuzz;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.SelectionVector;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.differential.RandomQueries;
import org.example.columnardb.differential.ReferenceEngine;
import org.example.columnardb.encoding.*;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.property.Invariants;
import org.example.columnardb.query.*;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/**
 * R-03: fuzzing. Жоден вхід не може викликати інший виняток, ніж {@link ColumnarDbException}
 * (ні NullPointerException, ні IndexOutOfBoundsException, ні NegativeArraySizeException тощо).
 */
class FuzzTest {

    /** Виконує дію; допускається успіх або ColumnarDbException, будь-що інше — провал тесту. */
    private static <T> T guarded(String what, Supplier<T> action) {
        try {
            return action.get();
        } catch (ColumnarDbException expected) {
            return null;
        } catch (RuntimeException unexpected) {
            throw new AssertionError("Unhandled " + unexpected.getClass().getSimpleName() + " in " + what, unexpected);
        }
    }

    private static NullBitmap randomBitmap(Random rnd, int size) {
        boolean[] flags = new boolean[size];
        double p = rnd.nextDouble();
        for (int i = 0; i < size; i++) {
            flags[i] = rnd.nextDouble() < p;
        }
        return NullBitmap.fromNullFlags(flags);
    }

    @Test
    void randomBitmapsHaveConsistentCountsAndAccess() {
        Random rnd = new Random(1);
        for (int iter = 0; iter < 500; iter++) {
            int size = rnd.nextInt(300);
            boolean[] flags = new boolean[size];
            int nulls = 0;
            for (int i = 0; i < size; i++) {
                flags[i] = rnd.nextBoolean();
                nulls += flags[i] ? 1 : 0;
            }
            NullBitmap b = NullBitmap.fromNullFlags(flags);
            assertEquals(nulls, b.countNulls());
            for (int i = 0; i < size; i++) {
                assertEquals(flags[i], b.isNull(i));
            }
            if (size > 0) {
                int lo = rnd.nextInt(size);
                NullBitmap s = b.slice(lo, rnd.nextInt(size - lo + 1));
                for (int i = 0; i < s.size(); i++) {
                    assertEquals(flags[lo + i], s.isNull(i));
                }
            }
            for (int probe : new int[] {-1, size, size + 1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                final int index = probe;
                assertThrows(ColumnarDbException.class, () -> b.isNull(index));
            }
        }
    }

    @Test
    void randomVectorsWithRandomBitmapsRoundTrip() {
        Random rnd = new Random(2);
        for (int iter = 0; iter < 200; iter++) {
            int n = rnd.nextInt(200);
            NullBitmap nulls = randomBitmap(rnd, n);
            long[] longs = new long[n];
            double[] doubles = new double[n];
            String[] strings = new String[n];
            for (int i = 0; i < n; i++) {
                longs[i] = rnd.nextInt(6) - 3;
                doubles[i] = rnd.nextInt(4) * 0.5;
                strings[i] = nulls.isNull(i) ? null : "v" + rnd.nextInt(5);
            }
            Invariants.compressionRoundTrip(new Int64Vector(longs, nulls));
            Invariants.compressionRoundTrip(new Float64Vector(doubles, nulls));
            Invariants.compressionRoundTrip(new StringVector(strings, nulls));
        }
    }

    @Test
    void corruptedStringBuffersNeverCauseUnhandledExceptions() {
        Random rnd = new Random(3);
        for (int iter = 0; iter < 2000; iter++) {
            byte[] bytes = new byte[rnd.nextInt(20)];
            rnd.nextBytes(bytes);
            int n = rnd.nextInt(6);
            int[] offsets = new int[n + 1 + rnd.nextInt(2) - (n > 0 ? rnd.nextInt(2) : 0)];
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = rnd.nextInt(25) - 2;
            }
            NullBitmap bitmap = randomBitmap(rnd, n);
            StringVector v = guarded("StringVector buffers", () -> new StringVector(bytes, offsets, bitmap));
            if (v != null) {
                for (int i = 0; i < v.size(); i++) {
                    v.getString(i); // валідні буфери читаються без винятків
                }
            }
        }
    }

    @Test
    void corruptedEncodedVectorsAreRejectedOrReadSafely() {
        Random rnd = new Random(4);
        for (int iter = 0; iter < 2000; iter++) {
            int runs = rnd.nextInt(5);
            int[] lengths = new int[runs];
            for (int i = 0; i < runs; i++) {
                lengths[i] = rnd.nextInt(6) - 1;
            }
            ColumnVector values = new Int64Vector(new long[runs + (rnd.nextInt(5) == 0 ? 1 : 0)]);
            RleVector rle = guarded("RleVector", () -> new RleVector(values, lengths));
            if (rle != null) {
                for (int i = 0; i < rle.size(); i++) {
                    rle.getLong(i);
                }
            }
            int n = rnd.nextInt(8);
            int[] codes = new int[n];
            for (int i = 0; i < n; i++) {
                codes[i] = rnd.nextInt(10) - 1;
            }
            int card = rnd.nextInt(6);
            NullBitmap bitmap = randomBitmap(rnd, n);
            ColumnVector dict = new Int64Vector(new long[card]);
            DictionaryVector d = guarded("DictionaryVector",
                    () -> new DictionaryVector(dict, PackedCodes.of(codes, rnd.nextInt(12)), bitmap));
            if (d != null) {
                for (int i = 0; i < d.size(); i++) {
                    if (!d.isNull(i)) {
                        d.getLong(i);
                    }
                }
            }
            int width = rnd.nextInt(70) - 2;
            int size = rnd.nextInt(10);
            long[] words = new long[rnd.nextInt(8)];
            NullBitmap packedNulls = randomBitmap(rnd, size);
            BitPackedVector p = guarded("BitPackedVector",
                    () -> new BitPackedVector(rnd.nextLong(), width, words, size, packedNulls));
            if (p != null) {
                for (int i = 0; i < p.size(); i++) {
                    p.getLong(i);
                }
            }
        }
    }

    @Test
    void randomSelectionVectorsAndBatchesAreValidatedNotCrashing() {
        Random rnd = new Random(5);
        InMemoryTable table = Datasets.table(100, 1, Datasets.Mode.FLAT);
        RecordBatch batch = table.batches().get(0);
        for (int iter = 0; iter < 2000; iter++) {
            int[] idx = new int[rnd.nextInt(8)];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = rnd.nextInt(130) - 5;
            }
            int len = rnd.nextInt(idx.length + 3) - 1;
            SelectionVector sv = guarded("SelectionVector", () -> new SelectionVector(idx, len));
            if (sv != null) {
                RecordBatch withSel = guarded("withSelection", () -> batch.withSelection(sv));
                if (withSel != null) {
                    withSel.rows();
                    withSel.compact();
                }
                guarded("complement", () -> sv.complement(rnd.nextInt(150) - 5));
                guarded("intersect", () -> sv.intersect(SelectionVector.all(rnd.nextInt(120))));
            }
            guarded("slice", () -> batch.slice(rnd.nextInt(150) - 20, rnd.nextInt(150) - 20));
        }
    }

    @Test
    void randomGarbageQueriesFailOnlyWithColumnarDbException() {
        Random rnd = new Random(6);
        InMemoryTable table = Datasets.table(300, 2, Datasets.Mode.AUTO);
        String[] columns = {"id", "age", "country", "salary", "dept", "nope", ""};
        Object[] literals = {1, 2.5, "UA", Long.MAX_VALUE, "", Double.NaN, -1};
        for (int iter = 0; iter < 3000; iter++) {
            String col = columns[rnd.nextInt(columns.length)];
            Predicate.Op op = Predicate.Op.values()[rnd.nextInt(6)];
            Object lit = literals[rnd.nextInt(literals.length)];
            guarded("garbage query", () -> {
                QueryBuilder b = QueryBuilder.from(table).where(new Predicate.Compare(col, op, lit));
                if (rnd.nextBoolean()) {
                    b.groupBy(columns[rnd.nextInt(columns.length)]);
                }
                if (rnd.nextBoolean()) {
                    b.aggregate(rnd.nextBoolean()
                            ? org.example.columnardb.aggregate.AggregateFunc.sum(columns[rnd.nextInt(columns.length)], "x")
                            : org.example.columnardb.aggregate.AggregateFunc.max(columns[rnd.nextInt(columns.length)], "y"));
                }
                if (rnd.nextBoolean()) {
                    b.orderBy(columns[rnd.nextInt(columns.length)]);
                }
                return QueryEngine.execute(b.build());
            });
        }
    }

    @Test
    void randomValidQueriesNeverDisagreeWithReferenceUnderFuzzedData() {
        for (long seed = 0; seed < 25; seed++) {
            Random rnd = new Random(seed);
            int n = rnd.nextInt(2200);
            List<Object[]> rows = Datasets.randomRows(seed + 1000, n);
            Datasets.Mode mode = Datasets.Mode.values()[(int) (seed % 5)];
            InMemoryTable table = Datasets.table(rows, mode);
            for (int i = 0; i < 12; i++) {
                Query q = RandomQueries.generate(rnd, table, n);
                Rows.assertSame(ReferenceEngine.execute(q, rows), QueryEngine.execute(q).rows());
            }
        }
    }
}
