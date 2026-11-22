package org.example.columnardb.property;

import java.util.List;
import java.util.function.Function;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.query.Expression;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.Query;
import org.example.columnardb.query.QueryBuilder;

/** P-03: ExecuteQuery(V_Flat) == ExecuteQuery(V_Encoded). */
class EncodingEquivalencePropertyTest {

    @Provide
    Arbitrary<Integer> seeds() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    @Provide
    Arbitrary<Integer> sizes() {
        return Arbitraries.of(0, 1, 50, 1024, 1025, 2200);
    }

    private static final List<Function<InMemoryTable, Query>> QUERIES = List.of(
            t -> QueryBuilder.from(t).where(Predicate.gt("age", 30)).build(),
            t -> QueryBuilder.from(t).where(Predicate.eq("country", "UA")).select("id", "salary").build(),
            t -> QueryBuilder.from(t).groupBy("country").aggregate(AggregateFunc.sum("salary", "s"),
                    AggregateFunc.countStar("n"), AggregateFunc.avg("age", "a")).build(),
            t -> QueryBuilder.from(t).groupBy("dept", "country").aggregate(AggregateFunc.max("age", "m")).build(),
            t -> QueryBuilder.from(t).where(Predicate.or(Predicate.isNull("age"), Predicate.lt("salary", 20)))
                    .project("x", Expression.add(Expression.col("id"), Expression.lit(1))).build(),
            t -> QueryBuilder.from(t).groupBy("dept").aggregate(AggregateFunc.min("country", "c"))
                    .orderByDesc("c").limit(2).build());

    @Property(tries = 60)
    void queryResultDoesNotDependOnPhysicalEncoding(@ForAll("seeds") int seed, @ForAll("sizes") int size) {
        Invariants.encodingEquivalence(Datasets.randomRows(seed, size), QUERIES);
    }
}
