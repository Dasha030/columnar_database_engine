package org.example.columnardb.query;

import org.example.columnardb.Preconditions;

/** Ключ сортування. NULL вважається найменшим значенням (першим при ASC). */
public record SortKey(String column, boolean ascending) {
    public SortKey {
        Preconditions.check(column != null && !column.isBlank(), "Sort column must not be blank");
    }
}
