package org.example.columnardb.engine;

import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.query.Expression;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.VectorBuilder;
import org.example.columnardb.vector.Vectors;

/**
 * Обчислює вираз над активними рядками батчу; результат має довжину activeRowCount.
 * Посилання на стовпчик без вибору повертається без копіювання (разом із кодуванням).
 * Ділення на нуль дає NULL; переповнення цілих — {@link ColumnarDbException}.
 */
public final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    public static ColumnVector evaluate(Expression expression, RecordBatch batch) {
        int m = batch.activeRowCount();
        return switch (expression) {
            case Expression.ColumnRef c -> {
                ColumnVector column = batch.column(c.name());
                yield batch.hasSelection() ? Vectors.gather(column, batch.selection().toArray(), 0, m) : column;
            }
            case Expression.Literal l -> Vectors.constant(literalType(l.value()), l.value(), m);
            case Expression.Binary b ->
                    arithmetic(b.op(), evaluate(b.left(), batch), evaluate(b.right(), batch));
        };
    }

    private static DataType literalType(Object value) {
        return value instanceof Long ? DataType.INT64 : value instanceof Double ? DataType.FLOAT64 : DataType.STRING;
    }

    private static ColumnVector arithmetic(Expression.ArithOp op, ColumnVector left, ColumnVector right) {
        if (!left.type().isNumeric() || !right.type().isNumeric()) {
            throw new ColumnarDbException("Operator " + op.symbol() + " requires numeric operands");
        }
        int n = left.size();
        boolean integral = left.type() == DataType.INT64 && right.type() == DataType.INT64;
        VectorBuilder out = new VectorBuilder(integral ? DataType.INT64 : DataType.FLOAT64, n);
        for (int i = 0; i < n; i++) {
            if (left.isNull(i) || right.isNull(i)) {
                out.appendNull();
            } else if (integral) {
                appendIntegral(out, op, left.getLong(i), right.getLong(i));
            } else {
                appendFloating(out, op, left.asDouble(i), right.asDouble(i));
            }
        }
        return out.build();
    }

    private static void appendIntegral(VectorBuilder out, Expression.ArithOp op, long a, long b) {
        try {
            switch (op) {
                case ADD -> out.appendLong(Math.addExact(a, b));
                case SUB -> out.appendLong(Math.subtractExact(a, b));
                case MUL -> out.appendLong(Math.multiplyExact(a, b));
                case DIV -> {
                    if (b == 0) {
                        out.appendNull();
                    } else {
                        out.appendLong(Math.divideExact(a, b));
                    }
                }
            }
        } catch (ArithmeticException e) {
            throw new ColumnarDbException("Integer overflow in " + a + " " + op.symbol() + " " + b, e);
        }
    }

    private static void appendFloating(VectorBuilder out, Expression.ArithOp op, double a, double b) {
        switch (op) {
            case ADD -> out.appendDouble(a + b);
            case SUB -> out.appendDouble(a - b);
            case MUL -> out.appendDouble(a * b);
            case DIV -> {
                if (b == 0.0) {
                    out.appendNull();
                } else {
                    out.appendDouble(a / b);
                }
            }
        }
    }
}
