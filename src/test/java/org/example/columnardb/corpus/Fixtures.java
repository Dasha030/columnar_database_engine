package org.example.columnardb.corpus;

import java.util.List;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.query.Query;
import org.example.columnardb.vector.DataType;

/** Маленькі ручні фікстури для unit-тестів: таблиця (a INT64?, b FLOAT64?, s STRING?). */
public final class Fixtures {
    public static final TableSchema SIMPLE = TableSchema.builder()
            .addColumn("a", DataType.INT64, true)
            .addColumn("b", DataType.FLOAT64, true)
            .addColumn("s", DataType.STRING, true)
            .build();

    private Fixtures() {
    }

    public static Object[] r(Object... cells) {
        return cells;
    }

    public static InMemoryTable table(Datasets.Mode mode, Object[]... rows) {
        InMemoryTable t = new InMemoryTable(SIMPLE, Datasets.encoder(mode));
        t.appendRows(List.of(rows));
        return t;
    }

    public static InMemoryTable table(Object[]... rows) {
        return table(Datasets.Mode.FLAT, rows);
    }

    public static List<Object[]> run(Query q) {
        return QueryEngine.execute(q).rows();
    }

    /** Значення першого стовпчика результату як список. */
    public static List<Object> firstColumn(List<Object[]> rows) {
        return rows.stream().map(row -> row[0]).toList();
    }
}
