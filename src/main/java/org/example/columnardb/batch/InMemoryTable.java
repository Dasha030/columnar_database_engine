package org.example.columnardb.batch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.example.columnardb.Preconditions;
import org.example.columnardb.encoding.EncodingSelector;
import org.example.columnardb.vector.ColumnVector;

/** Стовпчикове табличне сховище в пам'яті: послідовність батчів до 1024 рядків. */
public final class InMemoryTable {
    private final TableSchema schema;
    private final UnaryOperator<ColumnVector> encoder; // null = зберігати FLAT
    private final List<RecordBatch> batches = new ArrayList<>();
    private final List<Object[]> pendingRows = new ArrayList<>();
    private long rowCount;

    public InMemoryTable(TableSchema schema) {
        this(schema, false);
    }

    /** При autoEncode кожен збережений вектор автоматично стискається {@link EncodingSelector}. */
    public InMemoryTable(TableSchema schema, boolean autoEncode) {
        this(schema, autoEncode ? EncodingSelector::compress : null);
    }

    /** Кожен збережений вектор проходить через вказаний кодувальник (null = без кодування). */
    public InMemoryTable(TableSchema schema, UnaryOperator<ColumnVector> encoder) {
        this.schema = Preconditions.notNull(schema, "schema");
        this.encoder = encoder;
    }

    public TableSchema schema() {
        return schema;
    }

    public long rowCount() {
        return rowCount + pendingRows.size();
    }

    /** Додає батч; великі батчі розрізаються на шматки по 1024 рядки. */
    public void append(RecordBatch batch) {
        Preconditions.notNull(batch, "batch");
        Preconditions.check(batch.schema().equals(schema), "Batch schema does not match table schema");
        flushPending();
        RecordBatch dense = batch.compact();
        if (dense.rowCount() == 0) {
            return;
        }
        for (int from = 0; from < dense.rowCount(); from += RecordBatch.MAX_ROWS) {
            int length = Math.min(RecordBatch.MAX_ROWS, dense.rowCount() - from);
            RecordBatch chunk = from == 0 && length == dense.rowCount() ? dense : dense.slice(from, length);
            store(chunk);
        }
    }

    /** Додає один рядок; рядки буферизуються й скидаються у батч по 1024. */
    public void appendRow(Object... values) {
        Preconditions.notNull(values, "values");
        pendingRows.add(values.clone());
        if (pendingRows.size() >= RecordBatch.MAX_ROWS) {
            flushPending();
        }
    }

    public void appendRows(List<Object[]> rows) {
        for (Object[] row : rows) {
            appendRow(row);
        }
    }

    private void flushPending() {
        if (pendingRows.isEmpty()) {
            return;
        }
        RecordBatch batch = RecordBatch.fromRows(schema, pendingRows);
        pendingRows.clear();
        store(batch);
    }

    private void store(RecordBatch chunk) {
        RecordBatch stored = chunk;
        if (encoder != null) {
            RecordBatch.Builder builder = RecordBatch.builder(schema);
            for (int c = 0; c < schema.size(); c++) {
                ColumnVector encoded = encoder.apply(chunk.column(c));
                builder.addVector(schema.column(c).name(), encoded);
            }
            stored = builder.build();
        }
        batches.add(stored);
        rowCount += stored.rowCount();
    }

    /** Усі батчі таблиці (незмінний список). */
    public List<RecordBatch> batches() {
        flushPending();
        return Collections.unmodifiableList(batches);
    }

    /** Усі рядки таблиці як масиви значень (для тестів і еталонів). */
    public List<Object[]> rows() {
        List<Object[]> all = new ArrayList<>();
        for (RecordBatch batch : batches()) {
            all.addAll(batch.rows());
        }
        return all;
    }
}
