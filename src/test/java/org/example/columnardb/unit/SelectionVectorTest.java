package org.example.columnardb.unit;

import static org.junit.jupiter.api.Assertions.*;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.SelectionVector;
import org.junit.jupiter.api.Test;

class SelectionVectorTest {

    private static SelectionVector sv(int... indices) {
        return new SelectionVector(indices, indices.length);
    }

    @Test
    void storesIndicesAndLength() {
        SelectionVector s = new SelectionVector(new int[] {2, 5, 9, 0, 0}, 3);
        assertEquals(3, s.size());
        assertFalse(s.isEmpty());
        assertEquals(5, s.getIndex(1));
        assertArrayEquals(new int[] {2, 5, 9}, s.toArray());
        assertThrows(ColumnarDbException.class, () -> s.getIndex(3));
    }

    @Test
    void rejectsNonIncreasingOrNegativeIndices() {
        assertThrows(ColumnarDbException.class, () -> sv(3, 2));
        assertThrows(ColumnarDbException.class, () -> sv(2, 2));
        assertThrows(ColumnarDbException.class, () -> sv(-1, 0));
        assertThrows(ColumnarDbException.class, () -> new SelectionVector(new int[2], 3));
        assertThrows(ColumnarDbException.class, () -> new SelectionVector(new int[2], -1));
        assertThrows(ColumnarDbException.class, () -> new SelectionVector(null, 0));
    }

    @Test
    void allAndEmptyFactories() {
        assertArrayEquals(new int[] {0, 1, 2}, SelectionVector.all(3).toArray());
        assertTrue(SelectionVector.all(0).isEmpty());
        assertTrue(SelectionVector.empty().isEmpty());
        assertThrows(ColumnarDbException.class, () -> SelectionVector.all(-1));
    }

    @Test
    void intersectKeepsCommonIndices() {
        assertEquals(sv(2, 7), sv(1, 2, 5, 7, 9).intersect(sv(2, 3, 7, 8)));
        assertTrue(sv(1, 2).intersect(sv(3, 4)).isEmpty());
        assertTrue(sv(1, 2).intersect(SelectionVector.empty()).isEmpty());
        assertEquals(sv(1, 2), sv(1, 2).intersect(sv(0, 1, 2, 3)));
    }

    @Test
    void unionMergesWithoutDuplicates() {
        assertEquals(sv(1, 2, 3, 5, 8), sv(1, 3, 5).union(sv(2, 3, 8)));
        assertEquals(sv(4), SelectionVector.empty().union(sv(4)));
        assertEquals(sv(4), sv(4).union(SelectionVector.empty()));
        assertEquals(sv(0, 1, 2, 3), sv(0, 1).union(sv(2, 3)));
        assertEquals(sv(0, 1, 2, 3), sv(2, 3).union(sv(0, 1)));
    }

    @Test
    void complementReturnsMissingRows() {
        assertEquals(sv(0, 2, 4), sv(1, 3).complement(5));
        assertEquals(sv(0, 1, 2), SelectionVector.empty().complement(3));
        assertTrue(SelectionVector.all(4).complement(4).isEmpty());
        assertThrows(ColumnarDbException.class, () -> sv(5).complement(5));
        assertThrows(ColumnarDbException.class, () -> sv(1).complement(-1));
    }

    @Test
    void headTruncates() {
        assertEquals(sv(1, 2), sv(1, 2, 3).head(2));
        assertEquals(sv(1, 2, 3), sv(1, 2, 3).head(10));
        assertTrue(sv(1, 2, 3).head(0).isEmpty());
        assertThrows(ColumnarDbException.class, () -> sv(1).head(-1));
    }

    @Test
    void equalsHashCodeAndToString() {
        assertEquals(sv(1, 2), new SelectionVector(new int[] {1, 2, 99}, 2));
        assertEquals(sv(1, 2).hashCode(), new SelectionVector(new int[] {1, 2, 99}, 2).hashCode());
        assertNotEquals(sv(1, 2), sv(1, 3));
        assertNotEquals(sv(1, 2), sv(1));
        assertEquals("SelectionVector[1, 2]", sv(1, 2).toString());
    }
}
