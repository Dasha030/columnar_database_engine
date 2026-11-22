package org.example.columnardb.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.columnardb.Preconditions;

/**
 * Предикат фільтрації. Семантика NULL — трійкова логіка SQL: порівняння з NULL дає UNKNOWN,
 * а рядок проходить фільтр лише при TRUE. NaN вважається більшим за будь-яке число і рівним собі.
 */
public sealed interface Predicate {

    enum Op { EQ, NE, LT, LE, GT, GE }

    record Compare(String column, Op op, Object value) implements Predicate {
        public Compare {
            Preconditions.check(column != null && !column.isBlank(), "Column name must not be blank");
            Preconditions.notNull(op, "op");
            value = normalizeLiteral(value);
        }
    }

    record In(String column, List<Object> values) implements Predicate {
        public In {
            Preconditions.check(column != null && !column.isBlank(), "Column name must not be blank");
            Preconditions.notNull(values, "values");
            Preconditions.check(!values.isEmpty(), "IN list must not be empty");
            List<Object> normalized = new ArrayList<>();
            for (Object v : values) {
                normalized.add(normalizeLiteral(v));
            }
            boolean text = normalized.get(0) instanceof String;
            for (Object v : normalized) {
                Preconditions.check((v instanceof String) == text, "IN list mixes strings and numbers");
            }
            values = List.copyOf(normalized);
        }
    }

    record IsNull(String column, boolean negated) implements Predicate {
        public IsNull {
            Preconditions.check(column != null && !column.isBlank(), "Column name must not be blank");
        }
    }

    record And(List<Predicate> terms) implements Predicate {
        public And {
            Preconditions.check(terms != null && !terms.isEmpty(), "AND needs at least one term");
            terms = List.copyOf(terms);
        }
    }

    record Or(List<Predicate> terms) implements Predicate {
        public Or {
            Preconditions.check(terms != null && !terms.isEmpty(), "OR needs at least one term");
            terms = List.copyOf(terms);
        }
    }

    record Not(Predicate inner) implements Predicate {
        public Not {
            Preconditions.notNull(inner, "inner");
        }
    }

    /** Приводить літерал до Long, Double або String; інші типи відхиляються. */
    static Object normalizeLiteral(Object value) {
        Preconditions.check(value != null, "Literal must not be null (use isNull)");
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        Preconditions.check(value instanceof Long || value instanceof Double || value instanceof String,
                "Unsupported literal type: " + value.getClass().getSimpleName());
        return value;
    }

    static Predicate eq(String column, Object value) {
        return new Compare(column, Op.EQ, value);
    }

    static Predicate ne(String column, Object value) {
        return new Compare(column, Op.NE, value);
    }

    static Predicate lt(String column, Object value) {
        return new Compare(column, Op.LT, value);
    }

    static Predicate le(String column, Object value) {
        return new Compare(column, Op.LE, value);
    }

    static Predicate gt(String column, Object value) {
        return new Compare(column, Op.GT, value);
    }

    static Predicate ge(String column, Object value) {
        return new Compare(column, Op.GE, value);
    }

    static Predicate between(String column, Object low, Object high) {
        return and(ge(column, low), le(column, high));
    }

    static Predicate in(String column, Object... values) {
        return new In(column, List.of(values));
    }

    static Predicate isNull(String column) {
        return new IsNull(column, false);
    }

    static Predicate isNotNull(String column) {
        return new IsNull(column, true);
    }

    static Predicate and(Predicate... terms) {
        return new And(List.of(terms));
    }

    static Predicate or(Predicate... terms) {
        return new Or(List.of(terms));
    }

    static Predicate not(Predicate inner) {
        return new Not(inner);
    }

    /** Імена всіх стовпчиків, на які посилається предикат, у порядку першої появи. */
    default Set<String> columns() {
        Set<String> result = new LinkedHashSet<>();
        collectColumns(this, result);
        return result;
    }

    private static void collectColumns(Predicate p, Set<String> out) {
        switch (p) {
            case Compare c -> out.add(c.column());
            case In in -> out.add(in.column());
            case IsNull n -> out.add(n.column());
            case And a -> a.terms().forEach(t -> collectColumns(t, out));
            case Or o -> o.terms().forEach(t -> collectColumns(t, out));
            case Not n -> collectColumns(n.inner(), out);
        }
    }
}
