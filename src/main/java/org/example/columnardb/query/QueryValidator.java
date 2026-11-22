package org.example.columnardb.query;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.columnardb.Preconditions;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.ColumnDef;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.vector.DataType;

/** Статична перевірка запиту проти схеми та виведення схеми результату. */
public final class QueryValidator {
    private QueryValidator() {
    }

    /** Перевіряє запит; кидає {@link org.example.columnardb.ColumnarDbException} при помилці. */
    public static void validate(Query query) {
        outputSchema(query);
    }

    /** Схема результату запиту (після перевірки всіх його частин). */
    public static TableSchema outputSchema(Query q) {
        TableSchema source = q.table().schema();
        if (q.where() != null) {
            validatePredicate(q.where(), source);
        }
        Preconditions.check(q.limit() == null || q.limit() >= 0, "LIMIT must be non-negative");

        TableSchema output;
        if (q.isAggregation()) {
            Preconditions.check(q.projections().isEmpty(), "Cannot combine projection with aggregation");
            output = aggregationSchema(q, source);
        } else {
            Preconditions.check(q.having() == null, "HAVING requires aggregation");
            output = q.projections().isEmpty() ? source : projectionSchema(q.projections(), source);
        }
        if (q.having() != null) {
            validatePredicate(q.having(), output);
        }
        for (SortKey key : q.orderBy()) {
            Preconditions.check(output.has(key.column()), "ORDER BY references unknown column: " + key.column());
        }
        return output;
    }

    private static TableSchema aggregationSchema(Query q, TableSchema source) {
        TableSchema.Builder builder = TableSchema.builder();
        Set<String> names = new HashSet<>();
        for (String key : q.groupBy()) {
            ColumnDef def = source.column(key);
            Preconditions.check(names.add(key), "Duplicate GROUP BY column: " + key);
            builder.addColumn(def.name(), def.type(), def.nullable());
        }
        for (AggregateFunc f : q.aggregates()) {
            DataType type = f.outputType(source);
            Preconditions.check(names.add(f.alias()), "Duplicate output name: " + f.alias());
            builder.addColumn(f.alias(), type, f.outputNullable());
        }
        return builder.build();
    }

    private static TableSchema projectionSchema(List<ProjectionItem> items, TableSchema source) {
        TableSchema.Builder builder = TableSchema.builder();
        Set<String> names = new HashSet<>();
        for (ProjectionItem item : items) {
            Preconditions.check(names.add(item.name()), "Duplicate output name: " + item.name());
            builder.addColumn(item.name(), item.expression().typeOf(source), item.expression().nullable(source));
        }
        return builder.build();
    }

    /** Перевіряє існування стовпчиків і сумісність типів літералів. */
    public static void validatePredicate(Predicate p, TableSchema schema) {
        switch (p) {
            case Predicate.Compare c -> checkLiteral(schema.typeOf(c.column()), c.column(), c.value());
            case Predicate.In in -> {
                DataType type = schema.typeOf(in.column());
                in.values().forEach(v -> checkLiteral(type, in.column(), v));
            }
            case Predicate.IsNull n -> schema.indexOf(n.column());
            case Predicate.And a -> a.terms().forEach(t -> validatePredicate(t, schema));
            case Predicate.Or o -> o.terms().forEach(t -> validatePredicate(t, schema));
            case Predicate.Not n -> validatePredicate(n.inner(), schema);
        }
    }

    private static void checkLiteral(DataType columnType, String column, Object literal) {
        boolean text = literal instanceof String;
        Preconditions.check(text == (columnType == DataType.STRING),
                "Cannot compare " + columnType + " column '" + column + "' with literal " + literal);
    }
}
