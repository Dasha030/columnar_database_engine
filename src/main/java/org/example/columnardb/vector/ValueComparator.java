package org.example.columnardb.vector;

/** Єдина семантика порівняння значень для фільтрів, сортування та MIN/MAX. */
public final class ValueComparator {
    private ValueComparator() {
    }

    /** Порівняння double: NaN більший за все і рівний собі; -0.0 == 0.0. */
    public static int compareDoubles(double a, double b) {
        if (a < b) {
            return -1;
        }
        if (a > b) {
            return 1;
        }
        boolean nanA = Double.isNaN(a);
        boolean nanB = Double.isNaN(b);
        if (nanA || nanB) {
            return nanA == nanB ? 0 : nanA ? 1 : -1;
        }
        return 0;
    }

    /** Порівняння двох елементів; NULL вважається найменшим значенням. Знак результату: -1, 0, 1. */
    public static int compare(ColumnVector a, int i, ColumnVector b, int j) {
        boolean na = a.isNull(i);
        boolean nb = b.isNull(j);
        if (na || nb) {
            return na == nb ? 0 : na ? -1 : 1;
        }
        return switch (a.type()) {
            case INT64 -> Long.compare(a.getLong(i), b.getLong(j));
            case FLOAT64 -> compareDoubles(a.getDouble(i), b.getDouble(j));
            case STRING -> Integer.signum(a.getString(i).compareTo(b.getString(j)));
        };
    }
}
