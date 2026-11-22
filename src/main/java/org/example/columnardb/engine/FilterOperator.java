package org.example.columnardb.engine;

import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.SelectionVector;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.query.Predicate;

/** Формує SelectionVector за предикатом, не копіюючи дані; порожні батчі пропускаються. */
public final class FilterOperator extends PrefetchOperator {
    private final VectorOperator child;
    private final Predicate predicate;

    public FilterOperator(VectorOperator child, Predicate predicate) {
        this.child = Preconditions.notNull(child, "child");
        this.predicate = Preconditions.notNull(predicate, "predicate");
    }

    @Override
    public TableSchema schema() {
        return child.schema();
    }

    @Override
    protected RecordBatch computeNext() {
        while (child.hasNext()) {
            RecordBatch batch = child.nextBatch();
            SelectionVector passed = PredicateEvaluator.select(predicate, batch);
            if (!passed.isEmpty()) {
                return batch.withSelection(passed);
            }
        }
        return null;
    }
}
