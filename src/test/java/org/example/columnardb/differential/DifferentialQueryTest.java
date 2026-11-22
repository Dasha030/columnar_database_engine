package org.example.columnardb.differential;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.query.Query;
import org.junit.jupiter.api.Test;

/** D-01: випадкові аналітичні запити проти референсної рядкової реалізації. */
class DifferentialQueryTest {
    private static final int[] SIZES = {0, 1, 2, 100, 1023, 1024, 1025, 3000};

    private static int mismatches(int size, Datasets.Mode mode, int queries, long seed) {
        List<Object[]> rows = Datasets.randomRows(seed, size);
        InMemoryTable table = Datasets.table(rows, mode);
        Random rnd = new Random(seed * 31 + size);
        int bad = 0;
        for (int i = 0; i < queries; i++) {
            Query q = RandomQueries.generate(rnd, table, size);
            String diff = Rows.diff(ReferenceEngine.execute(q, rows), QueryEngine.execute(q).rows());
            if (diff != null) {
                bad++;
                fail("size=" + size + " mode=" + mode + " query#" + i + " " + q + "\n" + diff);
            }
        }
        return bad;
    }

    @Test
    void flatStorageMatchesReference() {
        for (int size : SIZES) {
            assertEquals(0, mismatches(size, Datasets.Mode.FLAT, 40, 101));
        }
    }

    @Test
    void autoEncodedStorageMatchesReference() {
        for (int size : SIZES) {
            assertEquals(0, mismatches(size, Datasets.Mode.AUTO, 40, 202));
        }
    }

    @Test
    void rleStorageMatchesReference() {
        for (int size : SIZES) {
            assertEquals(0, mismatches(size, Datasets.Mode.RLE, 40, 303));
        }
    }

    @Test
    void dictionaryStorageMatchesReference() {
        for (int size : SIZES) {
            assertEquals(0, mismatches(size, Datasets.Mode.DICTIONARY, 40, 404));
        }
    }

    @Test
    void bitPackedStorageMatchesReference() {
        for (int size : SIZES) {
            assertEquals(0, mismatches(size, Datasets.Mode.PACKED, 40, 505));
        }
    }

    @Test
    void referenceEngineItselfAgreesWithHandComputedAnswer() {
        List<Object[]> rows = List.of(
                new Object[] {0L, 20L, "UA", 100.0, "eng"},
                new Object[] {1L, null, "UA", 50.0, "eng"},
                new Object[] {2L, 40L, "US", null, "ops"});
        InMemoryTable table = Datasets.table(rows, Datasets.Mode.FLAT);
        Query q = org.example.columnardb.query.QueryBuilder.from(table)
                .where(org.example.columnardb.query.Predicate.ge("age", 20)).groupBy("country")
                .aggregate(org.example.columnardb.aggregate.AggregateFunc.sum("salary", "s")).build();
        List<Object[]> ref = ReferenceEngine.execute(q, rows);
        assertEquals(2, ref.size());
        assertEquals(100.0, (Double) ref.get(0)[1], 0.0);
        assertNull(ref.get(1)[1]);
    }
}
