package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.encoding.BitPackedEncoder;
import org.example.columnardb.encoding.BitPackedVector;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class BitPackingTest {

    @Test
    void bitsForComputesMinimalWidth() {
        assertEquals(0, BitPackedEncoder.bitsFor(0));
        assertEquals(1, BitPackedEncoder.bitsFor(1));
        assertEquals(2, BitPackedEncoder.bitsFor(2));
        assertEquals(2, BitPackedEncoder.bitsFor(3));
        assertEquals(8, BitPackedEncoder.bitsFor(255));
        assertEquals(9, BitPackedEncoder.bitsFor(256));
        assertEquals(64, BitPackedEncoder.bitsFor(-1L)); // беззнаковий максимум
    }

    @Test
    void smallRangeRoundTripUsesFrameOfReference() {
        ColumnVector v = new Int64Vector(new long[] {100, 103, 101, 107});
        BitPackedVector p = BitPackedEncoder.encode(v);
        assertEquals(Encoding.BIT_PACKED, p.encoding());
        assertEquals(100, p.base());
        assertEquals(3, p.bitWidth());
        assertTrue(Vectors.contentEquals(v, p));
        assertTrue(Vectors.contentEquals(v, BitPackedEncoder.decode(p)));
    }

    @Test
    void constantVectorNeedsZeroBits() {
        BitPackedVector p = BitPackedEncoder.encode(new Int64Vector(new long[] {42, 42, 42}));
        assertEquals(0, p.bitWidth());
        assertEquals(0, p.packedBytes());
        assertEquals(42, p.getLong(2));
    }

    @Test
    void fullRangeUses64BitsAndRoundTrips() {
        ColumnVector v = new Int64Vector(new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0, -1, 1});
        BitPackedVector p = BitPackedEncoder.encode(v);
        assertEquals(64, p.bitWidth());
        assertTrue(Vectors.contentEquals(v, p));
    }

    @Test
    void negativeValuesRoundTrip() {
        ColumnVector v = new Int64Vector(new long[] {-5, -3, -4, -5});
        BitPackedVector p = BitPackedEncoder.encode(v);
        assertEquals(-5, p.base());
        assertEquals(2, p.bitWidth());
        assertTrue(Vectors.contentEquals(v, p));
    }

    @Test
    void valuesCrossingWordBoundariesAreReadCorrectly() {
        for (int width : new int[] {3, 7, 13, 31, 33, 47, 63}) {
            long max = (1L << width) - 1;
            Random rnd = new Random(width);
            long[] values = new long[200];
            for (int i = 0; i < values.length; i++) {
                values[i] = i == 0 ? 0 : i == 1 ? max : (rnd.nextLong() >>> 1) & max;
            }
            BitPackedVector p = BitPackedEncoder.encode(new Int64Vector(values));
            assertEquals(width, p.bitWidth(), "width " + width);
            for (int i = 0; i < values.length; i++) {
                assertEquals(values[i], p.getLong(i), "width " + width + " index " + i);
            }
        }
    }

    @Test
    void nullsAreTrackedSeparately() {
        ColumnVector v = Int64Vector.ofNullable(10L, null, 12L, null);
        BitPackedVector p = BitPackedEncoder.encode(v);
        assertEquals(2, p.nullCount());
        assertTrue(p.isNull(1));
        assertEquals(12, p.getLong(2));
        assertTrue(Vectors.contentEquals(v, BitPackedEncoder.decode(p)));
    }

    @Test
    void emptyAndAllNullVectors() {
        assertEquals(0, BitPackedEncoder.encode(new Int64Vector(new long[0])).size());
        BitPackedVector nulls = BitPackedEncoder.encode(Int64Vector.ofNullable(null, null));
        assertEquals(0, nulls.bitWidth());
        assertTrue(nulls.isNull(0));
    }

    @Test
    void packedDataIsSmallerThanFlat() {
        long[] values = new long[1024];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 16;
        }
        BitPackedVector p = BitPackedEncoder.encode(new Int64Vector(values));
        assertEquals(4, p.bitWidth());
        assertEquals(1024 * 4 / 8, p.packedBytes());
        assertTrue(p.packedBytes() < values.length * 8);
    }

    @Test
    void onlyInt64IsSupported() {
        assertThrows(ColumnarDbException.class, () -> BitPackedEncoder.encode(new Float64Vector(new double[1])));
        assertThrows(ColumnarDbException.class, () -> BitPackedEncoder.encode(new StringVector(new String[1])));
    }

    @Test
    void invalidPackedVectorsAreRejected() {
        NullBitmap n = new NullBitmap(4);
        assertThrows(ColumnarDbException.class, () -> new BitPackedVector(0, 65, new long[10], 4, n));
        assertThrows(ColumnarDbException.class, () -> new BitPackedVector(0, -1, new long[10], 4, n));
        assertThrows(ColumnarDbException.class, () -> new BitPackedVector(0, 8, new long[0], 4, n));
        assertThrows(ColumnarDbException.class, () -> new BitPackedVector(0, 8, new long[1], 3, n));
        BitPackedVector ok = new BitPackedVector(5, 8, new long[1], 4, n);
        assertEquals(5, ok.getLong(0));
        assertThrows(ColumnarDbException.class, () -> ok.getLong(4));
        assertTrue(ok.toString().contains("width=8"));
    }
}
