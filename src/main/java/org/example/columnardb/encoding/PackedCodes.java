package org.example.columnardb.encoding;

import org.example.columnardb.Preconditions;

/** Компактний масив беззнакових кодів: 1, 2 або 4 байти на код залежно від кардинальності. */
public final class PackedCodes {
    private final byte[] bytes;
    private final short[] shorts;
    private final int[] ints;
    private final int size;

    private PackedCodes(byte[] bytes, short[] shorts, int[] ints, int size) {
        this.bytes = bytes;
        this.shorts = shorts;
        this.ints = ints;
        this.size = size;
    }

    /** Упаковує коди; cardinality — кількість різних кодів (макс. код = cardinality - 1). */
    public static PackedCodes of(int[] codes, int cardinality) {
        Preconditions.notNull(codes, "codes");
        Preconditions.check(cardinality >= 0, "Negative cardinality");
        int limit = Math.max(cardinality, 1); // при cardinality == 0 допустимий лише код-заглушка 0
        for (int code : codes) {
            Preconditions.check(code >= 0 && code < limit, "Code " + code + " outside [0, " + limit + ")");
        }
        int n = codes.length;
        if (cardinality <= 256) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) {
                b[i] = (byte) codes[i];
            }
            return new PackedCodes(b, null, null, n);
        }
        if (cardinality <= 65536) {
            short[] s = new short[n];
            for (int i = 0; i < n; i++) {
                s[i] = (short) codes[i];
            }
            return new PackedCodes(null, s, null, n);
        }
        return new PackedCodes(null, null, codes.clone(), n);
    }

    public int size() {
        return size;
    }

    /** Ширина одного коду в байтах: 1, 2 або 4. */
    public int width() {
        return bytes != null ? 1 : shorts != null ? 2 : 4;
    }

    public int get(int i) {
        Preconditions.index(i, size);
        if (bytes != null) {
            return bytes[i] & 0xFF;
        }
        if (shorts != null) {
            return shorts[i] & 0xFFFF;
        }
        return ints[i];
    }
}
