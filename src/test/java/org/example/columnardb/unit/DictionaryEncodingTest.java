package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.encoding.DictionaryEncoder;
import org.example.columnardb.encoding.DictionaryVector;
import org.example.columnardb.encoding.PackedCodes;
import org.example.columnardb.vector.*;
import org.junit.jupiter.api.Test;

/** U-03: Dictionary Encoding. */
class DictionaryEncodingTest {

    @Test
    void dictionaryIsBuiltInFirstSeenOrder() {
        DictionaryVector d = DictionaryEncoder.encode(new StringVector(new String[] {"UA", "US", "UA", "PL", "US"}));
        assertEquals(Encoding.DICTIONARY, d.encoding());
        assertEquals(3, d.dictionarySize());
        assertEquals("UA", d.dictionary().getString(0));
        assertEquals("US", d.dictionary().getString(1));
        assertEquals("PL", d.dictionary().getString(2));
        assertEquals(0, d.code(0));
        assertEquals(1, d.code(1));
        assertEquals(0, d.code(2));
        assertEquals(2, d.code(3));
        assertEquals("PL", d.getString(3));
    }

    @Test
    void roundTripPreservesNullsAndValues() {
        ColumnVector v = StringVector.ofNullable("a", null, "b", "a", null);
        DictionaryVector d = DictionaryEncoder.encode(v);
        assertEquals(2, d.nullCount());
        assertTrue(d.isNull(1));
        assertTrue(Vectors.contentEquals(v, DictionaryEncoder.decode(d)));
        assertTrue(Vectors.contentEquals(v, d));
    }

    @Test
    void findCodeLocatesValuesAndReportsAbsence() {
        DictionaryVector d = DictionaryEncoder.encode(new StringVector(new String[] {"x", "y"}));
        assertEquals(0, d.findCode("x"));
        assertEquals(1, d.findCode("y"));
        assertEquals(-1, d.findCode("z"));
        assertEquals(-1, d.findCode(null));
    }

    @Test
    void worksForNumericColumnsToo() {
        DictionaryVector d = DictionaryEncoder.encode(Int64Vector.ofNullable(5L, 5L, 7L, null));
        assertEquals(2, d.dictionarySize());
        assertEquals(7, d.getLong(2));
        assertEquals(1, d.findCode(7L));
        DictionaryVector f = DictionaryEncoder.encode(new Float64Vector(new double[] {1.5, 1.5, Double.NaN, Double.NaN}));
        assertEquals(2, f.dictionarySize());
        assertTrue(Double.isNaN(f.getDouble(3)));
    }

    @Test
    void codeWidthGrowsWithCardinality() {
        assertEquals(1, DictionaryEncoder.encode(distinctStrings(256)).codeWidth());
        assertEquals(2, DictionaryEncoder.encode(distinctStrings(257)).codeWidth());
        assertEquals(2, DictionaryEncoder.encode(distinctStrings(65536)).codeWidth());
        assertEquals(4, DictionaryEncoder.encode(distinctStrings(65537)).codeWidth());
    }

    @Test
    void largeCardinalityRoundTrips() {
        ColumnVector v = distinctStrings(70000);
        DictionaryVector d = DictionaryEncoder.encode(v);
        assertEquals("s69999", d.getString(69999));
        assertEquals("s300", d.getString(300));
        assertEquals("s40000", d.getString(40000));
    }

    @Test
    void emptyAndAllNullVectors() {
        DictionaryVector empty = DictionaryEncoder.encode(new StringVector(new String[0]));
        assertEquals(0, empty.size());
        assertEquals(0, empty.dictionarySize());
        DictionaryVector nulls = DictionaryEncoder.encode(StringVector.ofNullable(null, null));
        assertEquals(2, nulls.size());
        assertEquals(0, nulls.dictionarySize());
        assertTrue(nulls.isNull(0));
        assertTrue(Vectors.contentEquals(StringVector.ofNullable(null, null), DictionaryEncoder.decode(nulls)));
    }

    @Test
    void cardinalityCountsDistinctNonNullValues() {
        assertEquals(2, DictionaryEncoder.cardinality(StringVector.ofNullable("a", "b", "a", null)));
        assertEquals(0, DictionaryEncoder.cardinality(StringVector.ofNullable()));
    }

    @Test
    void corruptedDictionaryVectorsAreRejected() {
        ColumnVector dict = new StringVector(new String[] {"a", "b"});
        PackedCodes codes = PackedCodes.of(new int[] {0, 1, 1}, 2);
        assertThrows(ColumnarDbException.class, () -> new DictionaryVector(dict, codes, new NullBitmap(2)));
        PackedCodes bad = PackedCodes.of(new int[] {0, 5}, 6);
        assertThrows(ColumnarDbException.class, () -> new DictionaryVector(dict, bad, new NullBitmap(2)));
        // код поза словником у NULL-позиції допустимий
        NullBitmap nulls = new NullBitmap(2);
        nulls.setNull(1);
        assertTrue(new DictionaryVector(dict, bad, nulls).isNull(1));
        ColumnVector withNull = StringVector.ofNullable("a", null);
        assertThrows(ColumnarDbException.class,
                () -> new DictionaryVector(withNull, PackedCodes.of(new int[] {0}, 1), new NullBitmap(1)));
        ColumnVector encoded = DictionaryEncoder.encode(dict);
        assertThrows(ColumnarDbException.class,
                () -> new DictionaryVector(encoded, PackedCodes.of(new int[] {0}, 1), new NullBitmap(1)));
    }

    @Test
    void packedCodesValidateRangeAndWidth() {
        assertEquals(1, PackedCodes.of(new int[] {0, 255}, 256).width());
        assertEquals(255, PackedCodes.of(new int[] {0, 255}, 256).get(1));
        assertEquals(65535, PackedCodes.of(new int[] {65535}, 65536).get(0));
        assertEquals(70000, PackedCodes.of(new int[] {70000}, 70001).get(0));
        assertThrows(ColumnarDbException.class, () -> PackedCodes.of(new int[] {3}, 3));
        assertThrows(ColumnarDbException.class, () -> PackedCodes.of(new int[] {-1}, 3));
        assertThrows(ColumnarDbException.class, () -> PackedCodes.of(new int[] {1}, 0));
        assertEquals(0, PackedCodes.of(new int[] {0}, 0).get(0));
        assertThrows(ColumnarDbException.class, () -> PackedCodes.of(new int[1], 1).get(1));
    }

    @Test
    void sliceProducesFlatEquivalent() {
        DictionaryVector d = DictionaryEncoder.encode(new StringVector(new String[] {"a", "b", "a"}));
        assertEquals(Arrays.asList("b", "a"), Vectors.toList(d.slice(1, 2)));
        assertTrue(d.toString().contains("dict=2"));
    }

    private static ColumnVector distinctStrings(int n) {
        String[] values = new String[n];
        for (int i = 0; i < n; i++) {
            values[i] = "s" + i;
        }
        return new StringVector(values);
    }
}
