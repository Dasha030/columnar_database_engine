package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.*;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class RecordBatchTest {
    private static final TableSchema SCHEMA = TableSchema.builder()
            .addColumn("id", DataType.INT64, false)
            .addColumn("name", DataType.STRING, true)
            .build();

    private static RecordBatch batch() {
        return RecordBatch.builder(SCHEMA)
                .addVector("id", new Int64Vector(new long[] {1, 2, 3, 4}))
                .addVector("name", StringVector.ofNullable("a", null, "c", "d"))
                .build();
    }

    @Test
    void schemaLooksUpColumnsByNameAndIndex() {
        assertEquals(2, SCHEMA.size());
        assertEquals(1, SCHEMA.indexOf("name"));
        assertEquals(DataType.INT64, SCHEMA.typeOf("id"));
        assertEquals(new ColumnDef("id", DataType.INT64, false), SCHEMA.column(0));
        assertTrue(SCHEMA.has("id"));
        assertFalse(SCHEMA.has("zzz"));
        assertEquals(List.of("id", "name"), SCHEMA.names());
        assertThrows(ColumnarDbException.class, () -> SCHEMA.indexOf("zzz"));
        assertThrows(ColumnarDbException.class, () -> SCHEMA.column(2));
    }

    @Test
    void schemaRejectsDuplicatesEmptyAndBlankNames() {
        assertThrows(ColumnarDbException.class, () -> TableSchema.builder()
                .addColumn("a", DataType.INT64, true).addColumn("a", DataType.STRING, true).build());
        assertThrows(ColumnarDbException.class, () -> TableSchema.builder().build());
        assertThrows(ColumnarDbException.class, () -> new ColumnDef(" ", DataType.INT64, true));
        assertThrows(ColumnarDbException.class, () -> new ColumnDef(null, DataType.INT64, true));
        assertThrows(ColumnarDbException.class, () -> new ColumnDef("a", null, true));
    }

    @Test
    void schemaProjectionKeepsOrderAndEquality() {
        TableSchema p = SCHEMA.project(List.of("name"));
        assertEquals(1, p.size());
        assertEquals("name", p.column(0).name());
        assertEquals(SCHEMA, TableSchema.of(SCHEMA.columns()));
        assertEquals(SCHEMA.hashCode(), TableSchema.of(SCHEMA.columns()).hashCode());
        assertNotEquals(SCHEMA, p);
        assertThrows(ColumnarDbException.class, () -> SCHEMA.project(List.of("nope")));
    }

    @Test
    void builderCreatesBatchWithoutSelection() {
        RecordBatch b = batch();
        assertEquals(4, b.rowCount());
        assertEquals(4, b.activeRowCount());
        assertFalse(b.hasSelection());
        assertEquals(3, b.rowIndex(3));
        assertEquals(SCHEMA, b.schema());
        assertEquals("c", b.column("name").getString(2));
        assertEquals(4, b.column(0).getLong(3));
        assertEquals(4, b.selection().size());
    }

    @Test
    void builderValidatesVectors() {
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("id", StringVector.ofNullable("x")));
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("id", Int64Vector.ofNullable(1L, null)));
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("zzz", new Int64Vector(new long[1])));
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("id", new Int64Vector(new long[1])).addVector("id", new Int64Vector(new long[1])));
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("id", new Int64Vector(new long[2])).build());
        assertThrows(ColumnarDbException.class, () -> RecordBatch.builder(SCHEMA)
                .addVector("id", new Int64Vector(new long[2]))
                .addVector("name", StringVector.ofNullable("a")).build());
    }

    @Test
    void batchSizeIsLimitedTo1024() {
        TableSchema one = TableSchema.builder().addColumn("x", DataType.INT64, false).build();
        assertEquals(1024, RecordBatch.builder(one).addVector("x", new Int64Vector(new long[1024])).build().rowCount());
        assertThrows(ColumnarDbException.class,
                () -> RecordBatch.builder(one).addVector("x", new Int64Vector(new long[1025])).build());
    }

    @Test
    void selectionRestrictsActiveRowsWithoutCopyingData() {
        RecordBatch b = batch();
        RecordBatch s = b.withSelection(new SelectionVector(new int[] {1, 3}, 2));
        assertTrue(s.hasSelection());
        assertEquals(4, s.rowCount());
        assertEquals(2, s.activeRowCount());
        assertEquals(1, s.rowIndex(0));
        assertEquals(3, s.rowIndex(1));
        assertSame(b.column("id"), s.column("id"));
        assertThrows(ColumnarDbException.class, () -> s.rowIndex(2));
        assertFalse(s.withoutSelection().hasSelection());
        assertThrows(ColumnarDbException.class, () -> b.withSelection(new SelectionVector(new int[] {4}, 1)));
    }

    @Test
    void rowsReturnOnlyActiveRows() {
        RecordBatch s = batch().withSelection(new SelectionVector(new int[] {0, 1}, 2));
        List<Object[]> rows = s.rows();
        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] {1L, "a"}, rows.get(0));
        assertArrayEquals(new Object[] {2L, null}, rows.get(1));
    }

    @Test
    void compactMaterializesSelectedRows() {
        RecordBatch s = batch().withSelection(new SelectionVector(new int[] {0, 2}, 2));
        RecordBatch c = s.compact();
        assertFalse(c.hasSelection());
        assertEquals(2, c.rowCount());
        assertEquals(3, c.column("id").getLong(1));
        RecordBatch plain = batch();
        assertSame(plain, plain.compact());
    }

    @Test
    void sliceCopiesPhysicalRange() {
        RecordBatch sl = batch().slice(1, 2);
        assertEquals(2, sl.rowCount());
        assertEquals(2, sl.column("id").getLong(0));
        assertTrue(sl.column("name").isNull(0));
        assertThrows(ColumnarDbException.class, () -> batch().slice(3, 2));
        assertThrows(ColumnarDbException.class,
                () -> batch().withSelection(SelectionVector.all(4)).slice(0, 1));
    }

    @Test
    void projectKeepsSelectionAndReordersColumns() {
        RecordBatch s = batch().withSelection(new SelectionVector(new int[] {2}, 1));
        RecordBatch p = s.project(List.of("name", "id"));
        assertEquals(List.of("name", "id"), p.schema().names());
        assertEquals(1, p.activeRowCount());
        assertSame(s.column("id"), p.column("id"));
        assertThrows(ColumnarDbException.class, () -> s.project(List.of("zzz")));
    }

    @Test
    void fromRowsBuildsBatchAndValidatesRowWidth() {
        RecordBatch b = RecordBatch.fromRows(SCHEMA, Arrays.asList(new Object[] {1L, "x"}, new Object[] {2, null}));
        assertEquals(2, b.rowCount());
        assertEquals(2, b.column("id").getLong(1));
        assertTrue(b.column("name").isNull(1));
        assertThrows(ColumnarDbException.class,
                () -> RecordBatch.fromRows(SCHEMA, Arrays.<Object[]>asList(new Object[] {1L})));
        assertEquals(0, RecordBatch.fromRows(SCHEMA, List.of()).rowCount());
    }
}
