package org.example.columnardb.differential;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.query.Expression;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.ProjectionItem;
import org.example.columnardb.query.Query;
import org.example.columnardb.query.QueryValidator;
import org.example.columnardb.query.SortKey;

/**
 * Еталонна рядкова (row-based) реалізація, написана незалежно від векторизованого рушія:
 * один рядок за раз, Java-об'єкти, трійкова логіка через Boolean (null = UNKNOWN).
 */
public final class ReferenceEngine {
    private ReferenceEngine() {
    }

    /** Виконує запит над вихідними рядками (Object[] за схемою таблиці), а не над даними рушія. */
    public static List<Object[]> execute(Query q, List<Object[]> sourceRows) {
        TableSchema schema = q.table().schema();
        List<Object[]> rows = sourceRows;
        if (q.where() != null) {
            rows = filter(rows, schema, q.where());
        }
        TableSchema outSchema = QueryValidator.outputSchema(q);
        List<Object[]> out;
        if (q.isAggregation()) {
            out = aggregate(q, schema, rows);
        } else if (!q.projections().isEmpty()) {
            out = project(q.projections(), schema, rows);
        } else {
            out = rows;
        }
        if (q.having() != null) {
            out = filter(out, outSchema, q.having());
        }
        if (!q.orderBy().isEmpty()) {
            out = new ArrayList<>(out);
            out.sort((a, b) -> {
                for (SortKey key : q.orderBy()) {
                    int idx = outSchema.indexOf(key.column());
                    int cmp = compareNullSmallest(a[idx], b[idx]);
                    if (cmp != 0) {
                        return key.ascending() ? cmp : -cmp;
                    }
                }
                return 0;
            });
        }
        if (q.limit() != null && out.size() > q.limit()) {
            out = out.subList(0, (int) (long) q.limit());
        }
        return out;
    }

    // ---------- предикати ----------

    private static List<Object[]> filter(List<Object[]> rows, TableSchema schema, Predicate p) {
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : rows) {
            if (Boolean.TRUE.equals(eval(p, row, schema))) {
                result.add(row);
            }
        }
        return result;
    }

    static Boolean eval(Predicate p, Object[] row, TableSchema schema) {
        if (p instanceof Predicate.Compare c) {
            Object cell = row[schema.indexOf(c.column())];
            return cell == null ? null : test(c.op(), compareValues(cell, c.value()));
        }
        if (p instanceof Predicate.In in) {
            Object cell = row[schema.indexOf(in.column())];
            if (cell == null) {
                return null;
            }
            for (Object v : in.values()) {
                if (compareValues(cell, v) == 0) {
                    return true;
                }
            }
            return false;
        }
        if (p instanceof Predicate.IsNull n) {
            return (row[schema.indexOf(n.column())] == null) != n.negated();
        }
        if (p instanceof Predicate.Not n) {
            Boolean inner = eval(n.inner(), row, schema);
            return inner == null ? null : !inner;
        }
        if (p instanceof Predicate.And a) {
            Boolean result = true;
            for (Predicate t : a.terms()) {
                Boolean v = eval(t, row, schema);
                if (Boolean.FALSE.equals(v)) {
                    return false;
                }
                if (v == null) {
                    result = null;
                }
            }
            return result;
        }
        Predicate.Or o = (Predicate.Or) p;
        Boolean result = false;
        for (Predicate t : o.terms()) {
            Boolean v = eval(t, row, schema);
            if (Boolean.TRUE.equals(v)) {
                return true;
            }
            if (v == null) {
                result = null;
            }
        }
        return result;
    }

    private static boolean test(Predicate.Op op, int cmp) {
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
        };
    }

    static int compareValues(Object a, Object b) {
        if (a instanceof String x) {
            return Integer.signum(x.compareTo((String) b));
        }
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        return x == y ? 0 : Double.compare(x, y); // NaN найбільший, -0.0 == 0.0
    }

    static int compareNullSmallest(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? -1 : 1;
        }
        return compareValues(a, b);
    }

    // ---------- проекція ----------

    private static List<Object[]> project(List<ProjectionItem> items, TableSchema schema, List<Object[]> rows) {
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] out = new Object[items.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = evalExpr(items.get(i).expression(), row, schema);
            }
            result.add(out);
        }
        return result;
    }

    private static Object evalExpr(Expression e, Object[] row, TableSchema schema) {
        if (e instanceof Expression.ColumnRef c) {
            return row[schema.indexOf(c.name())];
        }
        if (e instanceof Expression.Literal l) {
            return l.value();
        }
        Expression.Binary b = (Expression.Binary) e;
        Object l = evalExpr(b.left(), row, schema);
        Object r = evalExpr(b.right(), row, schema);
        if (l == null || r == null) {
            return null;
        }
        if (l instanceof Long x && r instanceof Long y) {
            return switch (b.op()) {
                case ADD -> Math.addExact(x, y);
                case SUB -> Math.subtractExact(x, y);
                case MUL -> Math.multiplyExact(x, y);
                case DIV -> y == 0 ? null : (Object) (x / y);
            };
        }
        double x = ((Number) l).doubleValue();
        double y = ((Number) r).doubleValue();
        return switch (b.op()) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> y == 0.0 ? null : (Object) (x / y);
        };
    }

    // ---------- агрегація ----------

    private static List<Object[]> aggregate(Query q, TableSchema schema, List<Object[]> rows) {
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        if (q.groupBy().isEmpty()) {
            groups.put(List.of(), rows);
        } else {
            for (Object[] row : rows) {
                List<Object> key = new ArrayList<>();
                for (String column : q.groupBy()) {
                    Object v = row[schema.indexOf(column)];
                    key.add(v instanceof Double d && d == 0.0 ? (Object) 0.0 : v);
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> entry : groups.entrySet()) {
            List<Object> out = new ArrayList<>(entry.getKey());
            for (AggregateFunc f : q.aggregates()) {
                out.add(computeAggregate(f, schema, entry.getValue()));
            }
            result.add(out.toArray());
        }
        return result;
    }

    private static Object computeAggregate(AggregateFunc f, TableSchema schema, List<Object[]> rows) {
        if (f.kind() == AggregateFunc.Kind.COUNT_STAR) {
            return (long) rows.size();
        }
        int idx = schema.indexOf(f.column());
        List<Object> values = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[idx] != null) {
                values.add(row[idx]);
            }
        }
        switch (f.kind()) {
            case COUNT:
                return (long) values.size();
            case SUM:
                if (values.isEmpty()) {
                    return null;
                }
                if (values.get(0) instanceof Long) {
                    long sum = 0;
                    for (Object v : values) {
                        sum = Math.addExact(sum, (Long) v);
                    }
                    return sum;
                }
                double dsum = 0;
                for (Object v : values) {
                    dsum += (Double) v;
                }
                return dsum;
            case AVG:
                if (values.isEmpty()) {
                    return null;
                }
                double total = 0;
                for (Object v : values) {
                    total += ((Number) v).doubleValue();
                }
                return total / values.size();
            default:
                if (values.isEmpty()) {
                    return null;
                }
                Object best = values.get(0);
                for (Object v : values) {
                    int cmp = compareValues(v, best);
                    if (f.kind() == AggregateFunc.Kind.MIN ? cmp < 0 : cmp > 0) {
                        best = v;
                    }
                }
                return best;
        }
    }

    static String show(Object[] row) {
        return Arrays.toString(row);
    }
}
