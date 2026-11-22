package org.example.columnardb.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.encoding.BitPackedEncoder;
import org.example.columnardb.encoding.DictionaryEncoder;
import org.example.columnardb.encoding.EncodingSelector;
import org.example.columnardb.encoding.RleEncoder;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;

/** Детерміновані тестові датасети (corpus) і способи їх зберігання в різних кодуваннях. */
public final class Datasets {
    /** Спосіб зберігання колонок таблиці. */
    public enum Mode { FLAT, AUTO, RLE, DICTIONARY, PACKED }

    public static final TableSchema SCHEMA = TableSchema.builder()
            .addColumn("id", DataType.INT64, false)
            .addColumn("age", DataType.INT64, true)
            .addColumn("country", DataType.STRING, true)
            .addColumn("salary", DataType.FLOAT64, true)
            .addColumn("dept", DataType.STRING, false)
            .build();

    private static final String[] COUNTRIES = {"UA", "US", "PL", "DE"};
    private static final String[] DEPTS = {"eng", "ops", "hr"};

    private Datasets() {
    }

    /**
     * Рядки (id, age, country, salary, dept) з серіями повторюваних значень і ~10% NULL.
     * Зарплати кратні 0.25, тому суми double точні незалежно від порядку додавання.
     */
    public static List<Object[]> randomRows(long seed, int n) {
        Random rnd = new Random(seed);
        List<Object[]> rows = new ArrayList<>(n);
        Object age = null;
        Object country = null;
        Object salary = null;
        String dept = DEPTS[0];
        int ageLeft = 0;
        int countryLeft = 0;
        int salaryLeft = 0;
        int deptLeft = 0;
        for (int i = 0; i < n; i++) {
            if (ageLeft-- <= 0) {
                age = rnd.nextInt(10) == 0 ? null : (Object) (long) rnd.nextInt(91);
                ageLeft = rnd.nextInt(6);
            }
            if (countryLeft-- <= 0) {
                country = rnd.nextInt(12) == 0 ? null : COUNTRIES[rnd.nextInt(COUNTRIES.length)];
                countryLeft = rnd.nextInt(8);
            }
            if (salaryLeft-- <= 0) {
                salary = rnd.nextInt(10) == 0 ? null : (Object) (rnd.nextInt(401) * 0.25);
                salaryLeft = rnd.nextInt(4);
            }
            if (deptLeft-- <= 0) {
                dept = DEPTS[rnd.nextInt(DEPTS.length)];
                deptLeft = rnd.nextInt(10);
            }
            rows.add(new Object[] {(long) i, age, country, salary, dept});
        }
        return rows;
    }

    public static UnaryOperator<ColumnVector> encoder(Mode mode) {
        return switch (mode) {
            case FLAT -> null;
            case AUTO -> EncodingSelector::compress;
            case RLE -> RleEncoder::encode;
            case DICTIONARY -> DictionaryEncoder::encode;
            case PACKED -> v -> v.type() == DataType.INT64 ? BitPackedEncoder.encode(v) : v;
        };
    }

    public static InMemoryTable table(List<Object[]> rows, Mode mode) {
        InMemoryTable table = new InMemoryTable(SCHEMA, encoder(mode));
        table.appendRows(rows);
        return table;
    }

    public static InMemoryTable table(int n, long seed, Mode mode) {
        return table(randomRows(seed, n), mode);
    }
}
