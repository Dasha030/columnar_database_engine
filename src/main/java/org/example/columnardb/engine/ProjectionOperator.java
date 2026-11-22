package org.example.columnardb.engine;

import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.query.ProjectionItem;

/** Обчислює вирази проекції лише для активних рядків; результат — батч без вибору. */
public final class ProjectionOperator extends PrefetchOperator {
    private final VectorOperator child;
    private final List<ProjectionItem> items;
    private final TableSchema schema;

    public ProjectionOperator(VectorOperator child, List<ProjectionItem> items) {
        this.child = Preconditions.notNull(child, "child");
        Preconditions.check(items != null && !items.isEmpty(), "Projection needs at least one item");
        this.items = List.copyOf(items);
        TableSchema.Builder builder = TableSchema.builder();
        TableSchema input = child.schema();
        for (ProjectionItem item : this.items) {
            builder.addColumn(item.name(), item.expression().typeOf(input), item.expression().nullable(input));
        }
        this.schema = builder.build();
    }

    @Override
    public TableSchema schema() {
        return schema;
    }

    @Override
    protected RecordBatch computeNext() {
        while (child.hasNext()) {
            RecordBatch batch = child.nextBatch();
            if (batch.activeRowCount() == 0) {
                continue;
            }
            RecordBatch.Builder builder = RecordBatch.builder(schema);
            for (ProjectionItem item : items) {
                builder.addVector(item.name(), ExpressionEvaluator.evaluate(item.expression(), batch));
            }
            return builder.build();
        }
        return null;
    }
}
