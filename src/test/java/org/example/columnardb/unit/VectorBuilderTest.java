package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class VectorBuilderTest {

    @Test
    void buildsInt64WithNullsAndGrowsBeyondInitialCapacity() {
        VectorBuilder b = new VectorBuilder(DataType.INT64, 1);
        for (int i = 0; i < 1000; i++) {
            if (i % 10 == 0) {
                b.appendNull();
            } else {
                b.appendLong(i);
            }
        }
        ColumnVector v = b.build();
        assertEquals(1000, v.size());
        assertEquals(100, v.nullCount());
        assertTrue(v.isNull(990));
        assertEquals(999, v.getLong(999));
        assertEquals(1000, b.size());
    }

    @Test
    void buildsFloat64AndString() {
        ColumnVector d = new VectorBuilder(DataType.FLOAT64).appendDouble(1.5).appendNull().build();
        assertEquals(1.5, d.getDouble(0), 0.0);
        assertTrue(d.isNull(1));
        ColumnVector s = new VectorBuilder(DataType.STRING).appendString("x").appendString(null).build();
        assertEquals("x", s.getString(0));
        assertTrue(s.isNull(1));
    }

    @Test
    void emptyBuilderProducesEmptyVector() {
        for (DataType t : DataType.values()) {
            assertEquals(0, new VectorBuilder(t).build().size());
        }
    }

    @Test
    void appendingWrongTypeIsRejected() {
        VectorBuilder ints = new VectorBuilder(DataType.INT64);
        assertThrows(ColumnarDbException.class, () -> ints.appendDouble(1.0));
        assertThrows(ColumnarDbException.class, () -> ints.appendString("a"));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.STRING).appendLong(1));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.FLOAT64).appendLong(1));
    }

    @Test
    void appendObjectConvertsNumbers() {
        ColumnVector ints = new VectorBuilder(DataType.INT64)
                .appendObject(1).appendObject(2L).appendObject((short) 3).appendObject((byte) 4).appendObject(null).build();
        assertEquals(3, ints.getLong(2));
        assertEquals(4, ints.getLong(3));
        assertTrue(ints.isNull(4));
        ColumnVector doubles = new VectorBuilder(DataType.FLOAT64).appendObject(1).appendObject(2.5f).build();
        assertEquals(1.0, doubles.getDouble(0), 0.0);
        assertEquals(2.5, doubles.getDouble(1), 0.0);
    }

    @Test
    void appendObjectRejectsIncompatibleValues() {
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.INT64).appendObject(1.5));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.INT64).appendObject("1"));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.FLOAT64).appendObject("1"));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.STRING).appendObject(1));
    }

    @Test
    void appendFromCopiesValuesAndNulls() {
        ColumnVector src = Int64Vector.ofNullable(5L, null);
        ColumnVector copy = new VectorBuilder(DataType.INT64).appendFrom(src, 1).appendFrom(src, 0).build();
        assertTrue(copy.isNull(0));
        assertEquals(5, copy.getLong(1));
        assertThrows(ColumnarDbException.class, () -> new VectorBuilder(DataType.STRING).appendFrom(src, 0));
    }

    @Test
    void builderReportsType() {
        assertEquals(DataType.STRING, new VectorBuilder(DataType.STRING).type());
        assertTrue(DataType.INT64.isNumeric());
        assertTrue(DataType.FLOAT64.isNumeric());
        assertFalse(DataType.STRING.isNumeric());
    }
}
