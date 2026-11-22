package org.example.columnardb.query;

import org.example.columnardb.Preconditions;

/** Іменований вираз у списку проекції. */
public record ProjectionItem(String name, Expression expression) {
    public ProjectionItem {
        Preconditions.check(name != null && !name.isBlank(), "Projection name must not be blank");
        Preconditions.notNull(expression, "expression");
    }
}
