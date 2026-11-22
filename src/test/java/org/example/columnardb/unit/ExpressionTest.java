package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.example.columnardb.query.Expression.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.engine.*;
import org.example.columnardb.query.*;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class ExpressionTest {

    private static InMemoryTable t() {
        return table(r(10L, 2.5, "x"), r(7L, 0.0, "y"), r(null, 4.0, null), r(-9L, null, "z"));
    }

    private static List<Object> eval(Expression e) {
        return firstColumn(run(QueryBuilder.from(t()).project("r", e).build()));
    }

    @Test
    void integerArithmetic() {
        assertEquals(Arrays.asList(13L, 10L, null, -6L), eval(add(col("a"), lit(3))));
        assertEquals(Arrays.asList(7L, 4L, null, -12L), eval(sub(col("a"), lit(3))));
        assertEquals(Arrays.asList(30L, 21L, null, -27L), eval(mul(col("a"), lit(3))));
    }

    @Test
    void integerDivisionTruncatesAndZeroDivisorYieldsNull() {
        assertEquals(Arrays.asList(3L, 2L, null, -3L), eval(div(col("a"), lit(3))));
        assertEquals(java.util.Arrays.asList(null, null, null, null), eval(div(col("a"), lit(0))));
    }

    @Test
    void mixedArithmeticProducesDoubles() {
        assertEquals(Arrays.asList(12.5, 7.0, null, null), eval(add(col("a"), col("b"))));
        assertEquals(DataType.FLOAT64, add(col("a"), lit(1.5)).typeOf(SIMPLE));
        assertEquals(DataType.INT64, add(col("a"), lit(1)).typeOf(SIMPLE));
    }

    @Test
    void floatingDivisionByZeroIsNull() {
        List<Object> r = eval(div(lit(1.0), col("b")));
        assertEquals(0.4, (Double) r.get(0), 1e-12);
        assertNull(r.get(1));
        assertEquals(0.25, (Double) r.get(2), 1e-12);
        assertNull(r.get(3));
    }

    @Test
    void nestedExpressions() {
        assertEquals(Arrays.asList(23L, 17L, null, -15L), eval(add(mul(col("a"), lit(2)), lit(3))));
    }

    @Test
    void literalProjectionIsConstant() {
        assertEquals(List.of(5L, 5L, 5L, 5L), eval(lit(5)));
        assertEquals(List.of("k", "k", "k", "k"), eval(lit("k")));
    }

    @Test
    void integerOverflowIsReportedNotWrapped() {
        InMemoryTable big = table(r(Long.MAX_VALUE, null, null));
        assertThrows(ColumnarDbException.class,
                () -> run(QueryBuilder.from(big).project("x", add(col("a"), lit(1))).build()));
        assertThrows(ColumnarDbException.class,
                () -> run(QueryBuilder.from(big).project("x", mul(col("a"), lit(2))).build()));
        InMemoryTable small = table(r(Long.MIN_VALUE, null, null));
        assertThrows(ColumnarDbException.class,
                () -> run(QueryBuilder.from(small).project("x", sub(col("a"), lit(1))).build()));
        assertThrows(ColumnarDbException.class,
                () -> run(QueryBuilder.from(small).project("x", div(col("a"), lit(-1))).build()));
    }

    @Test
    void stringOperandsAreRejectedAtBuildTime() {
        assertThrows(ColumnarDbException.class,
                () -> QueryBuilder.from(t()).project("x", add(col("s"), lit(1))).build());
        assertThrows(ColumnarDbException.class,
                () -> QueryBuilder.from(t()).project("x", add(col("a"), lit("q"))).build());
    }

    @Test
    void projectionAfterFilterComputesOnlyActiveRows() {
        Query q = QueryBuilder.from(t()).where(Predicate.gt("a", 8)).project("a", col("a"))
                .project("double_a", mul(col("a"), lit(2))).build();
        List<Object[]> rows = run(q);
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[] {10L, 20L}, rows.get(0));
    }

    @Test
    void plainColumnReferenceWithoutSelectionKeepsEncodingAndInstance() {
        InMemoryTable table = table(org.example.columnardb.corpus.Datasets.Mode.RLE, r(1L, null, "a"), r(1L, null, "a"));
        RecordBatch source = table.batches().get(0);
        VectorOperator op = new ProjectionOperator(new ScanOperator(table),
                List.of(new ProjectionItem("a", col("a"))));
        RecordBatch out = op.nextBatch();
        assertSame(source.column("a"), out.column("a"));
        assertEquals(Encoding.RLE, out.column("a").encoding());
    }

    @Test
    void expressionMetadata() {
        assertEquals(java.util.Set.of("a", "b"), add(col("a"), mul(col("b"), lit(2))).columns());
        assertTrue(add(col("a"), lit(1)).nullable(SIMPLE));
        assertFalse(lit(1).nullable(SIMPLE));
        assertEquals("+", ArithSymbols.plus());
        assertThrows(ColumnarDbException.class, () -> col(" "));
        assertThrows(ColumnarDbException.class, () -> lit(null));
        assertThrows(ColumnarDbException.class, () -> lit(new Object()));
        assertThrows(ColumnarDbException.class, () -> new ProjectionItem("", lit(1)));
    }

    /** Допоміжний клас лише щоб прочитати символ оператора. */
    private static final class ArithSymbols {
        static String plus() {
            return Expression.ArithOp.ADD.symbol();
        }
    }
}
