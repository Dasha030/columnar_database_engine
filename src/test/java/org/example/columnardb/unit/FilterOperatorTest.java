package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.SelectionVector;
import org.example.columnardb.corpus.Datasets.Mode;
import org.example.columnardb.engine.*;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.QueryBuilder;
import org.junit.jupiter.api.Test;

/** U-04: FilterOperator і PredicateEvaluator. */
class FilterOperatorTest {

    private static InMemoryTable ints() {
        return table(r(1L, null, null), r(5L, null, null), r(null, null, null), r(10L, null, null), r(5L, null, null));
    }

    private static List<Object> a(Predicate p) {
        return firstColumn(run(QueryBuilder.from(ints()).where(p).select("a").build()));
    }

    @Test
    void comparisonOperatorsOnIntegers() {
        assertEquals(List.of(10L), a(Predicate.gt("a", 5)));
        assertEquals(List.of(5L, 10L, 5L), a(Predicate.ge("a", 5)));
        assertEquals(List.of(1L), a(Predicate.lt("a", 5)));
        assertEquals(List.of(1L, 5L, 5L), a(Predicate.le("a", 5)));
        assertEquals(List.of(5L, 5L), a(Predicate.eq("a", 5)));
        assertEquals(List.of(1L, 10L), a(Predicate.ne("a", 5)));
    }

    @Test
    void nullsNeverSatisfyComparisons() {
        assertEquals(0, a(Predicate.eq("a", 99)).size());
        assertFalse(a(Predicate.ne("a", 99)).contains(null));
        assertEquals(4, a(Predicate.ne("a", 99)).size());
    }

    @Test
    void isNullAndIsNotNull() {
        assertEquals(1, a(Predicate.isNull("a")).size());
        assertEquals(4, a(Predicate.isNotNull("a")).size());
    }

    @Test
    void andOrNotUseThreeValuedLogic() {
        assertEquals(List.of(5L, 5L), a(Predicate.and(Predicate.gt("a", 1), Predicate.lt("a", 10))));
        assertEquals(List.of(1L, 10L), a(Predicate.or(Predicate.lt("a", 2), Predicate.gt("a", 6))));
        // NOT UNKNOWN = UNKNOWN: рядок з NULL не потрапляє ні в P, ні в NOT P
        assertEquals(List.of(1L, 10L), a(Predicate.not(Predicate.eq("a", 5))));
        // UNKNOWN OR TRUE = TRUE
        assertEquals(3, a(Predicate.or(Predicate.eq("a", 5), Predicate.isNull("a"))).size());
        // UNKNOWN AND FALSE = FALSE, UNKNOWN AND TRUE = UNKNOWN
        assertEquals(0, a(Predicate.and(Predicate.gt("a", 100), Predicate.isNull("a"))).size());
        assertEquals(4, a(Predicate.not(Predicate.and(Predicate.gt("a", 100), Predicate.isNull("a")))).size());
    }

    @Test
    void betweenAndIn() {
        assertEquals(List.of(5L, 10L, 5L), a(Predicate.between("a", 5, 10)));
        assertEquals(List.of(1L, 10L), a(Predicate.in("a", 1, 10, 77)));
        assertEquals(List.of(1L, 5L, 5L), a(Predicate.in("a", 1.0, 5.0)));
    }

    @Test
    void doubleColumnsCompareWithIntegerAndFloatingLiterals() {
        InMemoryTable t = table(r(null, 1.5, null), r(null, 2.5, null), r(null, null, null), r(null, 0.0, null));
        assertEquals(1, run(QueryBuilder.from(t).where(Predicate.gt("b", 2)).build()).size());
        assertEquals(2, run(QueryBuilder.from(t).where(Predicate.le("b", 1.5)).build()).size());
        assertEquals(1, run(QueryBuilder.from(t).where(Predicate.eq("b", -0.0)).build()).size());
    }

    @Test
    void nanIsGreaterThanEverythingAndEqualToItself() {
        InMemoryTable t = table(r(null, Double.NaN, null), r(null, 1.0, null));
        assertEquals(1, run(QueryBuilder.from(t).where(Predicate.gt("b", 1e300)).build()).size());
        assertEquals(1, run(QueryBuilder.from(t).where(Predicate.eq("b", Double.NaN)).build()).size());
    }

    @Test
    void integerColumnAgainstDoubleLiteral() {
        assertEquals(List.of(10L), a(Predicate.gt("a", 5.5)));
        assertEquals(List.of(5L, 5L), a(Predicate.eq("a", 5.0)));
    }

