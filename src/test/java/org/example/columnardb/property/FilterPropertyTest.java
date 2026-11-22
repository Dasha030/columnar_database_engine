package org.example.columnardb.property;

import java.util.List;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.SelectionVector;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.query.Predicate;

/** P-02: інваріант фільтрації та алгебра SelectionVector. */
class FilterPropertyTest {

    @Provide
    Arbitrary<Integer> seeds() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    @Provide
    Arbitrary<Integer> sizes() {
        return Arbitraries.of(0, 1, 7, 1023, 1024, 1025, 2500);
    }

    @Provide
    Arbitrary<Integer> thresholds() {
        return Arbitraries.integers().between(-5, 95);
    }

    @Provide
    Arbitrary<List<Integer>> indexLists() {
        return Arbitraries.integers().between(0, 200).list().ofMaxSize(80);
    }

    static SelectionVector selection(List<Integer> raw) {
        int[] sorted = new TreeSet<>(raw).stream().mapToInt(Integer::intValue).toArray();
        return new SelectionVector(sorted, sorted.length);
    }

    @Property(tries = 50)
    void countOfPredicatePlusNegationPlusUnknownEqualsTotal(@ForAll("seeds") int seed, @ForAll("sizes") int size,
            @ForAll("thresholds") int threshold) {
        for (Datasets.Mode mode : Datasets.Mode.values()) {
            InMemoryTable table = Datasets.table(size, seed, mode);
            for (Predicate.Op op : Predicate.Op.values()) {
                Invariants.filterPartition(table, new Predicate.Compare("age", op, threshold), "age");
            }
            Invariants.filterPartition(table, Predicate.eq("country", "UA"), "country");
            Invariants.filterPartition(table, Predicate.in("country", "UA", "PL"), "country");
        }
    }

    @Property(tries = 200)
    void intersectionIsCommutativeAndBoundedByOperands(@ForAll("indexLists") List<Integer> x,
            @ForAll("indexLists") List<Integer> y) {
        SelectionVector a = selection(x);
        SelectionVector b = selection(y);
        if (!a.intersect(b).equals(b.intersect(a))) {
            throw new AssertionError("intersect not commutative");
        }
        if (a.intersect(b).size() > Math.min(a.size(), b.size())) {
            throw new AssertionError("intersection too large");
        }
        if (a.union(b).size() != a.size() + b.size() - a.intersect(b).size()) {
            throw new AssertionError("inclusion-exclusion violated");
        }
    }

    @Property(tries = 200)
    void complementIsAnInvolutionAndPartitionsTheRows(@ForAll("indexLists") List<Integer> x) {
        SelectionVector a = selection(x);
        int total = 250;
        SelectionVector c = a.complement(total);
        if (!c.complement(total).equals(a) || a.size() + c.size() != total || !a.intersect(c).isEmpty()) {
            throw new AssertionError("complement laws violated");
        }
    }
}
