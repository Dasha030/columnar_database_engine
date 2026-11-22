package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.columnardb.Preconditions;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.aggregate.HashAggregateOperator;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.query.ProjectionItem;
import org.example.columnardb.query.Query;
import org.example.columnardb.query.QueryValidator;

/**
 * Компілює {@link Query} у конвеєр операторів:
 * Scan → Filter(WHERE) → [HashAggregate → Filter(HAVING)] | [Projection] → Sort → Limit.
 */
public final class QueryEngine {
    private QueryEngine() {
    }

    public static VectorOperator compile(Query query) {
        Preconditions.notNull(query, "query");
        QueryValidator.validate(query);
        TableSchema source = query.table().schema();

        VectorOperator op = new ScanOperator(query.table(), neededColumns(query, source));
        if (query.where() != null) {
            op = new FilterOperator(op, query.where());
        }
        if (query.isAggregation()) {
            op = new HashAggregateOperator(op, query.groupBy(), query.aggregates());
            if (query.having() != null) {
                op = new FilterOperator(op, query.having());
            }
        } else if (!query.projections().isEmpty()) {
            op = new ProjectionOperator(op, query.projections());
        }
        if (!query.orderBy().isEmpty()) {
            op = new SortOperator(op, query.orderBy());
        }
        if (query.limit() != null) {
            op = new LimitOperator(op, query.limit());
        }
        return op;
    }

    public static QueryResult execute(Query query) {
        VectorOperator op = compile(query);
        return new QueryResult(op.schema(), op.drain());
    }

    /** Мінімальний набір стовпчиків (у порядку схеми), потрібний запиту (для діагностики плану). */
    public static List<String> requiredColumns(Query query) {
        return neededColumns(query, query.table().schema());
    }

    private static List<String> neededColumns(Query query, TableSchema source) {
        if (!query.isAggregation() && query.projections().isEmpty()) {
            return source.names();
        }
        Set<String> needed = new LinkedHashSet<>();
        if (query.where() != null) {
            needed.addAll(query.where().columns());
        }
        needed.addAll(query.groupBy());
        for (AggregateFunc f : query.aggregates()) {
            if (f.column() != null) {
                needed.add(f.column());
            }
        }
        for (ProjectionItem item : query.projections()) {
            needed.addAll(item.expression().columns());
        }
        List<String> ordered = new ArrayList<>();
        for (String name : source.names()) {
            if (needed.contains(name)) {
                ordered.add(name);
            }
        }
        if (ordered.isEmpty()) { // напр. COUNT(*) без WHERE: потрібен хоча б один стовпчик для кількості рядків
            ordered.add(source.column(0).name());
        }
        return ordered;
    }
}
