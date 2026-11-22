package org.example.columnardb.encoding;

import java.util.Arrays;
import org.example.columnardb.Preconditions;
import org.example.columnardb.vector.ColumnVector;
import org.example.columnardb.vector.VectorBuilder;
import org.example.columnardb.vector.Vectors;

/** Стиснення RLE за один прохід O(N). */
public final class RleEncoder {
    private RleEncoder() {
    }

    public static RleVector encode(ColumnVector input) {
        Preconditions.notNull(input, "input");
        VectorBuilder values = new VectorBuilder(input.type());
        int[] lengths = new int[8];
        int runs = 0;
        int n = input.size();
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && Vectors.sameValue(input, i, j)) {
                j++;
            }
            if (runs == lengths.length) {
                lengths = Arrays.copyOf(lengths, runs * 2);
            }
            values.appendFrom(input, i);
            lengths[runs++] = j - i;
            i = j;
        }
        return new RleVector(values.build(), Arrays.copyOf(lengths, runs));
    }

    /** Розпаковує у FLAT-вектор. */
    public static ColumnVector decode(RleVector encoded) {
        Preconditions.notNull(encoded, "encoded");
        VectorBuilder out = new VectorBuilder(encoded.type(), encoded.size());
        for (int r = 0; r < encoded.runCount(); r++) {
            for (int k = 0; k < encoded.runLength(r); k++) {
                out.appendFrom(encoded.runValues(), r);
            }
        }
        return out.build();
    }

    public static int countRuns(ColumnVector input) {
        int runs = 0;
        for (int i = 0; i < input.size(); i++) {
            if (i == 0 || !Vectors.sameValue(input, i - 1, i)) {
                runs++;
            }
        }
        return runs;
    }
}
