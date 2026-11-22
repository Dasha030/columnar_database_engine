# Design Document: In-Memory Columnar Database Engine (OLAP)

**Автор:** Тетерук Дарія, ІМ-44

## 1. Про що

`columnar-db-java` — високоефективний in-memory стовпчиковий (columnar) рушій баз даних на Java 21 для аналітичної обробки даних (OLAP). Систему натхненно специфікацією Apache Arrow та архітектурою ClickHouse/DuckDB.

Ядро реалізує векторизоване представлення даних із підтримкою бітових масок для NULL-значень, алгоритми стиснення векторів (Run-Length Encoding — RLE та Dictionary Encoding), а також векторизований виконувач запитів з блоковою обробкою даних (Batch Size = 1024). Рушій підтримує аналітичні запити з операціями фільтрації (`SelectionVector`), проекції, обчисленням агрегатів (`SUM`, `AVG`, `COUNT`, `MIN`, `MAX`) та групуванням через Hash GroupBy.

**Цільові показники:**

| Показник | Ціль |
|---|---|
| Покриття ядра | ≥ 90 % рядків і гілок |
| Mutation score | ≥ 80 % загальний (≥ 85 % для `vector/`, `encoding/`, `engine/`, `aggregate/`) |
| Обсяг ядра | ≥ 3000 рядків коду (без UI, CRUD, ORM, генерованого коду та тестів) |

## 2. Контекст і межі

Звичайні бази даних зберігають дані рядками (row-oriented). Це зручно для транзакцій (OLTP), але повільно для аналітики. Стовпчикові бази даних зберігають дані за стовпчиками (column-oriented), що дає чудове стиснення, локальність кешу CPU та можливість векторизованого виконання.

Джерело істини — Apache Arrow Specification; фактичний оракул для порівняння — DuckDB або еталонна рядкова (row-based) реалізація. Усі операції виконуються в оперативній пам'яті.

### Стовпчиковий рушій

Замість обходу таблиці по одному рядку рушій обробляє векторні батчі (по 1024 елементи). Для запиту
`SELECT country, SUM(salary) WHERE age > 18 GROUP BY country`:

| Крок | Що відбувається | Оптимізація |
|---|---|---|
| 1 | `ScanOperator` зчитує вектори `age`, `country`, `salary` батчами по 1024 елементи | Кеш-локальність CPU |
| 2 | `FilterOperator` обчислює предикат `age > 18` над вектором без розпакування рядків і формує `SelectionVector` | Відсутність branch misprediction |
| 3 | `Projection`/`Aggregate` обробляє лише індекси з `SelectionVector` | Нульове копіювання даних |
| 4 | `HashAggregate` накопичує суми в хеш-таблиці по групах `country` | Швидке групування в пам'яті |

## 3. Внутрішній устрій та алгоритми реалізації

### Цілі

- **G1:** Коректність векторизованих обчислень за специфікацією.
- **G2:** Збіг із референсною рядковою реалізацією за результатом аналітичних запитів.
- **G3:** Стійкість: жоден вхід або пошкоджений масив не викликає необробленого `NullPointerException` чи `IndexOutOfBoundsException`.
- **G4:** Перевірка кожної вимоги через property-based та differential тести.
- **G5:** Ядро з можливістю повного розширення без зовнішніх залежностей.

### Не-цілі

- SQL-парсер (запити будуються через Fluent Java Query Builder).
- Персистентне збереження на диск (WAL/SSTable).
- Транзакції (ACID).
- Багатопотоковість та мережевий протокол.

### Внутрішня структура даних векторів (`vector/`)

Усі дані зберігаються у щільних примітивних масивах Java для уникнення затримок GC та boxing/unboxing.

#### NullBitmap

Компактна бітова маска на основі `long[]`: кожен `long` містить 64 біти; `0` означає NULL, `1` — валідне значення.

- Індекс у масиві: `i >> 6`; бітова маска: `1L << (i & 63)`.
- `isNull(i)`: `(words[i >> 6] & (1L << (i & 63))) == 0`
- `setNotNull(i)`: `words[i >> 6] |= (1L << (i & 63))`

#### Типізовані вектори (`ColumnVector`)

- `Int64Vector`: `long[] values` + `NullBitmap`.
- `Float64Vector`: `double[] values` + `NullBitmap`.
- `StringVector` — схема Arrow Variable-Size Binary: суцільний `byte[] valueBuffer` (UTF-8), `int[] offsetBuffer` довжиною N+1 (рядок `i` лежить у `[offsetBuffer[i], offsetBuffer[i+1])`), `NullBitmap`.

### Алгоритми стиснення та кодування (`encoding/`)

