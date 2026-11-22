package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.encoding.RleEncoder;
import org.example.columnardb.encoding.RleVector;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/** U-02: Run-Length Encoding. */
class RleEncodingTest {

    @Test
    void compressesConsecutiveEqualValuesIntoRuns() {
        ColumnVector v = new Int64Vector(new long[] {7, 7, 7, 3, 3, 9});
        RleVector rle = RleEncoder.encode(v);
        assertEquals(Encoding.RLE, rle.encoding());
        assertEquals(3, rle.runCount());
        assertEquals(6, rle.size());
        assertEquals(3, rle.runLength(0));
        assertEquals(2, rle.runLength(1));
        assertEquals(1, rle.runLength(2));
        assertEquals(0, rle.runStart(0));
        assertEquals(3, rle.runStart(1));
        assertEquals(5, rle.runStart(2));
        assertEquals(9, rle.runValues().getLong(2));
    }

    @Test
    void roundTripRestoresOriginalValues() {
        ColumnVector v = Int64Vector.ofNullable(1L, 1L, null, null, 2L, 1L, 1L);
        RleVector rle = RleEncoder.encode(v);
        assertTrue(Vectors.contentEquals(v, RleEncoder.decode(rle)));
        assertTrue(Vectors.contentEquals(v, rle));
    }

    @Test
    void consecutiveNullsFormOneRun() {
        RleVector rle = RleEncoder.encode(Int64Vector.ofNullable(null, null, null, 5L));
        assertEquals(2, rle.runCount());
        assertTrue(rle.isNull(2));
        assertFalse(rle.isNull(3));
        assertEquals(5, rle.getLong(3));
    }

    @Test
    void randomAccessWorksAtRunBoundaries() {
        RleVector rle = RleEncoder.encode(new Int64Vector(new long[] {1, 1, 2, 2, 2, 3}));
        long[] expected = {1, 1, 2, 2, 2, 3};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], rle.getLong(i), "index " + i);
        }
        assertEquals(0, rle.runIndexOf(0));
        assertEquals(0, rle.runIndexOf(1));
        assertEquals(1, rle.runIndexOf(2));
        assertEquals(1, rle.runIndexOf(4));
        assertEquals(2, rle.runIndexOf(5));
        assertThrows(ColumnarDbException.class, () -> rle.getLong(6));
        assertThrows(ColumnarDbException.class, () -> rle.getLong(-1));
    }

    @Test
    void supportsStringsAndDoubles() {
        RleVector s = RleEncoder.encode(StringVector.ofNullable("a", "a", "b", null, null));
        assertEquals(3, s.runCount());
        assertEquals("b", s.getString(2));
        assertTrue(s.isNull(4));
        RleVector d = RleEncoder.encode(new Float64Vector(new double[] {1.5, 1.5, 2.5}));
        assertEquals(2.5, d.getDouble(2), 0.0);
        assertEquals(DataType.FLOAT64, d.type());
    }

    @Test
    void nanRunsMergeButSignedZerosStayApart() {
        assertEquals(1, RleEncoder.encode(new Float64Vector(new double[] {Double.NaN, Double.NaN})).runCount());
        RleVector zeros = RleEncoder.encode(new Float64Vector(new double[] {0.0, -0.0}));
        assertEquals(2, zeros.runCount());
        assertEquals(Double.doubleToLongBits(-0.0), Double.doubleToLongBits(zeros.getDouble(1)));
    }

    @Test
    void emptyVectorHasNoRuns() {
        RleVector rle = RleEncoder.encode(new Int64Vector(new long[0]));
        assertEquals(0, rle.runCount());
        assertEquals(0, rle.size());
        assertEquals(0, RleEncoder.decode(rle).size());
        assertEquals(0, RleEncoder.countRuns(new Int64Vector(new long[0])));
    }

    @Test
    void countRunsMatchesEncoding() {
        ColumnVector v = Int64Vector.ofNullable(1L, 1L, 2L, null, null, 1L);
        assertEquals(4, RleEncoder.countRuns(v));
        assertEquals(RleEncoder.countRuns(v), RleEncoder.encode(v).runCount());
    }

    @Test
    void manyRunsGrowInternalArrays() {
        long[] values = new long[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        RleVector rle = RleEncoder.encode(new Int64Vector(values));
        assertEquals(1000, rle.runCount());
        assertEquals(999, rle.getLong(999));
    }

    @Test
    void invalidRunDescriptionsAreRejected() {
        ColumnVector values = new Int64Vector(new long[] {1, 2});
        assertThrows(ColumnarDbException.class, () -> new RleVector(values, new int[] {1}));
        assertThrows(ColumnarDbException.class, () -> new RleVector(values, new int[] {1, 0}));
        assertThrows(ColumnarDbException.class, () -> new RleVector(values, new int[] {1, -2}));
        assertThrows(ColumnarDbException.class, () -> new RleVector(values, new int[] {Integer.MAX_VALUE, 1}));
        ColumnVector nested = RleEncoder.encode(values);
        assertThrows(ColumnarDbException.class, () -> new RleVector(nested, new int[] {1, 1}));
    }

    @Test
    void sliceOfRleVectorIsFlatCopy() {
        RleVector rle = RleEncoder.encode(new Int64Vector(new long[] {1, 1, 2, 2}));
        ColumnVector s = rle.slice(1, 2);
        assertEquals(Encoding.FLAT, s.encoding());
        assertEquals(Arrays.asList(1L, 2L), Vectors.toList(s));
        assertEquals(Encoding.FLAT, rle.flatten().encoding());
    }

    @Test
    void runLengthAccessorsValidateIndex() {
        RleVector rle = RleEncoder.encode(new Int64Vector(new long[] {1}));
        assertThrows(ColumnarDbException.class, () -> rle.runLength(1));
        assertThrows(ColumnarDbException.class, () -> rle.runStart(-1));
        assertTrue(rle.toString().contains("runs=1"));
    }
}
