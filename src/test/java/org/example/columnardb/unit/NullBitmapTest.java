package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.vector.NullBitmap;
import org.junit.jupiter.api.Test;

/** U-01: бітова маска валідності. */
class NullBitmapTest {

    @Test
    void newBitmapHasAllElementsValid() {
        NullBitmap b = new NullBitmap(10);
        for (int i = 0; i < 10; i++) {
            assertFalse(b.isNull(i));
            assertTrue(b.isValid(i));
        }
        assertEquals(10, b.countValid());
        assertEquals(0, b.countNulls());
        assertFalse(b.hasNulls());
    }

    @Test
    void setNullAndSetNotNullAreInverse() {
        NullBitmap b = new NullBitmap(5);
        b.setNull(2);
        assertTrue(b.isNull(2));
        assertTrue(b.hasNulls());
        b.setNotNull(2);
        assertFalse(b.isNull(2));
        assertFalse(b.hasNulls());
    }

    @Test
    void wordBoundariesAreIndependent() {
        NullBitmap b = new NullBitmap(130);
        b.setNull(63);
        b.setNull(64);
        b.setNull(129);
        for (int i = 0; i < 130; i++) {
            assertEquals(i == 63 || i == 64 || i == 129, b.isNull(i), "index " + i);
        }
        assertEquals(3, b.countNulls());
    }

    @Test
    void tailBitsOfLastWordNeverCountAsValid() {
        NullBitmap b = new NullBitmap(70);
        assertEquals(70, b.countValid());
        assertEquals(0, NullBitmap.allNull(70).countValid());
        assertEquals(64, new NullBitmap(64).countValid());
    }

    @Test
    void zeroSizeBitmapIsValid() {
        NullBitmap b = new NullBitmap(0);
        assertEquals(0, b.size());
        assertEquals(0, b.countValid());
        assertFalse(b.hasNulls());
        assertThrows(ColumnarDbException.class, () -> b.isNull(0));
    }

    @Test
    void allNullFactoryMarksEverythingNull() {
        NullBitmap b = NullBitmap.allNull(7);
        for (int i = 0; i < 7; i++) {
            assertTrue(b.isNull(i));
        }
        assertEquals(7, b.countNulls());
    }

    @Test
    void fromNullFlagsMatchesFlags() {
        NullBitmap b = NullBitmap.fromNullFlags(new boolean[] {true, false, false, true});
        assertTrue(b.isNull(0));
        assertFalse(b.isNull(1));
        assertFalse(b.isNull(2));
        assertTrue(b.isNull(3));
        assertEquals(4, b.size());
    }

    @Test
    void andRequiresBothValid() {
        NullBitmap a = NullBitmap.fromNullFlags(new boolean[] {true, false, false, false});
        NullBitmap c = NullBitmap.fromNullFlags(new boolean[] {false, true, false, false});
        NullBitmap r = a.and(c);
        assertTrue(r.isNull(0));
        assertTrue(r.isNull(1));
        assertFalse(r.isNull(2));
        assertFalse(r.isNull(3));
    }

    @Test
    void andRejectsSizeMismatch() {
        assertThrows(ColumnarDbException.class, () -> new NullBitmap(3).and(new NullBitmap(4)));
    }

    @Test
    void sliceCopiesRange() {
        NullBitmap b = NullBitmap.fromNullFlags(new boolean[] {false, true, false, true, true});
        NullBitmap s = b.slice(1, 3);
        assertEquals(3, s.size());
        assertTrue(s.isNull(0));
        assertFalse(s.isNull(1));
        assertTrue(s.isNull(2));
        assertThrows(ColumnarDbException.class, () -> b.slice(3, 3));
        assertEquals(0, b.slice(5, 0).size());
    }

    @Test
    void copyIsIndependent() {
        NullBitmap a = new NullBitmap(4);
        NullBitmap c = a.copy();
        c.setNull(0);
        assertFalse(a.isNull(0));
        assertTrue(c.isNull(0));
    }

    @Test
    void equalsAndHashCodeUseContent() {
        NullBitmap a = new NullBitmap(65);
        NullBitmap b = new NullBitmap(65);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.setNull(64);
        assertNotEquals(a, b);
        assertNotEquals(new NullBitmap(3), new NullBitmap(4));
    }

    @Test
    void invalidArgumentsAreRejectedWithColumnarDbException() {
        assertThrows(ColumnarDbException.class, () -> new NullBitmap(-1));
        NullBitmap b = new NullBitmap(3);
        assertThrows(ColumnarDbException.class, () -> b.setNull(3));
        assertThrows(ColumnarDbException.class, () -> b.setNotNull(-1));
        assertThrows(ColumnarDbException.class, () -> b.isNull(-1));
        assertThrows(ColumnarDbException.class, () -> NullBitmap.fromNullFlags(null));
    }

    @Test
    void toStringShowsBits() {
        NullBitmap b = new NullBitmap(3);
        b.setNull(1);
        assertEquals("NullBitmap[101]", b.toString());
    }
}