Стиснення прозоре на рівні векторів: виконувач працює через абстракцію `ColumnVector`.

#### Run-Length Encoding (RLE)

- **Застосування:** стовпчики з повторюваними підряд значеннями (відсортовані за датою чи категорією).
- **Пам'ять:** `runValues` (значення серій) + `int[] runLengths` (кількість повторів).
- **encode:** один прохід O(N); якщо поточний елемент дорівнює попередньому — збільшуємо лічильник серії, інакше записуємо пару `(value, length)`.
- **Доступ:** `getLong(i)` — бінарний пошук по префіксних сумах `runLengths`, або розпакування у масив за потреби.

#### Dictionary Encoding

- **Застосування:** категоріальні дані з низькою кардинальністю (`country`, `status`).
- **Пам'ять:** словник унікальних значень (напр. `{"UA","US","PL"}`) + компактні коди (`byte[]`/`short[]` замість рядків).
- **encode:** тимчасова хеш-таблиця `Map<value, code>`; відсутнє значення додається до словника з новим ID; у вектор кодів пишеться лише код.
- **Перевага:** фільтр `WHERE country = 'UA'` знаходить код `'UA'` у словнику й порівнює примітивний масив кодів.

### Конвеєр виконувача запитів (`engine/`)

Векторизована модель Volcano (batch-at-a-time): кожен оператор повертає `RecordBatch` (за замовчуванням N = 1024).

#### Механізм SelectionVector

При `WHERE age > 18` нові зрізані вектори не створюються. `FilterOperator` обчислює предикат і заповнює масив індексів `int[] selectionArray` лише позиціями, що пройшли умову → `SelectionVector(int[] indices, int length)`. Наступні оператори читають значення як `vector.getLong(selectionVector.getIndex(i))`. Це усуває алокацію пам'яті під час фільтрації.

#### Хеш-агрегація (`HashAggregateOperator`)

Для `GROUP BY country AGGREGATE SUM(salary)`: створюється відкрита хеш-таблиця; для кожного елемента `RecordBatch` обчислюється хеш ключа групування, у відповідній комірці оновлюється стан (`SUM: sum += x`, `COUNT: count++`, `AVG: sum += x; count++`). Після читання всіх батчів таблиця матеріалізується у підсумкові векторні `RecordBatch`.

## 4. Модель даних і публічний API

### Модель даних

- **NullBitmap** — масив `long[]`, де кожен біт відповідає наявності/відсутності NULL.
- **ColumnVector** — імутабельний векторизований масив однотипних даних (`Int64Vector`, `Float64Vector`, `StringVector`); кодування: `FLAT`, `RLE`, `DICTIONARY`.
- **RecordBatch** — горизонтальний фрагмент таблиці до 1024 рядків: іменовані `ColumnVector` та `SelectionVector`.
- **TableSchema** — метадані стовпчиків (назва, тип, допустимість NULL).

### Публічний API

```java
// 1. Схема таблиці
TableSchema schema = TableSchema.builder()
    .addColumn("id", DataType.INT64, false)
    .addColumn("age", DataType.INT64, true)
    .addColumn("country", DataType.STRING, false)
    .addColumn("salary", DataType.FLOAT64, false)
    .build();

// 2. Стовпчикове сховище в пам'яті
InMemoryTable table = new InMemoryTable(schema);

// 3. Вставка батчу
RecordBatch batch = RecordBatch.builder(schema)
    .addVector("id", new Int64Vector(new long[]{1, 2, 3}, new NullBitmap(3)))
    .addVector("age", new Int64Vector(new long[]{25, 17, 30}, new NullBitmap(3)))
    .addVector("country", new StringVector(new String[]{"UA", "US", "UA"}, new NullBitmap(3)))
    .addVector("salary", new Float64Vector(new double[]{1000.0, 500.0, 1500.0}, new NullBitmap(3)))
    .build();
table.append(batch);

// 4. Fluent Query Builder
Query query = QueryBuilder.from(table)
    .where(Predicate.gt("age", 18))
    .groupBy("country")
    .aggregate(AggregateFunc.sum("salary", "total_salary"))
    .build();

// 5. Виконання
VectorOperator executionEngine = QueryEngine.compile(query);
while (executionEngine.hasNext()) {
    RecordBatch resultBatch = executionEngine.nextBatch();
    // обробка стовпчикового батчу результатів
}
```

## 5. Архітектура

### Ключові рішення

