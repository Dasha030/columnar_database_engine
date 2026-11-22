package org.example.columnardb.property;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.example.columnardb.encoding.RleEncoder;
import org.example.columnardb.encoding.RleVector;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.DataType;
import org.example.columnardb.vector.VectorBuilder;

/** P-01: інваріант стиснення decompress(compress(V)) == V. */
class CompressionPropertyTest {

    @Provide
    Arbitrary<List<Long>> nullableLongs() {
        return Arbitraries.longs().between(-4L, 4L).injectNull(0.2).list().ofMaxSize(2500);
    }

    @Provide
    Arbitrary<List<Long>> wideLongs() {
        return Arbitraries.longs().between(Long.MIN_VALUE, Long.MAX_VALUE).injectNull(0.1).list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<String>> nullableStrings() {
        return Arbitraries.of("", "UA", "US", "Привіт", "日本", "a").injectNull(0.2).list().ofMaxSize(500);
    }

    static ColumnVector longs(List<Long> values) {
        VectorBuilder b = new VectorBuilder(DataType.INT64);
        values.forEach(b::appendObject);
        return b.build();
    }

    static ColumnVector strings(List<String> values) {
        VectorBuilder b = new VectorBuilder(DataType.STRING);
        values.forEach(b::appendObject);
        return b.build();
    }

    @Property(tries = 200)
    void int64RoundTrip(@ForAll("nullableLongs") List<Long> values) {
        Invariants.compressionRoundTrip(longs(values));
    }

    @Property(tries = 200)
    void wideRangeInt64RoundTrip(@ForAll("wideLongs") List<Long> values) {
        Invariants.compressionRoundTrip(longs(values));
    }

    @Property(tries = 200)
    void stringRoundTrip(@ForAll("nullableStrings") List<String> values) {
        Invariants.compressionRoundTrip(strings(values));
    }

    @Property(tries = 200)
    void rleRunLengthsSumToSize(@ForAll("nullableLongs") List<Long> values) {
        RleVector rle = RleEncoder.encode(longs(values));
        long sum = 0;
        for (int r = 0; r < rle.runCount(); r++) {
            sum += rle.runLength(r);
        }
        if (sum != values.size() || rle.size() != values.size()) {
            throw new AssertionError("run lengths do not sum to size");
        }
        if (rle.runCount() != RleEncoder.countRuns(longs(values))) {
            throw new AssertionError("run count mismatch");
        }
    }
}
