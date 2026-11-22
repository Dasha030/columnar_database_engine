package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.engine.*;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.QueryBuilder;
import org.junit.jupiter.api.Test;

class SortLimitTest {

    private static InMemoryTable t() {
        return table(r(3L, 1.0, "c"), r(1L, 2.0, "a"), r(null, 3.0, "b"), r(2L, 2.0, null), r(1L, 9.0, "z"));
    }

    @Test
    void ascendingPutsNullsFirst() {
        assertEquals(Arrays.asList(null, 1L, 1L, 2L, 3L),
                firstColumn(run(QueryBuilder.from(t()).select("a").orderBy("a").build())));
    }

    @Test
    void descendingPutsNullsLast() {
        assertEquals(Arrays.asList(3L, 2L, 1L, 1L, null),
                firstColumn(run(QueryBuilder.from(t()).select("a").orderByDesc("a").build())));
    }

    @Test
    void multipleKeysAndStability() {
        List<Object[]> rows = run(QueryBuilder.from(t()).orderBy("b").orderByDesc("a").select("b", "a", "s").build());
        assertEquals(Arrays.asList(1.0, 2.0, 2.0, 3.0, 9.0), firstColumn(rows));
        assertEquals(2L, rows.get(1)[1]); // при b == 2.0 за a DESC: 2 раніше за 1
        assertEquals(1L, rows.get(2)[1]);
        // стабільність: однакові a зберігають порядок вставки (s: "a" перед "z")
        List<Object[]> stable = run(QueryBuilder.from(t()).orderBy("a").select("a", "s").build());
        assertEquals("a", stable.get(1)[1]);
        assertEquals("z", stable.get(2)[1]);
    }

    @Test
    void sortsStringsWithNullsSmallest() {
        assertEquals(Arrays.asList(null, "a", "b", "c", "z"),
                firstColumn(run(QueryBuilder.from(t()).select("s").orderBy("s").build())));
    }

    @Test
    void sortsAcrossManyBatches() {
        InMemoryTable big = new InMemoryTable(SIMPLE);
        Random rnd = new Random(5);
        for (int i = 0; i < 3000; i++) {
            big.appendRow((long) rnd.nextInt(1000), 0.0, "x");
        }
        var res = QueryEngine.execute(QueryBuilder.from(big).select("a").orderBy("a").build());
        assertEquals(3, res.batches().size());
        long prev = Long.MIN_VALUE;
        for (Object[] row : res.rows()) {
            assertTrue((Long) row[0] >= prev);
            prev = (Long) row[0];
        }
        assertEquals(3000, res.rowCount());
    }

    @Test
    void sortOfEmptyInputProducesNoBatches() {
        assertEquals(0, run(QueryBuilder.from(table()).orderBy("a").build()).size());
    }

    @Test
    void sortRespectsPriorFilter() {
        assertEquals(Arrays.asList(2L, 3L),
                firstColumn(run(QueryBuilder.from(t()).where(Predicate.gt("a", 1)).select("a").orderBy("a").build())));
    }

    @Test
    void limitTruncatesSelection() {
        assertEquals(2, run(QueryBuilder.from(t()).limit(2).build()).size());
        assertEquals(5, run(QueryBuilder.from(t()).limit(100).build()).size());
        assertEquals(0, run(QueryBuilder.from(t()).limit(0).build()).size());
    }

    @Test
    void limitAfterFilterAndSort() {
        assertEquals(Arrays.asList(1L, 1L),
                firstColumn(run(QueryBuilder.from(t()).where(Predicate.isNotNull("a")).select("a").orderBy("a").limit(2).build())));
    }

    @Test
    void limitStopsPullingFromChild() {
        InMemoryTable big = new InMemoryTable(SIMPLE);
        for (int i = 0; i < 3000; i++) {
            big.appendRow((long) i, 0.0, "x");
        }
        LimitOperator op = new LimitOperator(new ScanOperator(big), 1500);
        assertEquals(1024, op.nextBatch().activeRowCount());
        assertEquals(476, op.nextBatch().activeRowCount());
        assertFalse(op.hasNext());
        assertThrows(ColumnarDbException.class, op::nextBatch);
        assertThrows(ColumnarDbException.class, () -> new LimitOperator(new ScanOperator(big), -1));
    }

    @Test
    void indexSorterIsStableAndCorrect() {
        int[] keys = {3, 1, 2, 1, 3, 2, 1};
        int[] perm = IndexSorter.sort(keys.length, (x, y) -> Integer.compare(keys[x], keys[y]));
        assertArrayEquals(new int[] {1, 3, 6, 2, 5, 0, 4}, perm);
        assertEquals(0, IndexSorter.sort(0, (x, y) -> 0).length);
        assertArrayEquals(new int[] {0}, IndexSorter.sort(1, (x, y) -> 0));
        assertThrows(ColumnarDbException.class, () -> IndexSorter.sort(-1, (x, y) -> 0));
        assertThrows(ColumnarDbException.class, () -> IndexSorter.sort(1, null));
    }

    @Test
    void indexSorterAgreesWithJavaSortOnRandomData() {
        Random rnd = new Random(11);
        for (int n : new int[] {2, 3, 17, 100, 1001}) {
            int[] keys = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = rnd.nextInt(10);
            }
            int[] perm = IndexSorter.sort(n, (x, y) -> Integer.compare(keys[x], keys[y]));
            Integer[] expected = new Integer[n];
            for (int i = 0; i < n; i++) {
                expected[i] = i;
            }
            Arrays.sort(expected, (x, y) -> Integer.compare(keys[x], keys[y])); // стабільний
            for (int i = 0; i < n; i++) {
                assertEquals((int) expected[i], perm[i]);
            }
        }
    }

    @Test
    void invalidSortSpecificationsAreRejected() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t()).orderBy("zzz").build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(t()).limit(-1));
        assertThrows(ColumnarDbException.class, () -> new SortOperator(new ScanOperator(t()), List.of()));
    }
}
