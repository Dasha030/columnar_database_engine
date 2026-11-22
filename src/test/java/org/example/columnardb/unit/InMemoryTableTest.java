package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.*;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class InMemoryTableTest {
    private static final TableSchema SCHEMA = TableSchema.builder()
            .addColumn("v", DataType.INT64, false).build();

    private static RecordBatch batchOf(int n, int offset) {
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = offset + i;
        }
        return RecordBatch.builder(SCHEMA).addVector("v", new Int64Vector(values)).build();
    }

    @Test
    void appendStoresBatchesAndCountsRows() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.append(batchOf(10, 0));
        t.append(batchOf(5, 10));
        assertEquals(15, t.rowCount());
        assertEquals(2, t.batches().size());
        assertEquals(SCHEMA, t.schema());
    }

    @Test
    void appendRowsFlushPerThousandTwentyFour() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        for (int i = 0; i < 2500; i++) {
            t.appendRow((long) i);
        }
        assertEquals(2500, t.rowCount());
        List<RecordBatch> batches = t.batches();
        assertEquals(3, batches.size());
        assertEquals(1024, batches.get(0).rowCount());
        assertEquals(1024, batches.get(1).rowCount());
        assertEquals(452, batches.get(2).rowCount());
        assertEquals(2499, batches.get(2).column("v").getLong(451));
    }

    @Test
    void pendingRowsAreVisibleInRowCountBeforeFlush() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.appendRow(1L);
        assertEquals(1, t.rowCount());
        assertEquals(1, t.rows().size());
    }

    @Test
    void largeBatchIsSplitIntoChunksOfAtMost1024() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        // батч > 1024 не можна побудувати напряму, тому збираємо через кілька appendRows
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            rows.add(new Object[] {(long) i});
        }
        t.appendRows(rows);
        assertEquals(3, t.batches().size());
        assertEquals(3000, t.rowCount());
    }

    @Test
    void appendRejectsForeignSchema() {
        InMemoryTable t = new InMemoryTable(Datasets.SCHEMA);
        assertThrows(ColumnarDbException.class, () -> t.append(batchOf(1, 0)));
        assertThrows(ColumnarDbException.class, () -> t.append(null));
        assertThrows(ColumnarDbException.class, () -> new InMemoryTable(null));
    }

    @Test
    void batchWithSelectionIsCompactedOnAppend() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.append(batchOf(5, 0).withSelection(new SelectionVector(new int[] {1, 4}, 2)));
        assertEquals(2, t.rowCount());
        assertEquals(4, t.batches().get(0).column("v").getLong(1));
    }

    @Test
    void emptyBatchesAreIgnored() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.append(batchOf(0, 0));
        assertEquals(0, t.rowCount());
        assertTrue(t.batches().isEmpty());
    }

    @Test
    void autoEncodeCompressesStoredColumns() {
        InMemoryTable t = new InMemoryTable(SCHEMA, true);
        for (int i = 0; i < 200; i++) {
            t.appendRow(7L);
        }
        assertEquals(Encoding.RLE, t.batches().get(0).column("v").encoding());
        assertEquals(7, t.batches().get(0).column("v").getLong(199));
    }

    @Test
    void customEncoderIsApplied() {
        InMemoryTable t = Datasets.table(100, 1, Datasets.Mode.DICTIONARY);
        assertEquals(Encoding.DICTIONARY, t.batches().get(0).column("country").encoding());
        assertEquals(Encoding.FLAT, Datasets.table(100, 1, Datasets.Mode.FLAT).batches().get(0).column("id").encoding());
    }

    @Test
    void rowsReturnsAllRowsInInsertionOrder() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.appendRows(List.of(new Object[] {3L}, new Object[] {1L}, new Object[] {2L}));
        List<Object[]> rows = t.rows();
        assertEquals(3L, rows.get(0)[0]);
        assertEquals(1L, rows.get(1)[0]);
        assertEquals(2L, rows.get(2)[0]);
    }

    @Test
    void batchesViewIsUnmodifiable() {
        InMemoryTable t = new InMemoryTable(SCHEMA);
        t.appendRow(1L);
        assertThrows(UnsupportedOperationException.class, () -> t.batches().clear());
    }
}
