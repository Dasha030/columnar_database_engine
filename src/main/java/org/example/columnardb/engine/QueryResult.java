package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;

/** Результат виконання запиту: схема та батчі. */
public final class QueryResult {
    private final TableSchema schema;
    private final List<RecordBatch> batches;

    public QueryResult(TableSchema schema, List<RecordBatch> batches) {
        this.schema = schema;
        this.batches = List.copyOf(batches);
    }

    public TableSchema schema() {
        return schema;
    }

    public List<RecordBatch> batches() {
        return batches;
    }

    public long rowCount() {
        long total = 0;
        for (RecordBatch b : batches) {
            total += b.activeRowCount();
        }
        return total;
    }

    /** Усі рядки результату в порядку виконання. */
    public List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        for (RecordBatch b : batches) {
            rows.addAll(b.rows());
        }
        return rows;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(String.join(" | ", schema.names())).append('\n');
        for (Object[] row : rows()) {
            for (int c = 0; c < row.length; c++) {
                sb.append(c == 0 ? "" : " | ").append(row[c]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
