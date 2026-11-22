package org.example.columnardb.query;

import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;

/** Незмінний опис аналітичного запиту (результат {@link QueryBuilder#build()}). */
public record Query(InMemoryTable table, List<ProjectionItem> projections, Predicate where,
                    List<String> groupBy, List<AggregateFunc> aggregates, Predicate having,
                    List<SortKey> orderBy, Long limit) {

    public Query {
        Preconditions.notNull(table, "table");
        projections = List.copyOf(projections);
        groupBy = List.copyOf(groupBy);
        aggregates = List.copyOf(aggregates);
        orderBy = List.copyOf(orderBy);
    }

    public boolean isAggregation() {
        return !groupBy.isEmpty() || !aggregates.isEmpty();
    }
}
