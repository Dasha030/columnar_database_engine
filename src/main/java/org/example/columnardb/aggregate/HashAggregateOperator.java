package org.example.columnardb.aggregate;

import java.util.Arrays;
import java.util.List;
import org.example.columnardb.Preconditions;
import org.example.columnardb.batch.ColumnDef;
import org.example.columnardb.batch.RecordBatch;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.encoding.DictionaryVector;
import org.example.columnardb.engine.PrefetchOperator;
import org.example.columnardb.engine.VectorOperator;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;

/**
 * Hash GroupBy: споживає весь вхід, накопичує агрегатний стан по групах у хеш-таблиці та віддає
 * результат батчами (спочатку ключі групування, потім агрегати). Групи йдуть у порядку першої появи.
 * Без GROUP BY завжди повертається рівно один рядок (COUNT = 0, решта = NULL для порожнього входу).
 * Для одного DICTIONARY-ключа хеш рахується один раз на код словника в межах батча.
 */
public final class HashAggregateOperator extends PrefetchOperator {
    private final VectorOperator child;
    private final List<String> groupBy;
    private final List<AggregateFunc> functions;
    private static final int UNASSIGNED = -1;

    private final TableSchema schema;
    private GroupKeyTable table;
    private Accumulator[] accumulators;
    private int emitted;

    public HashAggregateOperator(VectorOperator child, List<String> groupBy, List<AggregateFunc> functions) {
        this.child = Preconditions.notNull(child, "child");
        this.groupBy = List.copyOf(groupBy);
        this.functions = List.copyOf(functions);
        Preconditions.check(!this.groupBy.isEmpty() || !this.functions.isEmpty(),
                "Aggregation needs GROUP BY columns or aggregate functions");
        TableSchema.Builder builder = TableSchema.builder();
        for (String key : this.groupBy) {
            ColumnDef def = child.schema().column(key);
            builder.addColumn(def.name(), def.type(), def.nullable());
        }
        for (AggregateFunc f : this.functions) {
            builder.addColumn(f.alias(), f.outputType(child.schema()), f.outputNullable());
        }
        this.schema = builder.build();
    }

    @Override
    public TableSchema schema() {
        return schema;
    }

    private void consume() {
        DataType[] keyTypes = new DataType[groupBy.size()];
        for (int c = 0; c < keyTypes.length; c++) {
            keyTypes[c] = child.schema().typeOf(groupBy.get(c));
        }
        table = new GroupKeyTable(keyTypes);
        accumulators = new Accumulator[functions.size()];
        for (int a = 0; a < accumulators.length; a++) {
            AggregateFunc f = functions.get(a);
            DataType input = f.column() == null ? DataType.INT64 : child.schema().typeOf(f.column());
            accumulators[a] = Accumulators.create(f, input);
        }
        if (groupBy.isEmpty()) {
            table.groupFor(new ColumnVector[0], 0); // глобальна група існує навіть для порожнього входу
        }
        while (child.hasNext()) {
            process(child.nextBatch());
        }
    }

    private void ensureCapacity() {
        for (Accumulator acc : accumulators) {
            acc.ensureCapacity(table.groupCount());
        }
    }

    /** Єдиний словниково кодований ключ групування (швидкий шлях) або null. */
    static DictionaryVector dictionaryKey(ColumnVector[] keys) {
        return keys.length == 1 && keys[0] instanceof DictionaryVector d ? d : null;
    }

    private void process(RecordBatch batch) {
        ColumnVector[] keys = new ColumnVector[groupBy.size()];
        for (int c = 0; c < keys.length; c++) {
            keys[c] = batch.column(groupBy.get(c));
        }
        ColumnVector[] inputs = new ColumnVector[functions.size()];
        for (int a = 0; a < inputs.length; a++) {
            String column = functions.get(a).column();
            inputs[a] = column == null ? null : batch.column(column);
        }
        DictionaryVector dictKey = dictionaryKey(keys);
        int[] codeToGroup = null;
        if (dictKey != null) {
            codeToGroup = new int[dictKey.dictionarySize()];
            Arrays.fill(codeToGroup, UNASSIGNED);
        }
        int known = 0; // кількість груп, для яких уже гарантовано місце в акумуляторах
        for (int k = 0; k < batch.activeRowCount(); k++) {
            int row = batch.rowIndex(k);
            int group;
            if (dictKey == null || dictKey.isNull(row)) {
                group = table.groupFor(keys, row);
            } else {
                int code = dictKey.code(row);
                if (codeToGroup[code] == UNASSIGNED) {
                    codeToGroup[code] = table.groupFor(keys, row);
                }
                group = codeToGroup[code];
            }
            if (table.groupCount() != known) {
                known = table.groupCount();
                ensureCapacity();
            }
            for (int a = 0; a < accumulators.length; a++) {
                accumulators[a].update(group, inputs[a], row);
            }
        }
    }

    @Override
    protected RecordBatch computeNext() {
        if (table == null) {
            consume();
        }
        if (emitted >= table.groupCount()) {
            return null;
        }
        int to = Math.min(emitted + RecordBatch.MAX_ROWS, table.groupCount());
        RecordBatch.Builder builder = RecordBatch.builder(schema);
        ColumnVector[] keyColumns = table.materialize(emitted, to);
        for (int c = 0; c < keyColumns.length; c++) {
            builder.addVector(groupBy.get(c), keyColumns[c]);
        }
        for (int a = 0; a < accumulators.length; a++) {
            builder.addVector(functions.get(a).alias(), accumulators[a].finish(emitted, to));
        }
        emitted = to;
        return builder.build();
    }
}
