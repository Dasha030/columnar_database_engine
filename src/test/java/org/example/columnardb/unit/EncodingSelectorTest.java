package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.example.columnardb.encoding.EncodingSelector;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

class EncodingSelectorTest {

    @Test
    void longRunsSelectRle() {
        long[] v = new long[100];
        for (int i = 0; i < v.length; i++) {
            v[i] = i / 20;
        }
        assertEquals(Encoding.RLE, EncodingSelector.choose(new Int64Vector(v)));
    }

    @Test
    void lowCardinalityWithoutRunsSelectsDictionary() {
        String[] values = new String[100];
        for (int i = 0; i < values.length; i++) {
            values[i] = "c" + (i % 3);
        }
        assertEquals(Encoding.DICTIONARY, EncodingSelector.choose(new StringVector(values)));
    }

    @Test
    void uniqueSmallIntegersSelectBitPacking() {
        long[] v = new long[100];
        for (int i = 0; i < v.length; i++) {
            v[i] = (i * 37) % 100; // унікальні, без серій
        }
        assertEquals(Encoding.BIT_PACKED, EncodingSelector.choose(new Int64Vector(v)));
    }

    @Test
    void wideRandomValuesStayFlat() {
        Random rnd = new Random(1);
        long[] v = new long[100];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextLong();
        }
        assertEquals(Encoding.FLAT, EncodingSelector.choose(new Int64Vector(v)));
        assertEquals(Encoding.FLAT, EncodingSelector.choose(new Float64Vector(new double[] {1.1, 2.2, 3.3})));
        assertEquals(Encoding.FLAT, EncodingSelector.choose(new StringVector(new String[] {"a", "b", "c"})));
    }

    @Test
    void emptyVectorStaysFlat() {
        assertEquals(Encoding.FLAT, EncodingSelector.choose(new Int64Vector(new long[0])));
    }

    @Test
    void compressPreservesContentForEveryChoice() {
        ColumnVector[] samples = {
            Int64Vector.ofNullable(1L, 1L, 1L, 1L, null, null, null, null),
            StringVector.ofNullable("a", "b", "a", "b", "a", "b", null, "a"),
            Int64Vector.ofNullable(3L, 9L, 5L, 1L, 7L),
            new Float64Vector(new double[] {0.5, 9.25, 3.0})
        };
        for (ColumnVector sample : samples) {
            ColumnVector compressed = EncodingSelector.compress(sample);
            assertTrue(Vectors.contentEquals(sample, compressed), compressed.toString());
        }
    }

    @Test
    void encodeWithForcesRequestedEncoding() {
        ColumnVector v = new Int64Vector(new long[] {1, 2, 3});
        for (Encoding e : Encoding.values()) {
            ColumnVector encoded = EncodingSelector.encodeWith(v, e);
            assertEquals(e, encoded.encoding());
            assertTrue(Vectors.contentEquals(v, encoded));
        }
    }

    @Test
    void thresholdsAreInclusive() {
        // рівно 4 елементи на серію -> RLE
        assertEquals(Encoding.RLE, EncodingSelector.choose(new Int64Vector(new long[] {4, 4, 4, 4, 5, 5, 5, 5})));
        // 3 елементи на серію -> вже не RLE
        assertNotEquals(Encoding.RLE, EncodingSelector.choose(new Int64Vector(new long[] {4, 4, 4, 5, 5, 5})));
        // рівно 2 рядки на унікальне значення -> словник
        assertEquals(Encoding.DICTIONARY, EncodingSelector.choose(new StringVector(new String[] {"a", "b", "a", "b"})));
        assertNotEquals(Encoding.DICTIONARY,
                EncodingSelector.choose(new StringVector(new String[] {"a", "b", "c", "a", "b"})));
        // ширина рівно 32 біти -> пакуємо, 33 -> ні
        assertEquals(Encoding.BIT_PACKED, EncodingSelector.choose(new Int64Vector(new long[] {0, 1L << 32 - 1, 7})));
        assertEquals(Encoding.FLAT, EncodingSelector.choose(new Int64Vector(new long[] {0, 1L << 32, 7})));
    }
}
