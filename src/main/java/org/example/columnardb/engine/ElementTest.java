package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.query.Predicate.Op;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.ValueComparator;

/** Перевірка одного не-NULL елемента вектора проти літерала. */
@FunctionalInterface
interface ElementTest {
    boolean test(ColumnVector vector, int i);

    static boolean apply(Op op, int cmp) {
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
        };
    }

    /** Будує перевірку «елемент op літерал» для заданого типу стовпчика. */
    static ElementTest compare(DataType type, Op op, Object literal) {
        switch (type) {
            case INT64:
                if (literal instanceof Long l) {
                    long lit = l;
                    return (v, i) -> apply(op, Long.compare(v.getLong(i), lit));
                }
                if (literal instanceof Double d) {
                    double lit = d;
                    return (v, i) -> apply(op, ValueComparator.compareDoubles((double) v.getLong(i), lit));
                }
                break;
            case FLOAT64:
                if (literal instanceof Number n) {
                    double lit = n.doubleValue();
                    return (v, i) -> apply(op, ValueComparator.compareDoubles(v.getDouble(i), lit));
                }
                break;
            case STRING:
                if (literal instanceof String s) {
                    return (v, i) -> apply(op, Integer.signum(v.getString(i).compareTo(s)));
                }
                break;
            default:
                break;
        }
        throw new ColumnarDbException("Cannot compare " + type + " column with literal " + literal);
    }

    /** Елемент збігається з будь-яким із літералів. */
    static ElementTest in(DataType type, List<Object> literals) {
        List<ElementTest> tests = new ArrayList<>();
        for (Object literal : literals) {
            tests.add(compare(type, Op.EQ, literal));
        }
        return (v, i) -> {
            for (ElementTest t : tests) {
                if (t.test(v, i)) {
                    return true;
                }
            }
            return false;
        };
    }
}
