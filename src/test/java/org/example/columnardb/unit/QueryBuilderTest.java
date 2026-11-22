package org.example.columnardb.unit;

import static org.example.columnardb.corpus.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.example.columnardb.ColumnarDbException;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.query.*;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {
    private static final InMemoryTable T = table(r(1L, 1.0, "x"));

    @Test
    void multipleWhereCallsAreCombinedWithAnd() {
        Query q = QueryBuilder.from(T).where(Predicate.gt("a", 0)).where(Predicate.lt("a", 9)).build();
        assertInstanceOf(Predicate.And.class, q.where());
        assertEquals(2, ((Predicate.And) q.where()).terms().size());
    }

    @Test
    void defaultQueryHasNoClausesAndSelectsEverything() {
        Query q = QueryBuilder.from(T).build();
        assertNull(q.where());
        assertNull(q.limit());
        assertFalse(q.isAggregation());
        assertEquals(SIMPLE, QueryValidator.outputSchema(q));
    }

    @Test
    void projectionSchemaIsDerivedFromExpressions() {
        Query q = QueryBuilder.from(T).select("s").project("twice", Expression.mul(Expression.col("a"), Expression.lit(2))).build();
        assertEquals(List.of("s", "twice"), QueryValidator.outputSchema(q).names());
        assertTrue(QueryValidator.outputSchema(q).column("twice").nullable());
    }

    @Test
    void aggregationSchemaListsKeysThenAggregates() {
        Query q = QueryBuilder.from(T).groupBy("s").aggregate(AggregateFunc.avg("a", "m"), AggregateFunc.countStar("n")).build();
        var schema = QueryValidator.outputSchema(q);
        assertEquals(List.of("s", "m", "n"), schema.names());
        assertFalse(schema.column("n").nullable());
        assertTrue(q.isAggregation());
    }

    @Test
    void havingRequiresAggregation() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).having(Predicate.gt("a", 1)).build());
    }

    @Test
    void havingMustReferenceOutputColumns() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).groupBy("s")
                .aggregate(AggregateFunc.countStar("n")).having(Predicate.gt("a", 1)).build());
    }

    @Test
    void projectionCannotBeMixedWithAggregation() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).select("a")
                .aggregate(AggregateFunc.countStar("n")).build());
    }

    @Test
    void duplicateOutputNamesAreRejected() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).select("a", "a").build());
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T)
                .aggregate(AggregateFunc.countStar("n"), AggregateFunc.count("a", "n")).build());
    }

    @Test
    void predicateLiteralsAreNormalizedAndValidated() {
        assertEquals(5L, ((Predicate.Compare) Predicate.eq("a", 5)).value());
        assertEquals(1.5, ((Predicate.Compare) Predicate.eq("a", 1.5f)).value());
        assertThrows(ColumnarDbException.class, () -> Predicate.eq("a", null));
        assertThrows(ColumnarDbException.class, () -> Predicate.eq("a", new Object()));
        assertThrows(ColumnarDbException.class, () -> Predicate.eq("", 1));
        assertThrows(ColumnarDbException.class, () -> Predicate.in("a"));
        assertThrows(ColumnarDbException.class, () -> Predicate.in("a", 1, "x"));
        assertThrows(ColumnarDbException.class, () -> Predicate.and());
        assertThrows(ColumnarDbException.class, () -> Predicate.or());
        assertThrows(ColumnarDbException.class, () -> Predicate.not(null));
    }

    @Test
    void predicateColumnsAreCollectedInOrder() {
        Predicate p = Predicate.and(Predicate.gt("b", 1), Predicate.or(Predicate.eq("a", 1), Predicate.not(Predicate.isNull("s"))),
                Predicate.in("a", 1, 2));
        assertEquals(List.of("b", "a", "s"), List.copyOf(p.columns()));
    }

    @Test
    void limitAndNullArgumentsAreValidated() {
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(null));
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).where(null));
        assertThrows(ColumnarDbException.class, () -> QueryBuilder.from(T).having(null));
        assertThrows(ColumnarDbException.class, () -> new SortKey("", true));
        assertEquals(7L, (long) QueryBuilder.from(T).limit(7).build().limit());
    }

    @Test
    void queryIsImmutableSnapshotOfBuilderState() {
        QueryBuilder b = QueryBuilder.from(T).select("a");
        Query q = b.build();
        b.select("s");
        assertEquals(1, q.projections().size());
        assertThrows(UnsupportedOperationException.class, () -> q.projections().clear());
    }
}
