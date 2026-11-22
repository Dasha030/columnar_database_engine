package org.example.columnardb.aggregate;

import org.example.columnardb.vector.ColumnVector;

/** Агрегатний стан для всіх груп одночасно (масиви, індексовані номером групи). */
abstract class Accumulator {
    /** Поточна місткість масивів стану (для діагностики та тестів). */
    abstract int capacity();

    /** Гарантує місце для груп 0..groups-1. */
    abstract void ensureCapacity(int groups);

    /** Оновлює стан групи значенням рядка row; для COUNT(*) вектор дорівнює null. */
    abstract void update(int group, ColumnVector input, int row);

    /** Результат для груп [from, to). */
    abstract ColumnVector finish(int from, int to);
}
