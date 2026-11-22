package org.example.columnardb.query;

import java.util.LinkedHashSet;
import java.util.Set;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.vector.DataType;

/** Скалярний вираз для проекції: посилання на стовпчик, літерал, арифметика. */
public sealed interface Expression {

    enum ArithOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        private final String symbol;

        ArithOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    record ColumnRef(String name) implements Expression {
        public ColumnRef {
            Preconditions.check(name != null && !name.isBlank(), "Column name must not be blank");
        }
    }

    record Literal(Object value) implements Expression {
        public Literal {
            value = Predicate.normalizeLiteral(value);
        }
    }

    record Binary(ArithOp op, Expression left, Expression right) implements Expression {
        public Binary {
            Preconditions.notNull(op, "op");
            Preconditions.notNull(left, "left");
            Preconditions.notNull(right, "right");
        }
    }

    static Expression col(String name) {
        return new ColumnRef(name);
    }

    static Expression lit(Object value) {
        return new Literal(value);
    }

    static Expression add(Expression l, Expression r) {
        return new Binary(ArithOp.ADD, l, r);
    }

    static Expression sub(Expression l, Expression r) {
        return new Binary(ArithOp.SUB, l, r);
    }

    static Expression mul(Expression l, Expression r) {
        return new Binary(ArithOp.MUL, l, r);
    }

    static Expression div(Expression l, Expression r) {
        return new Binary(ArithOp.DIV, l, r);
    }

    default Set<String> columns() {
        Set<String> result = new LinkedHashSet<>();
        collect(this, result);
        return result;
    }

    private static void collect(Expression e, Set<String> out) {
        switch (e) {
            case ColumnRef c -> out.add(c.name());
            case Literal l -> { }
            case Binary b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
        }
    }

    /** Тип результату: INT64 лише коли обидва операнди INT64, інакше FLOAT64. */
    default DataType typeOf(TableSchema schema) {
        return switch (this) {
            case ColumnRef c -> schema.typeOf(c.name());
            case Literal l -> l.value() instanceof Long ? DataType.INT64
                    : l.value() instanceof Double ? DataType.FLOAT64 : DataType.STRING;
            case Binary b -> {
                DataType left = b.left().typeOf(schema);
                DataType right = b.right().typeOf(schema);
                Preconditions.check(left.isNumeric() && right.isNumeric(),
                        "Operator " + b.op().symbol() + " requires numeric operands, got " + left + " and " + right);
                yield left == DataType.INT64 && right == DataType.INT64 ? DataType.INT64 : DataType.FLOAT64;
            }
        };
    }

    /** Чи може результат бути NULL (ділення на нуль та NULL-операнди дають NULL). */
    default boolean nullable(TableSchema schema) {
        return switch (this) {
            case ColumnRef c -> schema.column(c.name()).nullable();
            case Literal l -> false;
            case Binary b -> true;
        };
    }
}
