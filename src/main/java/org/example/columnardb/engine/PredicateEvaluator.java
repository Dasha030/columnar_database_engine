package org.example.columnardb.engine;

import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.SelectionVector;
import org.example.columnardb.encoding.DictionaryVector;
import org.example.columnardb.encoding.RleVector;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.vector.ColumnVector;

/**
 * Обчислює предикат над активними рядками батчу у трійковій логіці (TRUE/FALSE/UNKNOWN).
 * Для DICTIONARY-векторів предикат обчислюється один раз на запис словника, для RLE — один
 * раз на серію; решта — лише розкидання результату.
 */
public final class PredicateEvaluator {
    public static final byte FALSE = 0;
    public static final byte TRUE = 1;
    public static final byte UNKNOWN = 2;

    private PredicateEvaluator() {
    }

    /** SelectionVector з рядками, де предикат дорівнює TRUE. */
    public static SelectionVector select(Predicate predicate, RecordBatch batch) {
        byte[] truth = evaluate(predicate, batch);
        int[] rows = new int[truth.length];
        int count = 0;
        for (int k = 0; k < truth.length; k++) {
            if (truth[k] == TRUE) {
                rows[count++] = batch.rowIndex(k);
            }
        }
        return new SelectionVector(rows, count);
    }

    /** Масив істинності довжини activeRowCount (позиція k відповідає k-му активному рядку). */
    public static byte[] evaluate(Predicate predicate, RecordBatch batch) {
        return switch (predicate) {
            case Predicate.Compare c -> {
                ColumnVector v = batch.column(c.column());
                yield applyTest(v, ElementTest.compare(v.type(), c.op(), c.value()), batch);
            }
            case Predicate.In in -> {
                ColumnVector v = batch.column(in.column());
                yield applyTest(v, ElementTest.in(v.type(), in.values()), batch);
            }
            case Predicate.IsNull n -> isNull(batch.column(n.column()), n.negated(), batch);
            case Predicate.And a -> combine(a.terms(), batch, true);
            case Predicate.Or o -> combine(o.terms(), batch, false);
            case Predicate.Not n -> negate(evaluate(n.inner(), batch));
        };
    }

    private static byte[] applyTest(ColumnVector v, ElementTest test, RecordBatch batch) {
        int m = batch.activeRowCount();
        byte[] out = new byte[m];
        if (v instanceof DictionaryVector d) {
            byte[] perEntry = new byte[d.dictionarySize()];
            for (int e = 0; e < perEntry.length; e++) {
                perEntry[e] = test.test(d.dictionary(), e) ? TRUE : FALSE;
            }
            for (int k = 0; k < m; k++) {
                int row = batch.rowIndex(k);
                out[k] = d.isNull(row) ? UNKNOWN : perEntry[d.code(row)];
            }
        } else if (v instanceof RleVector r) {
            byte[] perRun = new byte[r.runCount()];
            for (int run = 0; run < perRun.length; run++) {
                perRun[run] = r.runValues().isNull(run) ? UNKNOWN
                        : test.test(r.runValues(), run) ? TRUE : FALSE;
            }
            for (int k = 0; k < m; k++) {
                out[k] = perRun[r.runIndexOf(batch.rowIndex(k))];
            }
        } else {
            for (int k = 0; k < m; k++) {
                int row = batch.rowIndex(k);
                out[k] = v.isNull(row) ? UNKNOWN : test.test(v, row) ? TRUE : FALSE;
            }
        }
        return out;
    }

    private static byte[] isNull(ColumnVector v, boolean negated, RecordBatch batch) {
        int m = batch.activeRowCount();
        byte[] out = new byte[m];
        for (int k = 0; k < m; k++) {
            boolean isNull = v.isNull(batch.rowIndex(k));
            out[k] = isNull != negated ? TRUE : FALSE;
        }
        return out;
    }

    private static byte[] combine(java.util.List<Predicate> terms, RecordBatch batch, boolean conjunction) {
        byte[] acc = evaluate(terms.get(0), batch);
        for (int t = 1; t < terms.size(); t++) {
            byte[] next = evaluate(terms.get(t), batch);
            for (int k = 0; k < acc.length; k++) {
                acc[k] = conjunction ? and(acc[k], next[k]) : or(acc[k], next[k]);
            }
        }
        return acc;
    }

    public static byte and(byte a, byte b) {
        if (a == FALSE || b == FALSE) {
            return FALSE;
        }
        return a == UNKNOWN || b == UNKNOWN ? UNKNOWN : TRUE;
    }

    public static byte or(byte a, byte b) {
        if (a == TRUE || b == TRUE) {
            return TRUE;
        }
        return a == UNKNOWN || b == UNKNOWN ? UNKNOWN : FALSE;
    }

    private static byte[] negate(byte[] truth) {
        for (int k = 0; k < truth.length; k++) {
            truth[k] = truth[k] == UNKNOWN ? UNKNOWN : truth[k] == TRUE ? FALSE : TRUE;
        }
        return truth;
    }
}
