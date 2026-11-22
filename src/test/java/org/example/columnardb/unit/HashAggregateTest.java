package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.corpus.Datasets.Mode;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.QueryBuilder;
import org.junit.jupiter.api.Test;

/** U-05: HashAggregateOperator. */
class HashAggregateTest {

    private static InMemoryTable data(Mode mode) {
        return table(mode,
                r(10L, 1.5, "UA"), r(20L, 2.5, "US"), r(null, 4.0, "UA"),
                r(30L, null, "UA"), r(null, null, null), r(5L, 1.0, "US"));
    }

    @Test
    void sumAvgCountMinMaxPerGroupIgnoreNulls() {
        for (Mode mode : Mode.values()) {
            List<Object[]> rows = run(QueryBuilder.from(data(mode)).groupBy("s").aggregate(
                    AggregateFunc.sum("a", "sum_a"), AggregateFunc.avg("a", "avg_a"),
                    AggregateFunc.count("a", "cnt_a"), AggregateFunc.countStar("cnt"),
                    AggregateFunc.min("a", "min_a"), AggregateFunc.max("b", "max_b")).build());
            Rows.assertSame(Rows.of(
                    r("UA", 40L, 20.0, 2L, 3L, 10L, 4.0),
                    r("US", 25L, 12.5, 2L, 2L, 5L, 2.5),
                    r(null, null, null, 0L, 1L, null, null)), rows);
        }
    }

    @Test
    void firstSeenGroupOrderIsPreserved() {
        List<Object[]> rows = run(QueryBuilder.from(table(r(1L, 1.0, "b"), r(2L, 1.0, "a"), r(3L, 1.0, "b")))
                .groupBy("s").aggregate(AggregateFunc.countStar("n")).build());
        assertEquals(Arrays.asList("b", "a"), firstColumn(rows));
    }

    @Test
    void globalAggregateOverEmptyInputReturnsOneRow() {
        InMemoryTable empty = table();
        List<Object[]> rows = run(QueryBuilder.from(empty).aggregate(AggregateFunc.countStar("n"),
                AggregateFunc.count("a", "c"), AggregateFunc.sum("a", "s"), AggregateFunc.avg("b", "avg"),
                AggregateFunc.min("s", "mn"), AggregateFunc.max("b", "mx")).build());
        Rows.assertSame(Rows.of(r(0L, 0L, null, null, null, null)), rows);
    }

    @Test
    void groupedAggregateOverEmptyInputReturnsNoRows() {
        assertEquals(0, run(QueryBuilder.from(table()).groupBy("s").aggregate(AggregateFunc.countStar("n")).build()).size());
    }

    @Test
    void filterThatRemovesEverythingStillGivesGlobalRow() {
        List<Object[]> rows = run(QueryBuilder.from(data(Mode.FLAT)).where(Predicate.gt("a", 1000))
                .aggregate(AggregateFunc.countStar("n"), AggregateFunc.sum("a", "s")).build());
        Rows.assertSame(Rows.of(r(0L, null)), rows);
    }

    @Test
    void globalAggregateWithoutGroupBy() {
        List<Object[]> rows = run(QueryBuilder.from(data(Mode.FLAT)).aggregate(
                AggregateFunc.sum("a", "s"), AggregateFunc.sum("b", "sb"), AggregateFunc.countStar("n"),
                AggregateFunc.min("s", "mn"), AggregateFunc.max("s", "mx")).build());
        Rows.assertSame(Rows.of(r(65L, 9.0, 6L, "UA", "US")), rows);
    }

    @Test
    void groupByMultipleKeys() {
        InMemoryTable t = table(r(1L, 1.0, "x"), r(1L, 2.0, "y"), r(1L, 3.0, "x"), r(2L, 4.0, "x"), r(null, 5.0, "x"));
        List<Object[]> rows = run(QueryBuilder.from(t).groupBy("a", "s").aggregate(AggregateFunc.sum("b", "total")).build());
        Rows.assertSame(Rows.of(r(1L, "x", 4.0), r(1L, "y", 2.0), r(2L, "x", 4.0), r(null, "x", 5.0)), rows);
    }

    @Test
    void groupByWithoutAggregatesIsDistinct() {
        List<Object[]> rows = run(QueryBuilder.from(data(Mode.FLAT)).groupBy("s").build());
        assertEquals(Arrays.asList("UA", "US", null), firstColumn(rows));
    }

    @Test
    void nullKeysFormTheirOwnGroup() {
        for (Mode mode : Mode.values()) {
            InMemoryTable t = table(mode, r(1L, 1.0, null), r(2L, 1.0, null), r(3L, 1.0, "a"));
            List<Object[]> rows = run(QueryBuilder.from(t).groupBy("s").aggregate(AggregateFunc.countStar("n")).build());
            Rows.assertSame(Rows.of(r(null, 2L), r("a", 1L)), rows);
        }
    }

