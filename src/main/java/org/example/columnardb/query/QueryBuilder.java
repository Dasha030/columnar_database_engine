package org.example.columnardb.query;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;

/** Fluent-білдер запитів (замість SQL-парсера). */
public final class QueryBuilder {
    private final InMemoryTable table;
    private final List<ProjectionItem> projections = new ArrayList<>();
    private final List<String> groupBy = new ArrayList<>();
    private final List<AggregateFunc> aggregates = new ArrayList<>();
    private final List<SortKey> orderBy = new ArrayList<>();
    private Predicate where;
    private Predicate having;
    private Long limit;

    private QueryBuilder(InMemoryTable table) {
        this.table = table;
    }

    public static QueryBuilder from(InMemoryTable table) {
        return new QueryBuilder(Preconditions.notNull(table, "table"));
    }

    /** Проекція простих стовпчиків за іменами. */
    public QueryBuilder select(String... columns) {
        for (String column : columns) {
            projections.add(new ProjectionItem(column, Expression.col(column)));
        }
        return this;
    }

    public QueryBuilder project(String name, Expression expression) {
        projections.add(new ProjectionItem(name, expression));
        return this;
    }

    /** Кілька викликів where поєднуються через AND. */
    public QueryBuilder where(Predicate predicate) {
        Preconditions.notNull(predicate, "predicate");
        where = where == null ? predicate : Predicate.and(where, predicate);
        return this;
    }

    public QueryBuilder groupBy(String... columns) {
        groupBy.addAll(List.of(columns));
        return this;
    }

    public QueryBuilder aggregate(AggregateFunc... functions) {
        aggregates.addAll(List.of(functions));
        return this;
    }

    public QueryBuilder having(Predicate predicate) {
        Preconditions.notNull(predicate, "predicate");
        having = having == null ? predicate : Predicate.and(having, predicate);
        return this;
    }

    public QueryBuilder orderBy(String column) {
        orderBy.add(new SortKey(column, true));
        return this;
    }

    public QueryBuilder orderByDesc(String column) {
        orderBy.add(new SortKey(column, false));
        return this;
    }

    public QueryBuilder limit(long count) {
        Preconditions.check(count >= 0, "LIMIT must be non-negative: " + count);
        limit = count;
        return this;
    }

    public Query build() {
        Query query = new Query(table, projections, where, groupBy, aggregates, having, orderBy, limit);
        QueryValidator.validate(query);
        return query;
    }
}
