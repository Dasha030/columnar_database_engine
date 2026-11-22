package org.example.columnardb.corpus;

import java.util.List;
import java.util.Objects;

/** Порівняння результатів запитів як списків рядків. */
public final class Rows {
    private Rows() {
    }

    public static boolean cellEquals(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) {
            return x.equals(y) || x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    public static String describe(List<Object[]> rows, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            sb.append(java.util.Arrays.toString(rows.get(i))).append('\n');
        }
        return sb.toString();
    }

    /** Повертає null, якщо результати збігаються, інакше опис першої розбіжності. */
    public static String diff(List<Object[]> expected, List<Object[]> actual) {
        if (expected.size() != actual.size()) {
            return "row count: expected " + expected.size() + " but was " + actual.size();
        }
        for (int r = 0; r < expected.size(); r++) {
            Object[] e = expected.get(r);
            Object[] a = actual.get(r);
            if (e.length != a.length) {
                return "row " + r + " width: expected " + e.length + " but was " + a.length;
            }
            for (int c = 0; c < e.length; c++) {
                if (!cellEquals(e[c], a[c])) {
                    return "row " + r + " col " + c + ": expected " + e[c] + " but was " + a[c];
                }
            }
        }
        return null;
    }

    public static void assertSame(List<Object[]> expected, List<Object[]> actual) {
        String difference = diff(expected, actual);
        if (difference != null) {
            throw new AssertionError(difference + "\nexpected:\n" + describe(expected, 5)
                    + "actual:\n" + describe(actual, 5));
        }
    }

    public static List<Object[]> of(Object[]... rows) {
        return List.of(rows);
    }

    public static Object[] row(Object... cells) {
        return cells;
    }
}