    @Test
    void dictionaryEncodedKeyGivesSameResultAsFlat() {
        List<Object[]> src = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            src.add(r((long) i, (double) (i % 7), i % 11 == 0 ? null : "k" + (i % 5)));
        }
        InMemoryTable flat = new InMemoryTable(SIMPLE);
        flat.appendRows(src);
        InMemoryTable dict = new InMemoryTable(SIMPLE, org.example.columnardb.encoding.DictionaryEncoder::encode);
        dict.appendRows(src);
        for (InMemoryTable t : List.of(flat, dict)) {
            assertEquals(6, run(QueryBuilder.from(t).groupBy("s").aggregate(AggregateFunc.sum("a", "x")).build()).size());
        }
        Rows.assertSame(
                run(QueryBuilder.from(flat).groupBy("s").aggregate(AggregateFunc.sum("a", "x"), AggregateFunc.avg("b", "y")).build()),
                run(QueryBuilder.from(dict).groupBy("s").aggregate(AggregateFunc.sum("a", "x"), AggregateFunc.avg("b", "y")).build()));
    }

    @Test
    void manyGroupsTriggerRehashAndMultipleResultBatches() {
        InMemoryTable t = new InMemoryTable(SIMPLE);
        for (int i = 0; i < 3000; i++) {
            t.appendRow((long) (i % 2500), 1.0, "s");
        }
        var result = QueryEngine.execute(QueryBuilder.from(t).groupBy("a").aggregate(AggregateFunc.countStar("n"),
                AggregateFunc.sum("b", "total")).build());
        assertEquals(2500, result.rowCount());
        assertEquals(3, result.batches().size());
        assertEquals(1024, result.batches().get(0).rowCount());
        long twos = result.rows().stream().filter(row -> (Long) row[1] == 2L).count();
        assertEquals(500, twos);
        assertEquals(2.0, (Double) result.rows().get(0)[2], 0.0);
    }

    @Test
    void integerSumOverflowIsReported() {
        InMemoryTable t = table(r(Long.MAX_VALUE, null, "g"), r(1L, null, "g"));
        assertThrows(ColumnarDbException.class,
                () -> run(QueryBuilder.from(t).groupBy("s").aggregate(AggregateFunc.sum("a", "x")).build()));
    }

    @Test
    void minMaxOnStringsAndNumbers() {
        InMemoryTable t = table(r(3L, 2.5, "pear"), r(-4L, -1.5, "apple"), r(9L, null, null), r(0L, Double.NaN, "zebra"));
        List<Object[]> rows = run(QueryBuilder.from(t).aggregate(AggregateFunc.min("a", "a0"), AggregateFunc.max("a", "a1"),
                AggregateFunc.min("b", "b0"), AggregateFunc.max("b", "b1"),
                AggregateFunc.min("s", "s0"), AggregateFunc.max("s", "s1")).build());
        assertEquals(-4L, rows.get(0)[0]);
        assertEquals(9L, rows.get(0)[1]);
        assertEquals(-1.5, (Double) rows.get(0)[2], 0.0);
        assertTrue(Double.isNaN((Double) rows.get(0)[3]));
        assertEquals("apple", rows.get(0)[4]);
        assertEquals("zebra", rows.get(0)[5]);
    }

    @Test
    void signedZerosAndNansGroupTogether() {
        InMemoryTable t = table(r(1L, 0.0, null), r(2L, -0.0, null), r(3L, Double.NaN, null), r(4L, Double.NaN, null));
        List<Object[]> rows = run(QueryBuilder.from(t).groupBy("b").aggregate(AggregateFunc.sum("a", "x")).build());
        assertEquals(2, rows.size());
        assertEquals(3L, rows.get(0)[1]);
        assertEquals(7L, rows.get(1)[1]);
    }

    @Test
    void typeRulesAreEnforced() {
        InMemoryTable t = data(Mode.FLAT);
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).aggregate(AggregateFunc.sum("s", "x")).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).aggregate(AggregateFunc.avg("s", "x")).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).aggregate(AggregateFunc.sum("zzz", "x")).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).groupBy("zzz").build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).groupBy("s")
                .aggregate(AggregateFunc.countStar("s")).build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t).groupBy("s", "s").build());
        assertThrows(ColumnarDbException.class, () -> new AggregateFunc(AggregateFunc.Kind.SUM, null, "x"));
        assertThrows(ColumnarDbException.class, () -> new AggregateFunc(AggregateFunc.Kind.COUNT_STAR, "a", "x"));
        assertThrows(ColumnarDbException.class, () -> AggregateFunc.sum("a", " "));
    }

    @Test
    void havingFiltersAggregatedRows() {
        List<Object[]> rows = run(QueryBuilder.from(data(Mode.FLAT)).groupBy("s")
                .aggregate(AggregateFunc.sum("a", "total")).having(Predicate.gt("total", 30)).build());
        Rows.assertSame(Rows.of(r("UA", 40L)), rows);
    }

    @Test
    void outputNullabilityMatchesFunctionKind() {
        assertFalse(AggregateFunc.count("a", "c").outputNullable());
        assertFalse(AggregateFunc.countStar("c").outputNullable());
        assertTrue(AggregateFunc.sum("a", "c").outputNullable());
        assertTrue(AggregateFunc.min("a", "c").outputNullable());
    }

    /** 4119438558 підібрано перебором: його хеш у GroupKeyTable збігається з хешем NULL-ключа. */
    @Test
    void nullKeyAndValueWithCollidingHashStayInSeparateGroups() {
        long colliding = 4119438558L;
        for (Object[][] rows : new Object[][][] {
                {r(colliding, 1.0, "x"), r(null, 1.0, "x")}, {r(null, 1.0, "x"), r(colliding, 1.0, "x")}}) {
            List<Object[]> result = run(QueryBuilder.from(table(rows)).groupBy("a")
                    .aggregate(AggregateFunc.countStar("n")).build());
            assertEquals(2, result.size());
            assertEquals(1L, result.get(0)[1]);
            assertEquals(1L, result.get(1)[1]);
        }
    }
}