    @Test
    void stringComparisons() {
        InMemoryTable t = table(r(null, null, "apple"), r(null, null, "banana"), r(null, null, null), r(null, null, "cherry"));
        assertEquals(1, run(QueryBuilder.from(t).where(Predicate.eq("s", "banana")).build()).size());
        assertEquals(2, run(QueryBuilder.from(t).where(Predicate.gt("s", "apple")).build()).size());
        assertEquals(2, run(QueryBuilder.from(t).where(Predicate.in("s", "apple", "cherry")).build()).size());
        assertEquals(2, run(QueryBuilder.from(t).where(Predicate.lt("s", "c")).build()).size());
    }

    @Test
    void dictionaryAndRleColumnsGiveSameAnswersAsFlat() {
        Object[][] rows = {r(1L, null, "x"), r(1L, null, "x"), r(null, null, "y"), r(2L, null, null), r(2L, null, "x")};
        for (Mode mode : Mode.values()) {
            InMemoryTable t = table(mode, rows);
            assertEquals(3, run(QueryBuilder.from(t).where(Predicate.eq("s", "x")).build()).size(), mode.name());
            assertEquals(2, run(QueryBuilder.from(t).where(Predicate.gt("a", 1)).build()).size(), mode.name());
            assertEquals(4, run(QueryBuilder.from(t).where(Predicate.isNotNull("a")).build()).size(), mode.name());
            assertEquals(1, run(QueryBuilder.from(t).where(Predicate.isNull("s")).build()).size(), mode.name());
        }
    }

    @Test
    void filterCreatesSelectionVectorWithoutCopyingColumns() {
        InMemoryTable t = ints();
        RecordBatch source = t.batches().get(0);
        FilterOperator op = new FilterOperator(new ScanOperator(t), Predicate.gt("a", 4));
        RecordBatch out = op.nextBatch();
        assertTrue(out.hasSelection());
        assertEquals(new SelectionVector(new int[] {1, 3, 4}, 3), out.selection());
        assertSame(source.column("a"), out.column("a"));
    }

    @Test
    void emptyBatchesAreSkipped() {
        InMemoryTable t = table(r(1L, null, null));
        FilterOperator op = new FilterOperator(new ScanOperator(t), Predicate.gt("a", 100));
        assertFalse(op.hasNext());
        assertThrows(ColumnarDbException.class, op::nextBatch);
    }

    @Test
    void chainedFiltersIntersectSelections() {
        VectorOperator op = new FilterOperator(
                new FilterOperator(new ScanOperator(ints()), Predicate.ge("a", 5)), Predicate.le("a", 5));
        RecordBatch out = op.nextBatch();
        assertEquals(new SelectionVector(new int[] {1, 4}, 2), out.selection());
    }

    @Test
    void evaluatorExposesTruthValues() {
        RecordBatch b = ints().batches().get(0);
        byte[] truth = PredicateEvaluator.evaluate(Predicate.gt("a", 4), b);
        assertArrayEquals(new Object[] {(byte) 0, (byte) 1, (byte) 2, (byte) 1, (byte) 1},
                new Object[] {truth[0], truth[1], truth[2], truth[3], truth[4]});
        assertEquals(PredicateEvaluator.UNKNOWN, PredicateEvaluator.and(PredicateEvaluator.TRUE, PredicateEvaluator.UNKNOWN));
        assertEquals(PredicateEvaluator.FALSE, PredicateEvaluator.and(PredicateEvaluator.UNKNOWN, PredicateEvaluator.FALSE));
        assertEquals(PredicateEvaluator.TRUE, PredicateEvaluator.or(PredicateEvaluator.UNKNOWN, PredicateEvaluator.TRUE));
        assertEquals(PredicateEvaluator.UNKNOWN, PredicateEvaluator.or(PredicateEvaluator.FALSE, PredicateEvaluator.UNKNOWN));
        assertEquals(PredicateEvaluator.FALSE, PredicateEvaluator.or(PredicateEvaluator.FALSE, PredicateEvaluator.FALSE));
        assertEquals(PredicateEvaluator.TRUE, PredicateEvaluator.and(PredicateEvaluator.TRUE, PredicateEvaluator.TRUE));
    }

    @Test
    void evaluatorRespectsExistingSelection() {
        RecordBatch b = ints().batches().get(0).withSelection(new SelectionVector(new int[] {1, 3}, 2));
        SelectionVector s = PredicateEvaluator.select(Predicate.gt("a", 5), b);
        assertEquals(new SelectionVector(new int[] {3}, 1), s);
    }

    @Test
    void invalidPredicatesAreRejectedBeforeExecution() {
        InMemoryTable t = ints();
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).where(Predicate.eq("zzz", 1)).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).where(Predicate.eq("a", "text")).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).where(Predicate.eq("s", 5)).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).where(Predicate.in("a", "x")).build());
    }
}
