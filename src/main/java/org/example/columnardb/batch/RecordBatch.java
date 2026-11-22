package org.example.columnardb.batch;

import java.util.ArrayList;
import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.VectorBuilder;

/**
 * Горизонтальний фрагмент таблиці (до {@value #MAX_ROWS} рядків): вектори за схемою та
 * необов'язковий SelectionVector, що позначає «живі» рядки.
 */
public final class RecordBatch {
    public static final int MAX_ROWS = 1024;

    private final TableSchema schema;
    private final ColumnVector[] vectors;
    private final int rowCount;
    private final SelectionVector selection; // null = усі рядки активні

    private RecordBatch(TableSchema schema, ColumnVector[] vectors, int rowCount, SelectionVector selection) {
        this.schema = schema;
        this.vectors = vectors;
        this.rowCount = rowCount;
        this.selection = selection;
    }

    public static Builder builder(TableSchema schema) {
        return new Builder(Preconditions.notNull(schema, "schema"));
    }

    /** Будує батч із рядків (Object[] за порядком схеми). */
    public static RecordBatch fromRows(TableSchema schema, List<Object[]> rows) {
        List<VectorBuilder> builders = new ArrayList<>();
        for (ColumnDef c : schema.columns()) {
            builders.add(new VectorBuilder(c.type(), rows.size()));
        }
        for (Object[] row : rows) {
            Preconditions.check(row.length == schema.size(),
                    "Row has " + row.length + " values, schema has " + schema.size());
            for (int c = 0; c < row.length; c++) {
                builders.get(c).appendObject(row[c]);
            }
        }
        Builder b = builder(schema);
        for (int c = 0; c < schema.size(); c++) {
            b.addVector(schema.column(c).name(), builders.get(c).build());
        }
        return b.build();
    }

    public TableSchema schema() {
        return schema;
    }

    /** Загальна кількість рядків у векторах (без урахування вибору). */
    public int rowCount() {
        return rowCount;
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** Кількість активних рядків. */
    public int activeRowCount() {
        return selection == null ? rowCount : selection.size();
    }

    /** Вектор вибору (для батча без вибору — тотожний вибір усіх рядків). */
    public SelectionVector selection() {
        return selection == null ? SelectionVector.all(rowCount) : selection;
    }

    /** Фізичний індекс k-го активного рядка. */
    public int rowIndex(int k) {
        Preconditions.index(k, activeRowCount());
        return selection == null ? k : selection.getIndex(k);
    }

    public ColumnVector column(String name) {
        return vectors[schema.indexOf(name)];
    }

    public ColumnVector column(int index) {
        Preconditions.index(index, vectors.length);
        return vectors[index];
    }

    /** Той самий батч з іншим вектором вибору (нульове копіювання даних). */
    public RecordBatch withSelection(SelectionVector newSelection) {
        Preconditions.notNull(newSelection, "selection");
        if (!newSelection.isEmpty()) {
            Preconditions.check(newSelection.getIndex(newSelection.size() - 1) < rowCount,
                    "Selection index exceeds row count " + rowCount);
        }
        return new RecordBatch(schema, vectors, rowCount, newSelection);
    }

    public RecordBatch withoutSelection() {
        return new RecordBatch(schema, vectors, rowCount, null);
    }

    /** Нульове копіювання: лише вказані стовпчики у вказаному порядку, вибір зберігається. */
    public RecordBatch project(List<String> names) {
        ColumnVector[] selected = new ColumnVector[names.size()];
        for (int i = 0; i < selected.length; i++) {
            selected[i] = column(names.get(i));
        }
        return new RecordBatch(schema.project(names), selected, rowCount, selection);
    }

    /** Матеріалізує активні рядки в новий батч без вибору. */
    public RecordBatch compact() {
        if (selection == null) {
            return this;
        }
        int[] rows = selection.toArray();
        ColumnVector[] gathered = new ColumnVector[vectors.length];
        for (int c = 0; c < vectors.length; c++) {
            gathered[c] = org.example.columnardb.vector.Vectors.gather(vectors[c], rows, 0, rows.length);
        }
        return new RecordBatch(schema, gathered, rows.length, null);
    }

    /** Фізичний зріз [from, from+length); вибір не підтримується. */
    public RecordBatch slice(int from, int length) {
        Preconditions.check(selection == null, "Cannot slice a batch with selection; compact() first");
        Preconditions.range(from, length, rowCount);
        ColumnVector[] sliced = new ColumnVector[vectors.length];
        for (int c = 0; c < vectors.length; c++) {
            sliced[c] = vectors[c].slice(from, length);
        }
        return new RecordBatch(schema, sliced, length, null);
    }

    /** k-й активний рядок як масив значень. */
    public Object[] row(int k) {
        int physical = rowIndex(k);
        Object[] row = new Object[vectors.length];
        for (int c = 0; c < row.length; c++) {
            row[c] = vectors[c].getObject(physical);
        }
        return row;
    }

    public List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>(activeRowCount());
        for (int k = 0; k < activeRowCount(); k++) {
            rows.add(row(k));
        }
        return rows;
    }

    public static final class Builder {
        private final TableSchema schema;
        private final ColumnVector[] vectors;

        private Builder(TableSchema schema) {
            this.schema = schema;
            this.vectors = new ColumnVector[schema.size()];
        }

        public Builder addVector(String name, ColumnVector vector) {
            Preconditions.notNull(vector, "vector");
            int index = schema.indexOf(name);
            Preconditions.check(vectors[index] == null, "Vector already set for column " + name);
            ColumnDef def = schema.column(index);
            Preconditions.check(vector.type() == def.type(),
                    "Column " + name + " expects " + def.type() + " but got " + vector.type());
            Preconditions.check(def.nullable() || vector.nullCount() == 0,
                    "Column " + name + " is NOT NULL but vector contains NULLs");
            vectors[index] = vector;
            return this;
        }

        public RecordBatch build() {
            int rows = -1;
            for (int c = 0; c < vectors.length; c++) {
                Preconditions.check(vectors[c] != null, "Missing vector for column " + schema.column(c).name());
                if (rows < 0) {
                    rows = vectors[c].size();
                }
                Preconditions.check(vectors[c].size() == rows,
                        "Column " + schema.column(c).name() + " has " + vectors[c].size() + " rows, expected " + rows);
            }
            Preconditions.check(rows <= MAX_ROWS, "Batch exceeds " + MAX_ROWS + " rows: " + rows);
            return new RecordBatch(schema, vectors.clone(), rows, null);
        }
    }
}
