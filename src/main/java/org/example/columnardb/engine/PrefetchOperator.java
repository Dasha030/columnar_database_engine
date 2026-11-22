package org.example.columnardb.engine;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.RecordBatch;

/** Базовий клас: підклас реалізує computeNext(), який повертає null по завершенні даних. */
public abstract class PrefetchOperator implements VectorOperator {
    private RecordBatch next;
    private boolean exhausted;

    /** Обчислює наступний непорожній батч або null, якщо даних більше немає. */
    protected abstract RecordBatch computeNext();

    @Override
    public final boolean hasNext() {
        if (next == null && !exhausted) {
            next = computeNext();
            exhausted = next == null;
        }
        return next != null;
    }

    @Override
    public final RecordBatch nextBatch() {
        if (!hasNext()) {
            throw new ColumnarDbException("No more batches");
        }
        RecordBatch result = next;
        next = null;
        return result;
    }
}
