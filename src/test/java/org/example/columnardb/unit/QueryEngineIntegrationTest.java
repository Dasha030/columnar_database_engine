package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.*;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.engine.*;
import org.example.columnardb.query.*;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class QueryEngineIntegrationTest {

    @Test
    void designDocumentExampleQuery() {
        TableSchema schema = TableSchema.builder()
                .addColumn("id", DataType.INT64, false).addColumn("age", DataType.INT64, true)
                .addColumn("country", DataType.STRING, false).addColumn("salary", DataType.FLOAT64, false).build();
        InMemoryTable table = new InMemoryTable(schema);
        table.append(RecordBatch.builder(schema)
                .addVector("id", new Int64Vector(new long[] {1, 2, 3}, new NullBitmap(3)))
                .addVector("age", new Int64Vector(new long[] {25, 17, 30}, new NullBitmap(3)))
                .addVector("country", new StringVector(new String[] {"UA", "US", "UA"}, new NullBitmap(3)))
                .addVector("salary", new Float64Vector(new double[] {1000.0, 500.0, 1500.0}, new NullBitmap(3)))
                .build());
        Query query = QueryBuilder.from(table).where(Predicate.gt("age", 18)).groupBy("country")
                .aggregate(AggregateFunc.sum("salary", "total_salary")).build();
        VectorOperator engine = QueryEngine.compile(query);
        assertEquals(List.of("country", "total_salary"), engine.schema().names());
        assertTrue(engine.hasNext());
        RecordBatch result = engine.nextBatch();
        assertFalse(engine.hasNext());
        Rows.assertSame(Rows.of(r("UA", 2500.0)), result.rows());
    }

    @Test
    void fullPipelineWhereGroupHavingOrderLimit() {
        InMemoryTable t = Datasets.table(3000, 42, Datasets.Mode.AUTO);
        Query q = QueryBuilder.from(t).where(Predicate.isNotNull("country")).groupBy("country")
                .aggregate(AggregateFunc.countStar("n"), AggregateFunc.avg("age", "avg_age"))
                .having(Predicate.gt("n", 10)).orderByDesc("n").limit(2).build();
        List<Object[]> rows = QueryEngine.execute(q).rows();
        assertEquals(2, rows.size());
        assertTrue((Long) rows.get(0)[1] >= (Long) rows.get(1)[1]);
    }

    @Test
    void countStarWithoutColumnsStillCountsRows() {
        InMemoryTable t = Datasets.table(2500, 1, Datasets.Mode.FLAT);
        List<Object[]> rows = run(QueryBuilder.from(t).aggregate(AggregateFunc.countStar("n")).build());
        Rows.assertSame(Rows.of(r(2500L)), rows);
    }

    @Test
    void scanPrunesUnusedColumns() {
        InMemoryTable t = Datasets.table(10, 1, Datasets.Mode.FLAT);
        Query q = QueryBuilder.from(t).where(Predicate.gt("age", 0)).groupBy("country")
                .aggregate(AggregateFunc.sum("salary", "s")).build();
        assertEquals(List.of("age", "country", "salary"), QueryEngine.requiredColumns(q));
    }

    @Test
    void selectStarReturnsAllRowsInOrder() {
        InMemoryTable t = Datasets.table(1500, 3, Datasets.Mode.RLE);
        var result = QueryEngine.execute(QueryBuilder.from(t).build());
        assertEquals(1500, result.rowCount());
        assertEquals(2, result.batches().size());
        Rows.assertSame(t.rows(), result.rows());
    }

    @Test
    void resultToStringListsHeaderAndRows() {
        String text = QueryEngine.execute(QueryBuilder.from(table(r(1L, 2.0, "x"))).select("a", "s").build()).toString();
        assertEquals("a | s\n1 | x\n", text);
    }

    @Test
    void sameQueryCanBeCompiledAndExecutedRepeatedly() {
        Query q = QueryBuilder.from(table(r(1L, 1.0, "x"), r(2L, 1.0, "y"))).where(Predicate.gt("a", 1)).build();
        assertEquals(1, run(q).size());
        assertEquals(1, run(q).size());
    }

    @Test
    void projectionWithAliasesAndArithmeticAfterFilter() {
        Query q = QueryBuilder.from(Datasets.table(2000, 9, Datasets.Mode.PACKED))
                .where(Predicate.ge("age", 80)).project("id2", Expression.mul(Expression.col("id"), Expression.lit(2)))
                .build();
        for (Object[] row : run(q)) {
            assertEquals(0L, (Long) row[0] % 2);
        }
    }
}
