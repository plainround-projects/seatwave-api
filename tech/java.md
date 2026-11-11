# Java 25 (the language and the JDK)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The same bytecode runs on a Mac, a Windows PC and a Linux server.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. Passing text where a number is expected stops the build; it does not crash later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration`, `HttpClient` and, in this repository, a small web server. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had a JDK 25.0.2 build.

## Why this repository uses it

The repository is the hands-on project of Module 7 of a system design course. It builds the SeatWave ticket-booking API as a contract and as running code. Java 25 gives what that needs with one runtime library for JSON and one for the database:

- **A web server and an HTTP client in the JDK** (`com.sun.net.httpserver`, `java.net.http`). See `http-and-the-jdk-server.md`.
- **Virtual threads**: cheap threads, so a request that waits for the database or the payment provider costs almost nothing.
- **Records and sealed interfaces**: small immutable data types in a few lines, and `switch` statements that the compiler proves complete. They let the code return "seat taken" or "key reused" as ordinary values.
- **A standard library for time, regular expressions, concurrency and JDBC** (the database interface), so the project needs no framework.

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/api/` (3 files) | The wiring and the command line: `ContractLimits`, `SeatWaveApi`, `ServeCli`. See `java-root-and-config-walkthrough.md`. |
| `.../config/` (2 files) | Settings from a file. Same walkthrough. |
| `.../http/` (15 files) and `.../json/` (1 file) | The boundary with the network. See `java-http-walkthrough.md`. |
| `.../catalog/` (9 files) | Events and seats, with cursor pagination. See `java-catalog-walkthrough.md`. |
| `.../load/` (8 files) | The retry storm and the page walk. See `java-load-walkthrough.md`. |
| `.../booking/`, `idempotency/`, `payment/`, `store/` | Other notes describe them. |
| `src/test/java/` | The tests, also Java. |
| `build.gradle.kts` | Tells Gradle to compile for Java 25 and to treat every warning as an error. |

When I ran `./gradlew -q compileJava` in a copy of the repository, there were 67 source files under `src/main/java` and 127 class files under `build/classes/java/main`. There are more class files than source files because each record, enum and nested type gets a class file of its own.

## How it works underneath

**From text to a running program.** You write `FieldError.java`. The compiler reads it and writes `FieldError.class`. Gradle does this for you. To start a program, a JVM process starts, loads the class you name, and calls its `main` method. The commands of this repository each have a `main`: `ServeCli`, `RetryStormCli`, `PageWalkCli`, `KeyStoreSizeCli`.

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns and no other thread is running, the process ends with **exit status** 0, which means success. `System.exit(n)` ends it at once with another number. A non-zero status means failure; this repository uses 1 for "a failure to run", 2 for "the command was used incorrectly" and 3 for "the run found something wrong".

**Memory.** The **stack** holds the method calls in progress with their local variables; every thread has its own stack. The **heap** holds objects: `new ApiSettings(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. `null` is the reference that points to nothing. The JVM frees objects that nothing can reach any more; this is **garbage collection**.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often a method runs and turns the busy methods into native machine code while the program runs. The first requests are therefore slower than the later ones. That is one reason the load tools report percentiles and not a single time.

**Threads.** A **thread** is one line of execution in a process. Several threads run at the same time on different cores. A **blocking** call, such as reading from a socket, makes its thread wait. A **virtual thread** is a thread that the JDK manages itself: when it blocks, the JDK parks it and lets another one use the core, so a program can have thousands.

**The class path and modules.** The **class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task. The JDK itself is split into **modules**; the web server used here lives in the module `jdk.httpserver`, which the JDK exports for programs to use.

**The compiler flags of this repository.** `build.gradle.kts` sets `options.release = 25` (compile for Java 25 and use only its library) and `-Xlint:all -Werror` (turn on every warning and make any warning fail the build). So a warning cannot stay.

**Versions.** New language features arrive in numbered releases. A program that uses a feature from Java 21 cannot be compiled by JDK 17. The table at the end of the walkthrough lists the lowest version for each feature this code uses.

## Walkthrough of this repository's use

### The basic words

**Class, package, import.** A class is a blueprint for objects: it has **fields** (data) and **methods** (code). A **package** is a named group of classes, written with dots, and matches a folder path under `src/main/java`. The first line of a file is `package ...;`. Each `import` lets the file write a short class name instead of the full one.

```java
package io.plainround.seatwave.api;

import io.plainround.seatwave.api.booking.ConfirmService;
```

**Visibility, `static`, `final`.** `public` means any class may use it, `private` means only this class, and no word at all means only the classes of the same package. `static` means it belongs to the class, not to an object: you write `ContractLimits.MAX_BODY_BYTES`. `final` on a variable means it is assigned once; on a class, nobody may extend it. A **constant** is a `static final` field. Underscores inside a number (`1_024`) are ignored by the compiler and only help the eye (Java 7).

```java
  /** The largest request body, in bytes ("TooLarge" in the contract); Module 1's limit. */
  public static final int MAX_BODY_BYTES = 1_024;
```

```java
  public static final Duration HOLD_DURATION = Duration.ofSeconds(300);
```

**A private empty constructor** stops anyone from writing `new ContractLimits()`; the class only holds constants.

```java
  private ContractLimits() {}
```

**Types.** `int` is a 32-bit whole number, `long` a 64-bit one (a number written `20_261_111L` ends in `L`), `double` a decimal approximation, `boolean` true or false, `char` one character. `String` is text. These are fixed-size **primitive** types, except `String`. A type with a capital letter, such as `Long`, is an object type, and can be `null`. A value can be changed from one type to another with a **cast**: `(char) ('A' + section)` adds a number to a letter, which gives a number, and the cast turns the result back into a letter.

```java
  private Set<String> initialSeats() {
    Set<String> seats = new HashSet<>();
    for (int section = 0; section < settings.seed().sections(); section++) {
      for (int number = 1; number <= settings.seed().seatsPerSection(); number++) {
        seats.add((char) ('A' + section) + "-" + number);
      }
    }
    return seats;
  }
```

**Arrays, loops, `if`.** `long[] bytes = new long[2]` is a fixed row of two numbers. `for (int index = 0; index < 11; index++)` repeats a block; `do { ... } while (condition)` repeats it at least once; `for (String pair : encoded.split("&", -1))` visits each element of a collection. `cond ? a : b` picks one of two values.

```java
  // Upper bounds of the latency buckets, in milliseconds; p50 and p99 are read from them.
  private static final long[] BUCKET_BOUNDS_MILLIS = {
    1, 2, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500
  };
```

```java
  void countRequest(String route, int status, Duration elapsed) {
    requests.computeIfAbsent(route + SEPARATOR + status, unused -> new LongAdder()).increment();
    LongAdder[] routeBuckets = buckets.computeIfAbsent(route, unused -> newBuckets());
    long millis = elapsed.toMillis();
    for (int index = 0; index < BUCKET_BOUNDS_MILLIS.length; index++) {
      if (millis <= BUCKET_BOUNDS_MILLIS[index]) {
        routeBuckets[index].increment();
      }
    }
    // The last bucket is +Inf: every request falls in it.
    routeBuckets[BUCKET_BOUNDS_MILLIS.length].increment();
    durationSumMicros
        .computeIfAbsent(route, unused -> new LongAdder())
        .add(elapsed.toNanos() / NANOS_PER_MICRO);
  }
```

### Interfaces, implements and `@Override`

An **interface** lists methods a class promises to have. A class says `implements` and then must provide them. `AutoCloseable` is an interface of the JDK with one method, `close()`. `@Override` is an **annotation** (a tag written with `@` that the compiler or a library reads): it makes the compiler check that the method really replaces one from a parent type.

```java
  @Override
  public void close() throws SQLException {
    purge.close();
    server.close();
    database.close();
  }
```

An interface method can also have a body, marked `default`. `Page` has a default method `map`:

```java
  default <R> Page<R> map(Function<T, R> convert) {
    List<R> converted = items().stream().map(convert).toList();
    return switch (this) {
      case More<T> more -> new More<>(converted, more.nextCursor());
      case Last<T> last -> new Last<>(converted);
    };
  }
```

### Generics

A **generic** class or method has a type placeholder in angle brackets, such as `<T>`. `List<String>` is a list of text, and `Page<EventSummary>` is a page of events. One piece of code then works for many item types and the compiler still checks the types (Java 5).

```java
  private static <T> Page<T> pageOf(List<T> fetched, int limit, Function<T, String> cursorAfter) {
    if (fetched.size() <= limit) {
      return new Page.Last<>(fetched);
    }
    List<T> items = fetched.subList(0, limit);
    return new Page.More<>(items, cursorAfter.apply(items.getLast()));
  }
```

### Records (Java 16)

A **record** is a class for plain immutable data. `public record FieldError(String pointer, String detail) { ... }` makes the compiler write the fields, the constructor, the accessors `pointer()` and `detail()`, and `equals`, `hashCode` and `toString`. A record cannot be changed after it is built. A **compact constructor** has no parentheses and runs before the fields are set, so it can check or copy them. Records may be generic (`record More<T>`), nested in another type, and may implement interfaces.

```java
public record FieldError(String pointer, String detail) {

  /** Checks the fields. */
  public FieldError {
    Objects.requireNonNull(pointer, "pointer");
    Objects.requireNonNull(detail, "detail");
  }
```

I compiled `FieldError` and ran `javap` on it (see "Try it yourself"): the class is `final`, extends `java.lang.Record`, and has the two private final fields, the constructor, `toString`, `hashCode`, `equals` and the two accessors.

### Sealed interfaces (Java 17)

A **sealed** interface lists, in the same file, every type that implements it. The compiler therefore knows all the possible kinds of value. `Position` is either `First` or `After`; nothing else exists.

```java
/** Where a page starts: at the beginning, or after the item a cursor names. */
public sealed interface Position {

  /** The first page. */
  record First() implements Position {}
```

When the implementations sit in the same file, the `permits` list may be left out.

### Enums

An **enum** is a type with a fixed set of named values. In `ProblemType` each value also carries data, passed to the enum's constructor.

```java
  INVALID_REQUEST(400, "invalid-request", "The request does not match the contract"),
  NOT_FOUND(404, "not-found", "No such resource"),
```

```java
  private final int status;
  private final URI type;
  private final String title;

  ProblemType(int status, String slug, String title) {
    this.status = status;
    this.type = URI.create(TYPE_BASE + slug);
    this.title = title;
  }
```

### Switch expressions and pattern matching

A `switch` **expression** (Java 14) produces a value. The arrow `->` replaces the old `case x:` with `break`. Several labels can share a case, and `yield` returns a value from a block.

```java
  private static Optional<SeatFilter.StateFilter> stateOf(String text, List<FieldError> errors) {
    return switch (text) {
      case "available", "held", "sold" ->
          Optional.of(SeatFilter.StateFilter.valueOf(text.toUpperCase(Locale.ROOT)));
      default -> {
        errors.add(new FieldError("state", "must be available, held or sold"));
        yield Optional.empty();
      }
    };
  }
```

**Pattern matching for `switch` and record patterns** (Java 21) let a `case` test the kind of a value and take it apart in one step. `case Result.Success<ApiSettings, List<String>>(ApiSettings settings)` matches the success kind of the `Result` type and puts its content into `settings`. When the type is sealed, the compiler insists that every case is covered, so adding a new kind makes every unfinished `switch` fail to compile.

```java
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) ->
            serve(settings, Duration.ofSeconds(seconds));
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

The **unnamed pattern** `_` (final in Java 22) says "there is a value here and I do not need it". `var` (Java 10) lets the compiler infer a local type; here it works inside a pattern.

```java
      case ConfirmOutcome.HoldNotFound(), ConfirmOutcome.HoldNotActive(_) ->
          new StoredReply(
              CONFLICT, "", json.write(new Version0Views.ErrorVersion0("hold missing or expired")));
```

```java
      case HoldOutcome.Held(var hold) ->
          new StoredReply(
              CREATED, "/v1/holds/" + hold.holdId().value(), json.write(V1Views.HoldView.of(hold)));
```

**Pattern matching for `instanceof`** (Java 16) tests a type and names the result in one expression, so no cast is needed:

```java
      if (!(found instanceof Page.More<EventSummary> more)) {
        return deprecated(Reply.json(OK, json.write(events)), "/v1/events");
      }
      position = new Position.After(more.nextCursor());
```

### Text blocks (Java 15)

A text block is a string that spans lines, between `"""` and `"""`. The compiler removes the common indentation. The SQL in `CatalogService` uses them.

```java
    try (Connection connection = database.connect();
        PreparedStatement query =
            connection.prepareStatement(
                """
                SELECT event_id, name, starts_at FROM events
                WHERE starts_at > ? OR (starts_at = ? AND event_id > ?)
                ORDER BY starts_at, event_id
                LIMIT ?
                """)) {
```

### Lambdas, method references and functional interfaces

A **lambda** (Java 8) is a short unnamed function: `() -> purgeExpiredKeys(database, keys, clock)`. A **method reference** such as `catalog::listEvents` is a shorter way to say "call this method". Both can stand wherever an interface with a single method is expected; such an interface is a **functional interface**, and `@FunctionalInterface` asks the compiler to check that it has only one.

```java
      ScheduledExecutorService purge = Executors.newSingleThreadScheduledExecutor();
      purge.scheduleWithFixedDelay(
          () -> purgeExpiredKeys(database, keys, clock),
          PURGE_INTERVAL.toMillis(),
          PURGE_INTERVAL.toMillis(),
          TimeUnit.MILLISECONDS);
```

```java
  /** What a route runs. */
  @FunctionalInterface
  interface RouteHandler {
    Reply handle(ApiRequest request) throws SQLException;
  }
```

### Streams and Optional

A **stream** (Java 8) is a pipeline over a collection: `filter` keeps some elements, `map` converts each one, `findFirst` or `toList()` (Java 16) ends it. An **`Optional`** (Java 8) is a box that holds a value or is empty; it replaces returning `null`. `Optional.orElseThrow()` with no argument (Java 10) gives the value or throws.

```java
  private Optional<Route> match(ApiRequest request) {
    return routes.stream()
        .filter(route -> route.method().equals(request.method()))
        .filter(route -> route.pattern().matcher(request.path()).matches())
        .findFirst();
  }
```

```java
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
  }
```

### Exceptions

An **exception** signals a failure. A **checked** exception (such as `SQLException` or `IOException`) must be declared with `throws` or caught; an **unchecked** one (a `RuntimeException` such as `IllegalArgumentException`) need not. A `catch` can name several kinds with `|` (Java 7). `throw` raises one.

```java
    } catch (IOException | SQLException failed) {
      System.err.println("serving failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

When a `catch` rethrows the exact exception it caught, the compiler works out the exact kinds, so a method need not declare more than the body can throw (Java 7, "precise rethrow"):

```java
    } catch (SQLException | IOException | RuntimeException startFailure) {
      database.close();
      throw startFailure;
    }
  }
```

**try-with-resources** (Java 7) closes anything `AutoCloseable` when the block ends, however it ends. Since Java 9 the resource can be an existing variable, as in `try (exchange)`.

```java
  private static void purgeExpiredKeys(Database database, IdempotencyStore keys, Clock clock) {
    try (Connection connection = database.connect()) {
      int deleted = keys.purgeExpired(connection, clock.instant(), PURGE_BATCH);
      LOG.info("purged {} expired idempotency keys", deleted);
    } catch (SQLException purgeFailure) {
      // A failed purge only delays deletion; the next run tries again. It must not kill the
      // scheduler, which a thrown exception would.
      LOG.warn("key purge failed, SQL state {}", purgeFailure.getSQLState(), purgeFailure);
    }
  }
```

```java
    try (exchange) {
```

### Checks of arguments

`Objects.requireNonNull(value, "name")` throws a clear `NullPointerException` if the value is `null` and returns the value otherwise. The compact constructors use it to make sure that no field of a built object is `null`.

```java
  /** Copies the headers, keeping their order, and checks the fields. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(body, "body");
    // Map.copyOf would lose the order; an unmodifiable copy of an ordered map keeps it.
    headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }
```

### Immutable collections and the newer list methods

`List.of(...)`, `Map.of(...)` (Java 9), `List.copyOf(...)` and `Map.copyOf(...)` (Java 10) build collections that cannot be changed. `list.getFirst()` and `list.getLast()` (Java 21) read the ends of a list. Records copy their lists with these in the constructor, so a caller cannot change a built record from outside. `Map.copyOf` keeps no order, so `Reply` above, whose headers keep the order they were added in, uses `Collections.unmodifiableMap(new LinkedHashMap<>(headers))` instead: an ordered copy behind a wrapper that refuses changes.

```java
  /** Copies the collections and checks the fields. */
  public ApiRequest {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    headers = Map.copyOf(headers);
    Objects.requireNonNull(body, "body");
    pathValues = List.copyOf(pathValues);
    Objects.requireNonNull(correlationId, "correlationId");
  }
```

### Strings and formatting

`String.format(Locale.ROOT, "%s must be from %d to %d", ...)` fills `%s` with text and `%d` with a whole number. `Locale.ROOT` makes the result independent of the computer's language. `"text " + value` joins. Methods used here: `strip()` and `isBlank()` (Java 11), `String.join`, and `transform` (Java 12), which passes the string through a function.

```java
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
```

### Inner classes and `synchronized`

A class declared inside another class, without `static`, is an **inner class**: it can read the outer object's fields. `Tracker` in `PageWalk` is one. `synchronized` on a method lets only one thread at a time run it on the same object.

```java
  /** Counts what a walk saw and makes the same changes, in the same order, for both walks. */
  private final class Tracker {
    private final SeatWaveApi api;
    private final ApiClient client;
    private final Set<String> stable;
    private final Map<String, Integer> timesSeen = new HashMap<>();
    private final SplittableRandom random = new SplittableRandom(RANDOM_SEED);
    private final Map<Character, Integer> nextNewSeat = new HashMap<>();
    private int returned;

    Tracker(SeatWaveApi api, ApiClient client, Set<String> initial) {
      this.api = api;
      this.client = client;
      this.stable = new HashSet<>(initial);
    }
```

```java
  private synchronized long jitter(long capMillis) {
    // SplittableRandom is not thread-safe, so the draw is synchronized; it is not on the hot path.
    return random.nextLong(capMillis + 1);
  }
```

### Concurrency and virtual threads (Java 21)

`Executors.newVirtualThreadPerTaskExecutor()` gives an executor that starts a new virtual thread for each task. Since Java 19, an `ExecutorService` is `AutoCloseable`: closing it waits for the running tasks. `Thread.sleep(Duration)` (Java 19) waits for a `Duration`. `HttpClient` has been `AutoCloseable` since Java 21. A `Semaphore` is a counter of permits.

```java
    // Virtual threads make a request waiting on the database or the payment provider cheap; the
    // semaphore, not the thread count, is what bounds the work (Module 4).
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    ApiServer api = new ApiServer(server, workers, settings, routes, problems, metrics);
    server.setExecutor(workers);
    server.createContext("/", api::serve);
    server.start();
```

```java
  private List<ApiClient.Answer> sendAll(ApiClient client, List<HttpCall> calls)
      throws InterruptedException {
    // All requests start together; the semaphore keeps at most `concurrency` on the wire.
    Semaphore onTheWire = new Semaphore(concurrency);
    List<Future<ApiClient.Answer>> pending = new ArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (HttpCall call : calls) {
        pending.add(
            threads.submit(() -> {
              onTheWire.acquire();
              try {
                return sendWithRetries(client, call);
              } finally {
                onTheWire.release();
              }
            }));
      }
    }
    List<ApiClient.Answer> answers = new ArrayList<>();
    for (Future<ApiClient.Answer> answer : pending) {
      answers.add(resultOf(answer));
    }
    return answers;
  }
```

### The time classes (Java 8)

`Instant` is a point in time, `Duration` a length of time, and `Clock` the source of "now". Passing a `Clock` into a class lets a test supply one it can move.

```java
  /** How long a hold lasts (the summary of POST /v1/holds); Module 1's 300 seconds. */
  public static final Duration HOLD_DURATION = Duration.ofSeconds(300);

  /** How long an Idempotency-Key is remembered after its first use (the document's rules). */
  public static final Duration KEY_RETENTION = Duration.ofHours(24);
```

### Javadoc

A comment between `/**` and `*/` is **Javadoc**. A tool turns it into documentation. `@param` names a parameter, `@return` the result, `@throws` an exception. Every public type and method of this repository has one. A `//` comment explains a decision to the next reader.

### The versions each feature needs

| Feature used in this code | First Java version where it is final |
|---|---|
| Generics, annotations, enhanced `for` | 5 |
| Multi-catch, try-with-resources, underscores in numbers | 7 |
| Lambdas, method references, streams, `Optional`, `java.time`, `Base64` | 8 |
| `try (existingVariable)`, `List.of`, `Map.of`, `Matcher.results()` | 9 |
| `var`, `Optional.orElseThrow()`, `List.copyOf`, `Map.copyOf` | 10 |
| `java.net.http.HttpClient`, `String.strip`, `String.isBlank`, `readNBytes(int)` | 11 |
| `String.transform` | 12 |
| Switch expressions and `yield` | 14 |
| Text blocks | 15 |
| Records, `instanceof` patterns, `Stream.toList()` | 16 |
| Sealed interfaces | 17 |
| `ExecutorService` as `AutoCloseable`, `Thread.sleep(Duration)` | 19 |
| Virtual threads, switch patterns, record patterns, `getFirst()` and `getLast()`, `HttpClient.close()` | 21 |
| The unnamed pattern `_` | 22 |
| The build target of this repository (`options.release = 25`) | 25 |

## Try it yourself

All of these ran on a Mac with a JDK 25 build, in a copy of the repository or in a scratch folder. Timings differ on every run.

1. See the version:

```text
$ java -version
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
```

2. Run a single-file program. Save this as `Demo.java` and run `java Demo.java` (Java compiles and runs it in one step). It uses a sealed interface, two records and a switch with record patterns:

```java
public class Demo {
  sealed interface Shape permits Circle, Square {}
  record Circle(double radius) implements Shape {}
  record Square(double side) implements Shape {}
  public static void main(String[] args) {
    Shape shape = new Square(2.0);
    double area = switch (shape) {
      case Circle(double r) -> 3.14 * r * r;
      case Square(double s) -> s * s;
    };
    System.out.println(shape + " area " + area);
  }
}
```

```text
Square[side=2.0] area 4.0
```

3. Watch the compiler check types. A file `Bad.java` with `int count = "seven";` gives:

```text
$ javac Bad.java
Bad.java:3: error: incompatible types: String cannot be converted to int
    int count = "seven";
                ^
1 error
```

4. Watch it check that a `switch` is complete. I removed the `Missing` case from a `switch` over a sealed interface with two records:

```text
$ javac Switchy.java
Switchy.java:6: error: the switch expression does not cover all possible input values
    return switch (outcome) {
           ^
1 error
```

5. Look at what the compiler made of a record. After `./gradlew -q compileJava`:

```text
$ javap -cp build/classes/java/main -p io.plainround.seatwave.api.http.FieldError
Compiled from "FieldError.java"
public final class io.plainround.seatwave.api.http.FieldError extends java.lang.Record {
  private final java.lang.String pointer;
  private final java.lang.String detail;
  public io.plainround.seatwave.api.http.FieldError(java.lang.String, java.lang.String);
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public java.lang.String pointer();
  public java.lang.String detail();
}
```

6. The class files carry the Java version. Command: `javap -v -cp build/classes/java/main io.plainround.seatwave.api.ContractLimits | grep "major version"` printed `major version: 69`, the number of Java 25.

## Mistakes people make

- **Comparing text with `==`.** It compares references, not letters. Use `equals`. This code uses `equals` (for example `route.method().equals(request.method())`).
- **Returning `null` for "nothing".** The caller forgets to check. Here `Optional` and `Result` say it in the type.
- **Using exceptions for expected outcomes.** A taken seat is not a crash. Here a `Result` or a sealed outcome carries it, and a `switch` forces every caller to handle each kind.
- **A `switch` with a `default` that hides new cases.** With a sealed type and no `default`, the compiler reports every place that needs updating.
- **Catching everything and ignoring it.** Every `catch` here either returns a defined answer or logs, and says in a comment why.
- **Mutable shared state.** Many threads changing one object corrupt it. Records are immutable, collections are copied, and counters use `LongAdder` or `synchronized`.
- **Leaving resources open.** A connection or a stream stays open if the code forgets `close()`. `try (...)` closes them.
- **Money as `double`.** Use `BigDecimal`. `ApiSettings` insists on exactly two decimals.

## Check yourself

1. What does a record's compact constructor do, and when does it run?
   Answer: it checks or changes the arguments; it runs before the fields are assigned, so an invalid record cannot be built.
2. Why does `ServeCli` need no `default` branch in its `switch` over a `Result`?
   Answer: `Result` is sealed with two kinds, success and error; the compiler checks that both are covered.
3. What does `try (exchange)` do that a plain call does not?
   Answer: it closes the exchange when the block ends, whether it ends normally or by an exception.
4. What is the oldest Java release that can compile `case Circle(double r) -> ...`?
   Answer: Java 21.
5. What does `-Xlint:all -Werror` change in the build?
   Answer: every compiler warning becomes a build error, so no warning can stay.

## Further reading

- JDK 25 documentation: https://docs.oracle.com/en/java/javase/25/
- Java Language Specification, SE 25: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- Java SE 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/index.html
- JEP 395, Records: https://openjdk.org/jeps/395
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
- JEP 440, Record Patterns: https://openjdk.org/jeps/440
- JEP 441, Pattern Matching for switch: https://openjdk.org/jeps/441
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 456, Unnamed Variables and Patterns: https://openjdk.org/jeps/456
- JEP 378, Text Blocks: https://openjdk.org/jeps/378
