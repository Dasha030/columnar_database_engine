package org.example.columnardb.aggregate;

import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.vector.DataType;

/** Опис агрегатної функції: вид, вхідний стовпчик (крім COUNT(*)) і ім'я результату. */
public record AggregateFunc(Kind kind, String column, String alias) {

    public enum Kind { SUM, AVG, COUNT, COUNT_STAR, MIN, MAX }

    public AggregateFunc {
        Preconditions.notNull(kind, "kind");
        Preconditions.check(alias != null && !alias.isBlank(), "Aggregate alias must not be blank");
        if (kind == Kind.COUNT_STAR) {
            Preconditions.check(column == null, "COUNT(*) takes no column");
        } else {
            Preconditions.check(column != null && !column.isBlank(), kind + " requires a column");
        }
    }

    public static AggregateFunc sum(String column, String alias) {
        return new AggregateFunc(Kind.SUM, column, alias);
    }

    public static AggregateFunc avg(String column, String alias) {
        return new AggregateFunc(Kind.AVG, column, alias);
    }

    public static AggregateFunc count(String column, String alias) {
        return new AggregateFunc(Kind.COUNT, column, alias);
    }

    public static AggregateFunc countStar(String alias) {
        return new AggregateFunc(Kind.COUNT_STAR, null, alias);
    }

    public static AggregateFunc min(String column, String alias) {
        return new AggregateFunc(Kind.MIN, column, alias);
    }

    public static AggregateFunc max(String column, String alias) {
        return new AggregateFunc(Kind.MAX, column, alias);
    }

    /** Тип результату для заданої вхідної схеми; перевіряє сумісність типів. */
    public DataType outputType(TableSchema input) {
        if (kind == Kind.COUNT_STAR) {
            return DataType.INT64;
        }
        DataType in = input.typeOf(column);
        return switch (kind) {
            case COUNT -> DataType.INT64;
            case SUM -> {
                Preconditions.check(in.isNumeric(), "SUM requires a numeric column, got " + in);
                yield in;
            }
            case AVG -> {
                Preconditions.check(in.isNumeric(), "AVG requires a numeric column, got " + in);
                yield DataType.FLOAT64;
            }
            default -> in;
        };
    }

    /** COUNT не повертає NULL; решта функцій повертають NULL для порожніх груп. */
    public boolean outputNullable() {
        return kind != Kind.COUNT && kind != Kind.COUNT_STAR;
    }
}