| Рішення | Чому | Що відкинули |
|---|---|---|
| Java 21, ядро без залежностей | Чистота вимірювання покриття та мутацій | Apache Arrow Java, Guava |
| Бітові маски через `long[]` | Швидкі побітові операції для NULL | `boolean[]`, `BitSet` (повільніші / GC) |
| Векторизована фільтрація через `SelectionVector` | Без копіювання масивів у пам'яті | Фізичне видалення/копіювання відфільтрованих елементів |
| Батчі по 1024 елементи | Лягає в L1/L2 кеш сучасних CPU | Обробка по одному рядку |
| Окремий шар кодування (RLE / Dictionary) | Прозоре стиснення векторів | Збереження лише в нестиснутому вигляді |

### Структура репозиторію

```
src/main/java/org/example/columnardb/
├── vector/     # ColumnVector, Int64Vector, Float64Vector, StringVector, NullBitmap
├── encoding/   # RLE, Dictionary, BitPacking
├── batch/      # RecordBatch, SelectionVector, TableSchema, InMemoryTable
├── engine/     # QueryEngine, Scan/Filter/Projection/Sort/Limit operators
├── aggregate/  # HashAggregateOperator, AggregateFunc (SUM, AVG, COUNT, MIN, MAX)
└── query/      # QueryBuilder, Expression, Predicate
src/test/java/org/example/columnardb/
├── unit/         # L1 Unit-тести (JUnit 5)
├── property/     # L2 Property-based (jqwik)
├── differential/ # L3 Диференційні тести з еталоном
├── fuzz/         # L4 Fuzzing & Boundary
└── corpus/       # Набори тестових датасетів
```

### Модулі ядра та оцінка обсягу

| Модуль | Відповідальність | План (рядків) | Факт (рядків) | Пріоритет |
|---|---|---:|---:|---|
| `vector/` | Вектори, пам'ять, `NullBitmap` | 650 | 638 | Must |
| `encoding/` | RLE, Dictionary (+ BitPacking) | 550 (+350) | 519 | Must (+Should) |
| `batch/` | `RecordBatch`, `SelectionVector`, схема, таблиця | 400 | 455 | Must |
| `engine/` | Scan, Filter, Projection, (+Sort, Limit) | 600 (+400) | 672 | Must (+Should) |
| `aggregate/` | Хеш-таблиця GroupBy, агрегатні функції | 550 | 575 | Must |
| `query/` | `QueryBuilder`, предикати, вирази | 450 | 391 | Must |
| **Разом** | | ≈ 3 200 + 750 | **3 286** | |

*Факт — непорожні рядки без коментарів. `Should` (BitPacking, HAVING, ORDER BY, LIMIT) реалізовано.*

## 6. Стратегія тестування

### Принципи

1. **Каскадна верифікація:** кожен рівень ловить свій клас помилок (Unit → Property → Differential → Fuzzing).
2. **Незмінні інваріанти:** перевіряються закони стиснення та еквівалентність фільтрації.
3. **Оцінка якості тестів через мутації:** тест не вважається ефективним без проходження PITest.

### Каталог перевірок

**L1 — Unit (JUnit 5)**

| ID | Клас | Що перевіряє |
|---|---|---|
| U-01 | `NullBitmapTest` | зсуви бітів, `isNull()`, `setNull()` |
| U-02 | `RleEncodingTest` | стиснення/розпакування серій |
| U-03 | `DictionaryEncodingTest` | побудова словника, індексація |
| U-04 | `FilterOperatorTest` | генерація `SelectionVector` для `>`, `<`, `==`, `!=` (+ `>=`, `<=`, `IN`, `IS NULL`, AND/OR/NOT) |
| U-05 | `HashAggregateTest` | `SUM`, `AVG`, `COUNT` (+ `MIN`, `MAX`) з урахуванням NULL |

**L2 — Property-based (jqwik)**

| ID | Інваріант |
|---|---|
| P-01 | `decompress(compress(V)) == V` |
| P-02 | `Count(Filter(P)) + Count(Filter(NOT P)) + Count(P is UNKNOWN) == TotalRows` |
| P-03 | `ExecuteQuery(V_Flat) == ExecuteQuery(V_Encoded)` |

**L3 — Differential**

| ID | Опис |
|---|---|
| D-01 | Порівняння випадково згенерованих запитів на стовпчиковому рушії та референсному row-based рушії (`ReferenceEngine`) |

**L4 — Edge cases & Fuzzing**

| ID | Опис |
|---|---|
| R-01 | Вектори з 0 і 1 елементом, повністю з NULL |
| R-02 | Розміри, не кратні 1024 (1025, 3000 рядків) |
| R-03 | Fuzzing бітових масок, буферів, схем запитів: лише `ColumnarDbException` |

**L5 — Coverage & Mutation (JaCoCo + PITest)**

