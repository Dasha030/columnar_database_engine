package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;

/** Читає батчі таблиці, залишаючи лише потрібні стовпчики (column pruning без копіювання). */
public final class ScanOperator extends PrefetchOperator {
    private final List<RecordBatch> batches;
    private final List<String> columns;
    private final TableSchema schema;
    private int position;

    public ScanOperator(InMemoryTable table, List<String> columns) {
        Preconditions.notNull(table, "table");
        Preconditions.notNull(columns, "columns");
        Preconditions.check(!columns.isEmpty(), "Scan needs at least one column");
        this.batches = new ArrayList<>(table.batches());
        this.columns = List.copyOf(columns);
        this.schema = table.schema().project(this.columns);
    }

    public ScanOperator(InMemoryTable table) {
        this(table, table.schema().names());
    }

    @Override
    public TableSchema schema() {
        return schema;
    }

    @Override
    protected RecordBatch computeNext() {
        if (position >= batches.size()) {
            return null;
        }
        RecordBatch batch = batches.get(position++);
        return columns.equals(batch.schema().names()) ? batch : batch.project(columns);
    }
}
