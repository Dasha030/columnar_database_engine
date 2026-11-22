package org.example.columnardb.encoding;

import java.util.Arrays;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Encoding;

/**
 * Run-Length Encoded вектор: унікальні послідовні значення {@code runValues} (FLAT-вектор,
 * NULL-серії теж підтримуються) і довжини серій. Доступ за індексом: бінарний пошук по
 * префіксних сумах {@code runEnds}, O(log K).
 */
public final class RleVector implements ColumnVector {
    private final ColumnVector runValues;
    private final int[] runLengths;
    private final int[] runEnds;
    private final int size;

    public RleVector(ColumnVector runValues, int[] runLengths) {
        Preconditions.notNull(runValues, "runValues");
        Preconditions.notNull(runLengths, "runLengths");
        Preconditions.check(runValues.encoding() == Encoding.FLAT, "Run values must be FLAT");
        Preconditions.check(runValues.size() == runLengths.length,
                "Run count mismatch: " + runValues.size() + " values vs " + runLengths.length + " lengths");
        this.runValues = runValues;
        this.runLengths = runLengths.clone();
        this.runEnds = new int[runLengths.length];
        long total = 0;
        for (int r = 0; r < runLengths.length; r++) {
            Preconditions.check(runLengths[r] > 0, "Run length must be positive at run " + r);
            total += runLengths[r];
            Preconditions.check(total <= Integer.MAX_VALUE, "RLE vector too large");
            runEnds[r] = (int) total;
        }
        this.size = (int) total;
    }

    public int runCount() {
        return runLengths.length;
    }

    public int runLength(int run) {
        Preconditions.index(run, runLengths.length);
        return runLengths[run];
    }

    public int runStart(int run) {
        Preconditions.index(run, runLengths.length);
        return runEnds[run] - runLengths[run];
    }

    public ColumnVector runValues() {
        return runValues;
    }

    /** Індекс серії, що містить елемент i. */
    public int runIndexOf(int i) {
        Preconditions.index(i, size);
        int pos = Arrays.binarySearch(runEnds, i);
        // runEnds[r] — виключна межа: елемент i у першій серії, де runEnds[r] > i
        return pos >= 0 ? pos + 1 : -pos - 1;
    }

    @Override
    public DataType type() {
        return runValues.type();
    }

    @Override
    public Encoding encoding() {
        return Encoding.RLE;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isNull(int i) {
        return runValues.isNull(runIndexOf(i));
    }

    @Override
    public long getLong(int i) {
        return runValues.getLong(runIndexOf(i));
    }

    @Override
    public double getDouble(int i) {
        return runValues.getDouble(runIndexOf(i));
    }

    @Override
    public String getString(int i) {
        return runValues.getString(runIndexOf(i));
    }

    @Override
    public String toString() {
        return "RleVector{" + type() + ", size=" + size + ", runs=" + runCount() + "}";
    }
}
