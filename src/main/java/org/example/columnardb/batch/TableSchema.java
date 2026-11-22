package org.example.columnardb.batch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.DataType;

/** Упорядкований набір визначень стовпчиків без дублікатів імен. */
public final class TableSchema {
    private final List<ColumnDef> columns;
    private final Map<String, Integer> indexByName;

    private TableSchema(List<ColumnDef> columns) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Integer previous = index.put(columns.get(i).name(), i);
            Preconditions.check(previous == null, "Duplicate column name: " + columns.get(i).name());
        }
        this.indexByName = index;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TableSchema of(List<ColumnDef> columns) {
        return new TableSchema(Preconditions.notNull(columns, "columns"));
    }

    public List<ColumnDef> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public boolean has(String name) {
        return indexByName.containsKey(name);
    }

    public int indexOf(String name) {
        Integer index = indexByName.get(name);
        Preconditions.check(index != null, "Unknown column: " + name);
        return index;
    }

    public ColumnDef column(String name) {
        return columns.get(indexOf(name));
    }

    public ColumnDef column(int index) {
        Preconditions.index(index, columns.size());
        return columns.get(index);
    }

    public DataType typeOf(String name) {
        return column(name).type();
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnDef c : columns) {
            names.add(c.name());
        }
        return names;
    }

    /** Нова схема лише з вказаних стовпчиків у вказаному порядку. */
    public TableSchema project(List<String> names) {
        List<ColumnDef> selected = new ArrayList<>();
        for (String name : names) {
            selected.add(column(name));
        }
        return new TableSchema(selected);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TableSchema other && other.columns.equals(columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        return "TableSchema" + columns;
    }

    public static final class Builder {
        private final List<ColumnDef> columns = new ArrayList<>();

        public Builder addColumn(String name, DataType type, boolean nullable) {
            columns.add(new ColumnDef(name, type, nullable));
            return this;
        }

        public TableSchema build() {
            Preconditions.check(!columns.isEmpty(), "Schema must contain at least one column");
            return new TableSchema(columns);
        }
    }
}
