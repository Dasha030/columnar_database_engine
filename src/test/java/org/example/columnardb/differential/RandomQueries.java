package org.example.columnardb.differential;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.example.columnardb.aggregate.AggregateFunc;
import org.example.columnardb.batch.InMemoryTable;
import org.example.columnardb.batch.TableSchema;
import org.example.columnardb.corpus.Datasets;
import org.example.columnardb.query.Expression;
import org.example.columnardb.query.Predicate;
import org.example.columnardb.query.Query;
import org.example.columnardb.query.QueryBuilder;

/** Генератор випадкових, але коректних запитів над {@link Datasets#SCHEMA}. */
public final class RandomQueries {
    private static final String[] COUNTRIES = {"UA", "US", "PL", "DE", "ZZ"};
    private static final String[] DEPTS = {"eng", "ops", "hr", "zz"};
    private static final Predicate.Op[] OPS = Predicate.Op.values();

    private RandomQueries() {
    }

    public static Query generate(Random rnd, InMemoryTable table, int rows) {
        QueryBuilder b = QueryBuilder.from(table);
        if (rnd.nextInt(4) != 0) {
            b.where(predicate(rnd, rows, 2));
        }
        switch (rnd.nextInt(3)) {
            case 0 -> { // вибірка без перетворень
                if (rnd.nextBoolean()) {
                    b.select("id", "country", "age");
                }
            }
            case 1 -> { // проекція з арифметикою
                b.select("id").project("age2", Expression.add(Expression.col("age"), Expression.lit(rnd.nextInt(5))));
                b.project("pay", Expression.mul(Expression.col("salary"), Expression.lit(rnd.nextInt(4))));
                b.project("ratio", Expression.div(Expression.col("age"), Expression.lit(rnd.nextInt(3))));
            }
            default -> aggregation(rnd, b, table.schema());
        }
        Query base = b.build();
        TableSchema out = org.example.columnardb.query.QueryValidator.outputSchema(base);
        if (rnd.nextInt(3) == 0) {
            b.orderBy(out.column(rnd.nextInt(out.size())).name());
            if (rnd.nextBoolean()) {
                b.orderByDesc(out.column(rnd.nextInt(out.size())).name());
            }
        }
        if (rnd.nextInt(4) == 0) {
            b.limit(rnd.nextInt(Math.max(rows, 1) + 3));
        }
        return b.build();
    }

    private static void aggregation(Random rnd, QueryBuilder b, TableSchema schema) {
        List<String> keys = new ArrayList<>(List.of("country", "dept", "age"));
        java.util.Collections.shuffle(keys, rnd);
        b.groupBy(keys.subList(0, rnd.nextInt(3)).toArray(new String[0]));
        String[] numeric = {"age", "salary", "id"};
        String[] any = {"age", "salary", "country", "dept"};
        List<AggregateFunc> funcs = new ArrayList<>();
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count; i++) {
            String alias = "agg" + i;
            funcs.add(switch (rnd.nextInt(6)) {
                case 0 -> AggregateFunc.sum(numeric[rnd.nextInt(3)], alias);
                case 1 -> AggregateFunc.avg(numeric[rnd.nextInt(3)], alias);
                case 2 -> AggregateFunc.count(any[rnd.nextInt(4)], alias);
                case 3 -> AggregateFunc.countStar(alias);
                case 4 -> AggregateFunc.min(any[rnd.nextInt(4)], alias);
                default -> AggregateFunc.max(any[rnd.nextInt(4)], alias);
            });
        }
        b.aggregate(funcs.toArray(new AggregateFunc[0]));
        AggregateFunc first = funcs.get(0);
        if (rnd.nextInt(3) == 0 && first.outputType(schema).isNumeric()) {
            b.having(Predicate.gt(first.alias(), rnd.nextInt(5)));
        }
    }

    static Predicate predicate(Random rnd, int rows, int depth) {
        int kind = depth == 0 ? rnd.nextInt(5) : rnd.nextInt(8);
        if (kind >= 5) {
            Predicate left = predicate(rnd, rows, depth - 1);
            return switch (kind) {
                case 5 -> Predicate.and(left, predicate(rnd, rows, depth - 1));
                case 6 -> Predicate.or(left, predicate(rnd, rows, depth - 1));
                default -> Predicate.not(left);
            };
        }
        Predicate.Op op = OPS[rnd.nextInt(OPS.length)];
        return switch (rnd.nextInt(8)) {
            case 0 -> Predicate.isNull(pick(rnd, "age", "country", "salary"));
            case 1 -> Predicate.isNotNull(pick(rnd, "age", "country", "salary"));
            case 2 -> new Predicate.Compare("age", op, rnd.nextInt(5) == 0 ? (Object) (rnd.nextInt(180) / 2.0) : (Object) rnd.nextInt(91));
            case 3 -> new Predicate.Compare("salary", op, rnd.nextBoolean() ? (Object) (rnd.nextInt(401) * 0.25) : (Object) rnd.nextInt(101));
            case 4 -> new Predicate.Compare("country", op, COUNTRIES[rnd.nextInt(COUNTRIES.length)]);
            case 5 -> new Predicate.Compare("dept", op, DEPTS[rnd.nextInt(DEPTS.length)]);
            case 6 -> new Predicate.Compare("id", op, (long) rnd.nextInt(Math.max(rows, 1)));
            default -> Predicate.in(pick(rnd, "country", "dept"), COUNTRIES[rnd.nextInt(5)], DEPTS[rnd.nextInt(4)], "UA");
        };
    }

    private static String pick(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

}
