package org.example.columnardb.batch;

import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.DataType;

/** Метадані одного стовпчика: назва, тип, допустимість NULL. */
public record ColumnDef(String name, DataType type, boolean nullable) {
    public ColumnDef {
        Preconditions.check(name != null && !name.isBlank(), "Column name must not be blank");
        Preconditions.notNull(type, "type");
    }
}
