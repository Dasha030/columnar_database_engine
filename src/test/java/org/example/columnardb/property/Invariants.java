package org.example.columnardb.property;

import java.util.List;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.corpus.Rows;
import org.example.columnardb.encoding.BitPackedEncoder;
import org.example.columnardb.encoding.DictionaryEncoder;
import org.example.columnardb.encoding.EncodingSelector;
import org.example.columnardb.encoding.RleEncoder;
import org.example.columnardb.engine.QueryEngine;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.Query;
import org.example.columnardb.query.QueryBuilder;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.Vectors;

/** Інваріанти P-01..P-03 у вигляді перевірок, що кидають AssertionError; їх викликають property- та fuzz-тести. */
public final class Invariants {
    private Invariants() {
    }

    /** P-01: decompress(compress(V)) == V для кожного кодування. */
    public static void compressionRoundTrip(ColumnVector v) {
        require(Vectors.contentEquals(v, RleEncoder.decode(RleEncoder.encode(v))), "RLE round trip", v);
        require(Vectors.contentEquals(v, RleEncoder.encode(v)), "RLE logical access", v);
        require(Vectors.contentEquals(v, DictionaryEncoder.decode(DictionaryEncoder.encode(v))), "Dictionary round trip", v);
        require(Vectors.contentEquals(v, DictionaryEncoder.encode(v)), "Dictionary logical access", v);
        require(Vectors.contentEquals(v, EncodingSelector.compress(v)), "Auto compress", v);
        if (v.type() == DataType.INT64) {
            require(Vectors.contentEquals(v, BitPackedEncoder.decode(BitPackedEncoder.encode(v))), "BitPack round trip", v);
            require(Vectors.contentEquals(v, BitPackedEncoder.encode(v)), "BitPack logical access", v);
        }
    }

    /** P-02: Count(P) + Count(NOT P) + Count(col IS NULL) == TotalRows (SQL-семантика NULL). */
    public static void filterPartition(InMemoryTable table, Predicate p, String column) {
        long total = table.rowCount();
        long yes = count(table, p);
        long no = count(table, Predicate.not(p));
        long unknown = count(table, Predicate.isNull(column));
        require(yes + no + unknown == total, "partition " + yes + "+" + no + "+" + unknown + " != " + total, p);
    }

    private static long count(InMemoryTable table, Predicate p) {
        Query q = QueryBuilder.from(table).where(p).aggregate(AggregateFunc.countStar("n")).build();
        return (Long) QueryEngine.execute(q).rows().get(0)[0];
    }

    /** P-03: результат запиту не залежить від кодування зберігання. */
    public static void encodingEquivalence(List<Object[]> rows, List<java.util.function.Function<InMemoryTable, Query>> queries) {
        InMemoryTable flat = Datasets.table(rows, Datasets.Mode.FLAT);
        for (Datasets.Mode mode : Datasets.Mode.values()) {
            InMemoryTable encoded = Datasets.table(rows, mode);
            for (var q : queries) {
                List<Object[]> expected = QueryEngine.execute(q.apply(flat)).rows();
                List<Object[]> actual = QueryEngine.execute(q.apply(encoded)).rows();
                String diff = Rows.diff(expected, actual);
                require(diff == null, "encoding " + mode + ": " + diff, q.apply(flat));
            }
        }
    }

    private static void require(boolean condition, String what, Object context) {
        if (!condition) {
            throw new AssertionError("Invariant violated: " + what + " for " + context);
        }
    }
}
