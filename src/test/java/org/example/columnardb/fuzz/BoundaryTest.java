package org.example.columnardb.fuzz;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.corpus.Datasets.Mode;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.engine.QueryResult;
import org.example.columnardb.property.Invariants;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.QueryBuilder;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/** R-01 і R-02: граничні вектори та нерегулярні розміри даних. */
class BoundaryTest {

    private static List<ColumnVector> boundaryVectors() {
        List<ColumnVector> list = new ArrayList<>();
        for (DataType type : DataType.values()) {
            Object sample = type == DataType.INT64 ? (Object) 5L : type == DataType.FLOAT64 ? (Object) 2.5 : "x";
            for (int n : new int[] {0, 1, 2, 63, 64, 65}) {
                list.add(Vectors.constant(type, sample, n));      // усі значення однакові
                list.add(Vectors.constant(type, null, n));        // повністю NULL
            }
        }
        return list;
    }

    @Test
    void r01_boundaryVectorsRoundTripThroughEveryEncoding() {
        for (ColumnVector v : boundaryVectors()) {
            Invariants.compressionRoundTrip(v);
        }
    }

    @Test
    void r01_allNullAndSingleElementVectorsBehaveInQueries() {
        for (int n : new int[] {0, 1}) {
            for (Mode mode : Mode.values()) {
                List<Object[]> rows = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    rows.add(new Object[] {(long) i, null, null, null, "eng"});
                }
                InMemoryTable t = Datasets.table(rows, mode);
                List<Object[]> r = QueryEngine.execute(QueryBuilder.from(t).where(Predicate.gt("age", 1))
                        .aggregate(AggregateFunc.countStar("n"), AggregateFunc.sum("age", "s"), AggregateFunc.avg("salary", "a"),
                                AggregateFunc.min("country", "m")).build()).rows();
                Rows.assertSame(Rows.of(Rows.row(0L, null, null, null)), r);
                assertEquals(n, QueryEngine.execute(QueryBuilder.from(t).where(Predicate.isNull("age")).build()).rowCount());
            }
        }
    }

    @Test
    void r02_irregularRowCountsProduceExpectedBatchLayout() {
        int[][] cases = {{1, 1}, {1023, 1}, {1024, 1}, {1025, 2}, {2048, 2}, {2049, 3}, {3000, 3}};
        for (int[] c : cases) {
            InMemoryTable t = Datasets.table(c[0], 7, Mode.AUTO);
            assertEquals(c[0], t.rowCount());
            assertEquals(c[1], t.batches().size(), "rows=" + c[0]);
            for (int b = 0; b < t.batches().size() - 1; b++) {
                assertEquals(RecordBatch.MAX_ROWS, t.batches().get(b).rowCount());
            }
        }
    }

    @Test
    void r02_scanReturnsEveryRowExactlyOnceForIrregularSizes() {
        for (int n : new int[] {1, 1023, 1025, 3000}) {
            for (Mode mode : Mode.values()) {
                List<Object[]> rows = Datasets.randomRows(n, n);
                QueryResult result = QueryEngine.execute(QueryBuilder.from(Datasets.table(rows, mode)).build());
                Rows.assertSame(rows, result.rows());
            }
        }
    }

    @Test
    void r02_filterAcrossBatchBoundariesKeepsOrder() {
        List<Object[]> rows = Datasets.randomRows(3, 3000);
        InMemoryTable t = Datasets.table(rows, Mode.RLE);
        long last = -1;
        for (Object[] row : QueryEngine.execute(QueryBuilder.from(t).where(Predicate.ge("id", 1000)).build()).rows()) {
            assertTrue((Long) row[0] > last);
            last = (Long) row[0];
        }
        assertEquals(2999, last);
    }

    @Test
    void extremeNumericValuesSurviveStorageAndQueries() {
        InMemoryTable t = new InMemoryTable(Datasets.SCHEMA, Datasets.encoder(Mode.PACKED));
        t.appendRow(0L, Long.MIN_VALUE, "UA", Double.MAX_VALUE, "eng");
        t.appendRow(1L, Long.MAX_VALUE, "UA", -Double.MAX_VALUE, "eng");
        t.appendRow(2L, 0L, "UA", Double.NaN, "eng");
        List<Object[]> r = QueryEngine.execute(QueryBuilder.from(t).where(Predicate.gt("age", Long.MIN_VALUE))
                .select("id").build()).rows();
        assertEquals(2, r.size());
        assertEquals((Object) Long.MIN_VALUE, QueryEngine.execute(QueryBuilder.from(t)
                .aggregate(AggregateFunc.min("age", "m")).build()).rows().get(0)[0]);
    }

    @Test
    void veryLongAndUnicodeStringsSurviveDictionaryAndGrouping() {
        InMemoryTable t = new InMemoryTable(Datasets.SCHEMA, Datasets.encoder(Mode.DICTIONARY));
        String big = "ж".repeat(5000);
        t.appendRow(0L, 1L, big, 1.0, "eng");
        t.appendRow(1L, 2L, big, 2.0, "eng");
        t.appendRow(2L, 3L, "日本語🙂", 4.0, "eng");
        List<Object[]> r = QueryEngine.execute(QueryBuilder.from(t).groupBy("country")
                .aggregate(AggregateFunc.sum("salary", "s")).build()).rows();
        assertEquals(2, r.size());
        assertEquals(big, r.get(0)[0]);
        assertEquals(3.0, (Double) r.get(0)[1], 0.0);
    }
}
