# columnar-db-java

In-memory **стовпчиковий OLAP-рушій** на Java 21 без runtime-залежностей: векторизоване виконання
(батчі по 1024 рядки), бітові маски NULL, кодування **RLE / Dictionary / Bit-Packing**,
`SelectionVector` для фільтрації без копіювання та Hash GroupBy.

Повний опис архітектури та рішень — у [`DESIGN.md`](DESIGN.md).

## Швидкий старт

```bash
mvn test                      # усі тести (unit, property, differential, fuzz) + звіт JaCoCo
mvn verify -Pgate             # те саме + перевірка покриття >= 90% (рядки/гілки)
mvn test-compile org.pitest:pitest-maven:mutationCoverage   # mutation testing (поріг 80%)
```

Звіти: `target/site/jacoco/index.html` (покриття), `target/pit-reports/index.html` (мутації).
Потрібні JDK 21 і Maven 3.9+.

## Приклад

```java
TableSchema schema = TableSchema.builder()
    .addColumn("age", DataType.INT64, true)
    .addColumn("country", DataType.STRING, false)
    .addColumn("salary", DataType.FLOAT64, false)
    .build();

InMemoryTable table = new InMemoryTable(schema, true);   // true = автоматичне стиснення
table.appendRow(25L, "UA", 1000.0);
table.appendRow(17L, "US", 500.0);
table.appendRow(30L, "UA", 1500.0);

Query q = QueryBuilder.from(table)
    .where(Predicate.gt("age", 18))
    .groupBy("country")
    .aggregate(AggregateFunc.sum("salary", "total"), AggregateFunc.countStar("n"))
    .having(Predicate.ge("n", 1))
    .orderByDesc("total")
    .limit(10)
    .build();

System.out.println(QueryEngine.execute(q));
// country | total | n
// UA | 2500.0 | 2
```

## Що вміє

| Область | Можливості |
|---|---|
| Типи | `INT64`, `FLOAT64`, `STRING` (UTF-8, Arrow-layout), NULL через `long[]`-маску |
| Кодування | `FLAT`, `RLE`, `DICTIONARY`, `BIT_PACKED`, автовибір (`EncodingSelector`) |
| Предикати | `= != < <= > >=`, `IN`, `BETWEEN`, `IS [NOT] NULL`, `AND/OR/NOT` (трійкова логіка) |
| Вирази | `+ - * /` над числами, літерали, посилання на стовпчики |
| Агрегати | `SUM`, `AVG`, `COUNT`, `COUNT(*)`, `MIN`, `MAX`, `GROUP BY` по кількох ключах, `HAVING` |
| Інше | `ORDER BY` (стабільне, багатоключове), `LIMIT`, відсікання зайвих стовпчиків у `Scan` |

## Тестування

| Рівень | Папка | Що |
|---|---|---|
| L1 Unit | `unit/` | 190+ тестів: вектори, кодування, оператори, агрегати, білдер |
| L2 Property | `property/` | jqwik: P-01 стиснення, P-02 розбиття фільтра, P-03 еквівалентність кодувань |
| L3 Differential | `differential/` | випадкові запити проти незалежної рядкової `ReferenceEngine` (5 способів зберігання × 8 розмірів) |
| L4 Fuzz/Boundary | `fuzz/` | порожні/NULL-вектори, 1025/3000 рядків, пошкоджені буфери: лише `ColumnarDbException` |
| L5 | `pom.xml` | JaCoCo (>= 90%) і PITest (>= 80%) |

## Структура

```
src/main/java/org/example/columnardb/
  vector/  encoding/  batch/  engine/  aggregate/  query/
src/test/java/org/example/columnardb/
  unit/  property/  differential/  fuzz/  corpus/
```
