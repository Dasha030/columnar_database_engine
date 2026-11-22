package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/** Типізовані вектори: Int64Vector, Float64Vector, StringVector. */
class VectorTest {

    @Test
    void int64VectorStoresValuesAndNulls() {
        NullBitmap nulls = new NullBitmap(3);
        nulls.setNull(1);
        Int64Vector v = new Int64Vector(new long[] {10, 0, 30}, nulls);
        assertEquals(DataType.INT64, v.type());
        assertEquals(Encoding.FLAT, v.encoding());
        assertEquals(3, v.size());
        assertEquals(10, v.getLong(0));
        assertTrue(v.isNull(1));
        assertEquals(30, v.getLong(2));
        assertEquals(1, v.nullCount());
        assertNull(v.getObject(1));
        assertEquals(30L, v.getObject(2));
    }

    @Test
    void vectorsAreImmutableViaDefensiveCopies() {
        long[] source = {1, 2, 3};
        NullBitmap nulls = new NullBitmap(3);
        Int64Vector v = new Int64Vector(source, nulls);
        source[0] = 99;
        nulls.setNull(1);
        assertEquals(1, v.getLong(0));
        assertFalse(v.isNull(1));
        v.toArray()[0] = 77;
        assertEquals(1, v.getLong(0));
    }

    @Test
    void lengthMismatchBetweenValuesAndBitmapIsRejected() {
        assertThrows(ColumnarDbException.class, () -> new Int64Vector(new long[2], new NullBitmap(3)));
        assertThrows(ColumnarDbException.class, () -> new Float64Vector(new double[2], new NullBitmap(1)));
        assertThrows(ColumnarDbException.class, () -> new StringVector(new String[2], new NullBitmap(3)));
        assertThrows(ColumnarDbException.class, () -> new Int64Vector(null, new NullBitmap(0)));
    }

    @Test
    void outOfRangeAccessThrowsColumnarDbException() {
        Int64Vector v = new Int64Vector(new long[] {1});
        assertThrows(ColumnarDbException.class, () -> v.getLong(1));
        assertThrows(ColumnarDbException.class, () -> v.getLong(-1));
        assertThrows(ColumnarDbException.class, () -> v.isNull(5));
    }

    @Test
    void wrongTypedAccessorsAreRejected() {
        Int64Vector ints = new Int64Vector(new long[] {1});
        assertThrows(ColumnarDbException.class, () -> ints.getDouble(0));
        assertThrows(ColumnarDbException.class, () -> ints.getString(0));
        Float64Vector doubles = new Float64Vector(new double[] {1});
        assertThrows(ColumnarDbException.class, () -> doubles.getLong(0));
        StringVector strings = new StringVector(new String[] {"a"});
        assertThrows(ColumnarDbException.class, () -> strings.getLong(0));
        assertThrows(ColumnarDbException.class, () -> strings.asDouble(0));
    }

    @Test
    void float64VectorStoresValuesAndSpecialNumbers() {
        Float64Vector v = Float64Vector.ofNullable(1.5, null, Double.NaN, -0.0);
        assertEquals(DataType.FLOAT64, v.type());
        assertEquals(1.5, v.getDouble(0), 0.0);
        assertTrue(v.isNull(1));
        assertTrue(Double.isNaN(v.getDouble(2)));
        assertEquals(Double.doubleToLongBits(-0.0), Double.doubleToLongBits(v.getDouble(3)));
        assertEquals(1, v.nullCount());
        assertEquals(1.5, v.asDouble(0), 0.0);
    }

    @Test
    void asDoubleConvertsIntegers() {
        assertEquals(7.0, new Int64Vector(new long[] {7}).asDouble(0), 0.0);
    }

    @Test
    void stringVectorUsesArrowOffsetLayoutWithUtf8() {
        StringVector v = new StringVector(new String[] {"UA", "", "Привіт", "日本"});
        assertEquals(4, v.size());
        assertEquals("UA", v.getString(0));
        assertEquals("", v.getString(1));
        assertEquals("Привіт", v.getString(2));
        assertEquals("日本", v.getString(3));
        assertEquals(2, v.byteLength(0));
        assertEquals(0, v.byteLength(1));
        assertEquals("Привіт".getBytes(StandardCharsets.UTF_8).length, v.byteLength(2));
        assertEquals(6, v.byteLength(3));
    }

    @Test
    void stringVectorHandlesNulls() {
        StringVector v = StringVector.ofNullable("a", null, "c");
        assertTrue(v.isNull(1));
        assertEquals("c", v.getString(2));
        assertEquals(1, v.nullCount());
        assertNull(v.getObject(1));
        assertEquals("a", v.getObject(0));
    }

