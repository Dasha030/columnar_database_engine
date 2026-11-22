package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;

/** Оператор векторизованої Volcano-моделі (batch-at-a-time). */
public interface VectorOperator {
    /** Схема батчів, які повертає оператор. */
    TableSchema schema();

    boolean hasNext();

    RecordBatch nextBatch();

    /** Вичитує всі залишені батчі. */
    default List<RecordBatch> drain() {
        List<RecordBatch> batches = new ArrayList<>();
        while (hasNext()) {
            batches.add(nextBatch());
        }
        return batches;
    }
}