| ID | Ціль |
|---|---|
| G-01 | Покриття ядра по гілках ≥ 90 % |
| G-02 | Mutation score ≥ 80 % (≥ 85 % для `vector/`, `encoding/`, `engine/`, `aggregate/`) |

## 7. MVP і план робіт

**Склад MVP:**

1. Векторні структури (`Int64Vector`, `Float64Vector`, `StringVector`) та `NullBitmap`.
2. Стиснення/розпакування RLE та Dictionary Encoding.
3. Блоковий ітератор `RecordBatch` (1024 елементи) та `SelectionVector`.
4. Оператори `ScanOperator`, `FilterOperator`, `ProjectionOperator`, `HashAggregateOperator` (SUM, AVG, COUNT).
5. Повний комплекс тестів (Unit, Property, Differential, Mutation).

---

## Додаток A. Рішення, прийняті під час реалізації

Уточнення, яких не було в початковому дизайні. Усі вони покриті тестами.

**Семантика даних**

1. **NULL — трійкова логіка SQL.** Порівняння з NULL дає `UNKNOWN`; рядок проходить фільтр лише при `TRUE`. Тому інваріант P-02 у точному вигляді має третій доданок — кількість рядків з `UNKNOWN`.
2. **NaN** вважається більшим за будь-яке число й рівним собі; `-0.0 == 0.0` у порівняннях і групуванні.
3. **Сортування:** NULL — найменше значення (першим при ASC, останнім при DESC); сортування стабільне (злиття).
4. **Ділення на нуль** (цілочисельне й дробове) дає NULL; **переповнення** `long` в арифметиці та `SUM` — `ColumnarDbException`.
5. **Порядок груп** у результаті `GROUP BY` — порядок першої появи ключа. Агрегат без `GROUP BY` завжди повертає один рядок (`COUNT = 0`, решта `NULL` на порожньому вході).

**Структури та алгоритми**

6. `RleVector` зберігає `runValues` як FLAT-вектор (NULL-серії підтримуються), тому працює для всіх типів; доступ — бінарний пошук, O(log K).
7. `DictionaryVector` — узагальнений (INT64/FLOAT64/STRING); коди займають 1, 2 або 4 байти (`PackedCodes`).
8. **Предикати над кодованими даними:** для `DICTIONARY` предикат обчислюється один раз на запис словника, для `RLE` — один раз на серію.
9. `HashAggregateOperator` — відкрита хеш-таблиця з лінійним зондуванням (`GroupKeyTable`); для одного словникового ключа хеш рахується раз на код у межах батча.
10. **BitPacking** (frame-of-reference + фіксована ширина) і `EncodingSelector` (автовибір кодування за евристиками), а також `ORDER BY`, `LIMIT`, `HAVING` реалізовані (пріоритет *Should*).
11. `SelectionVector` вимагає суворо зростаючих індексів — це дає O(n+m) для `intersect`/`union`.

**Інше**

12. Оператор виконання названо `QueryEngine` (як в API-прикладі), а не `ExecutionEngine`.
13. Усі помилки вхідних даних — єдиний `ColumnarDbException`; жоден публічний метод не кидає голі `IndexOutOfBounds`/`NullPointer` (ціль G3, перевіряється fuzz-тестами).

## Додаток B. Зміни за результатами mutation testing

Перший прогін PITest дав 90 % загалом, але пакет `aggregate` мав 78 % (ціль для нього — 85 %). Аналіз 43 мутантів, що вижили, показав три групи:

1. **Недоторкані властивості хеш-таблиці** (функція `mix`, комбінація ключів у `hashRow`, межі росту масивів). Помилка тут не змінює результат запиту, а лише швидкість, тому тести рівня запитів її не бачили. Додано package-private діагностику (`capacity()`, `slotCapacity()`, `occupiedSlots()`) і тести в пакеті `aggregate`, які звіряють `mix` з еталонним MurmurHash3 fmix64 (включно з відомим тестовим вектором) та перевіряють точні моменти росту й rehash.
2. **Еквівалентні мутанти** — зміни, що не впливають на поведінку (підказки початкової місткості `VectorBuilder`, кеш NULL-групи в `HashAggregateOperator`). Такий код спрощено або прибрано.
3. **Справжні прогалини в тестах**: пряма перевірка `equalsRow` (гілка NULL проти значення недосяжна без колізії хешів; для регресійного тесту підібрано значення `4119438558`, чий хеш збігається з хешем NULL), політика «перше з рівних» для `MIN`/`MAX` на `0.0`/`-0.0`, перевірки аргументів конструктора.

Усі виправлення додано тестами; 20 репрезентативних мутантів з цього списку відтворено вручну, і кожен тепер вбивається.
