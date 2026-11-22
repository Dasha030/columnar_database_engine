package org.example.columnardb.engine;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;

/** LIMIT n: передає не більше n активних рядків, обрізаючи SelectionVector останнього батча. */
public final class LimitOperator implements VectorOperator {
    private final VectorOperator child;
    private long remaining;

    public LimitOperator(VectorOperator child, long limit) {
        this.child = Preconditions.notNull(child, "child");
        Preconditions.check(limit >= 0, "LIMIT must be non-negative: " + limit);
        this.remaining = limit;
    }

    @Override
    public TableSchema schema() {
        return child.schema();
    }

    @Override
    public boolean hasNext() {
        return remaining > 0 && child.hasNext();
    }

    @Override
    public RecordBatch nextBatch() {
        if (!hasNext()) {
            throw new ColumnarDbException("No more batches");
        }
        RecordBatch batch = child.nextBatch();
        int active = batch.activeRowCount();
        if (active <= remaining) {
            remaining -= active;
            return batch;
        }
        RecordBatch truncated = batch.withSelection(batch.selection().head((int) remaining));
        remaining = 0;
        return truncated;
    }
}