    @Test
    void nullStringMarkedValidIsRejected() {
        assertThrows(ColumnarDbException.class, () -> new StringVector(new String[] {null}, new NullBitmap(1)));
    }

    @Test
    void stringVectorFromValidBuffers() {
        byte[] bytes = "abcde".getBytes(StandardCharsets.UTF_8);
        StringVector v = new StringVector(bytes, new int[] {0, 2, 2, 5}, new NullBitmap(3));
        assertEquals("ab", v.getString(0));
        assertEquals("", v.getString(1));
        assertEquals("cde", v.getString(2));
    }

    @Test
    void corruptedStringBuffersAreRejected() {
        byte[] bytes = new byte[5];
        NullBitmap n3 = new NullBitmap(3);
        assertThrows(ColumnarDbException.class, () -> new StringVector(bytes, new int[] {0, 2, 5}, n3));
        assertThrows(ColumnarDbException.class, () -> new StringVector(bytes, new int[] {1, 2, 3, 5}, n3));
        assertThrows(ColumnarDbException.class, () -> new StringVector(bytes, new int[] {0, 3, 2, 5}, n3));
        assertThrows(ColumnarDbException.class, () -> new StringVector(bytes, new int[] {0, 2, 3, 4}, n3));
        assertThrows(ColumnarDbException.class, () -> new StringVector(bytes, new int[] {0, 2, 3, 9}, n3));
    }

    @Test
    void sliceCopiesRangeAsFlat() {
        Int64Vector v = Int64Vector.ofNullable(1L, null, 3L, 4L);
        ColumnVector s = v.slice(1, 2);
        assertEquals(2, s.size());
        assertTrue(s.isNull(0));
        assertEquals(3, s.getLong(1));
        assertEquals(0, v.slice(4, 0).size());
        assertThrows(ColumnarDbException.class, () -> v.slice(3, 2));
        assertThrows(ColumnarDbException.class, () -> v.slice(-1, 1));
    }

    @Test
    void flattenOfFlatVectorReturnsSameInstance() {
        Int64Vector v = new Int64Vector(new long[] {1});
        assertSame(v, v.flatten());
    }

    @Test
    void toStringDescribesVector() {
        String s = new Int64Vector(new long[] {1, 2}).toString();
        assertTrue(s.startsWith("INT64/FLAT[2]"));
        assertTrue(new Int64Vector(new long[20]).toString().endsWith("...}"));
    }

    @Test
    void vectorsUtilityComparesContentAcrossEncodings() {
        ColumnVector a = Vectors.of(DataType.INT64, 1L, null, 3L);
        ColumnVector b = Int64Vector.ofNullable(1L, null, 3L);
        assertTrue(Vectors.contentEquals(a, b));
        assertFalse(Vectors.contentEquals(a, Int64Vector.ofNullable(1L, 2L, 3L)));
        assertFalse(Vectors.contentEquals(a, Int64Vector.ofNullable(1L, null)));
        assertFalse(Vectors.contentEquals(a, Float64Vector.ofNullable(1.0, null, 3.0)));
        assertEquals(Arrays.asList(1L, null, 3L), Vectors.toList(a));
    }

    @Test
    void sameValueTreatsNullsAndDoublesCorrectly() {
        Float64Vector v = Float64Vector.ofNullable(Double.NaN, Double.NaN, 0.0, -0.0, null, null);
        assertTrue(Vectors.sameValue(v, 0, 1));
        assertFalse(Vectors.sameValue(v, 2, 3));
        assertTrue(Vectors.sameValue(v, 4, 5));
        assertFalse(Vectors.sameValue(v, 0, 4));
    }

    @Test
    void concatGatherAndConstant() {
        ColumnVector c = Vectors.concat(DataType.STRING,
                java.util.List.of(StringVector.ofNullable("a", null), StringVector.ofNullable("c")));
        assertEquals(Arrays.asList("a", null, "c"), Vectors.toList(c));
        ColumnVector g = Vectors.gather(c, new int[] {2, 0, 0}, 0, 3);
        assertEquals(Arrays.asList("c", "a", "a"), Vectors.toList(g));
        assertEquals(Arrays.asList("c", "a"), Vectors.toList(Vectors.gather(c, new int[] {2, 0, 0}, 0, 2)));
        assertEquals(Arrays.asList(5L, 5L), Vectors.toList(Vectors.constant(DataType.INT64, 5L, 2)));
        assertEquals(Arrays.asList((Object) null, null), Vectors.toList(Vectors.constant(DataType.STRING, null, 2)));
        assertThrows(ColumnarDbException.class,
                () -> Vectors.concat(DataType.INT64, java.util.List.of(StringVector.ofNullable("a"))));
        assertThrows(ColumnarDbException.class, () -> Vectors.constant(DataType.INT64, 1L, -1));
    }
}
