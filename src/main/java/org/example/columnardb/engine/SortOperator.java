package org.example.columnardb.engine;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.query.SortKey;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.ValueComparator;
import org.example.columnardb.vector.Vectors;

/** ORDER BY: матеріалізує вхід, сортує перестановку (стабільно) і віддає батчами по 1024 рядки. */
public final class SortOperator extends PrefetchOperator {
    private final VectorOperator child;
    private final List<SortKey> keys;
    private ColumnVector[] columns;
    private int[] permutation;
    private int position;

    public SortOperator(VectorOperator child, List<SortKey> keys) {
        this.child = Preconditions.notNull(child, "child");
        Preconditions.check(keys != null && !keys.isEmpty(), "Sort needs at least one key");
        this.keys = List.copyOf(keys);
        for (SortKey key : this.keys) {
            child.schema().indexOf(key.column());
        }
    }

    @Override
    public TableSchema schema() {
        return child.schema();
    }

    private void materialize() {
        TableSchema schema = child.schema();
        List<List<ColumnVector>> parts = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            parts.add(new ArrayList<>());
        }
        while (child.hasNext()) {
            RecordBatch batch = child.nextBatch().compact();
            for (int c = 0; c < schema.size(); c++) {
                parts.get(c).add(batch.column(c));
            }
        }
        columns = new ColumnVector[schema.size()];
        for (int c = 0; c < columns.length; c++) {
            columns[c] = Vectors.concat(schema.column(c).type(), parts.get(c));
        }
        int n = columns[0].size();
        ColumnVector[] keyColumns = new ColumnVector[keys.size()];
        for (int k = 0; k < keyColumns.length; k++) {
            keyColumns[k] = columns[schema.indexOf(keys.get(k).column())];
        }
        permutation = IndexSorter.sort(n, (a, b) -> {
            for (int k = 0; k < keyColumns.length; k++) {
                int cmp = ValueComparator.compare(keyColumns[k], a, keyColumns[k], b);
                if (cmp != 0) {
                    return keys.get(k).ascending() ? cmp : -cmp;
                }
            }
            return 0;
        });
    }

    @Override
    protected RecordBatch computeNext() {
        if (permutation == null) {
            materialize();
        }
        if (position >= permutation.length) {
            return null;
        }
        int to = Math.min(position + RecordBatch.MAX_ROWS, permutation.length);
        RecordBatch.Builder builder = RecordBatch.builder(child.schema());
        for (int c = 0; c < columns.length; c++) {
            builder.addVector(child.schema().column(c).name(), Vectors.gather(columns[c], permutation, position, to));
        }
        position = to;
        return builder.build();
    }
}
