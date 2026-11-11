# Walkthrough: the root package and `config/`

## What it is

This file reads five Java source files line by line: the three files that sit directly in `src/main/java/io/plainround/seatwave/api/` (`ContractLimits.java`, `SeatWaveApi.java`, `ServeCli.java`) and the two files in the `config/` package (`ApiSettings.java`, `SettingsReader.java`).

Together they answer three questions. What numbers does the contract fix (`ContractLimits`)? How does the program read a settings file and refuse a bad one (`SettingsReader`, `ApiSettings`)? How are the parts wired into one running server, and how is it started from a terminal (`SeatWaveApi`, `ServeCli`)?

A **source file** is a plain text file with Java code in it. A **package** is a named folder of source files; the name appears on the first line of each file. Read `java.md` first if the words record, constructor, exception or lambda are new to you. This file explains each of them again where a line uses it.

## Why this repository uses it

The lesson behind this repository is that an API contract is a promise, and the code must keep it. These five files are where the promise meets the running program:

- The contract publishes limits (500 items per page, 1,024 bytes per body, 300 seconds per hold, 24 hours of key memory). `ContractLimits` holds exactly those numbers, in one place, so no other file invents its own copy.
- Everything that may differ between runs (port, database, provider address, timeouts) lives in a settings file. `SettingsReader` and `ApiSettings` check every value before anything starts, so a typo is a clear message and not a half-started server.
- `SeatWaveApi` builds the whole service in one place, so the command line, the tests and the load tools all start the same thing.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/`) | What it does |
|---|---|
| `ContractLimits.java` | Five constants that the OpenAPI document also publishes. |
| `SeatWaveApi.java` | Opens the database, builds the services, starts the HTTP server and a background key purge; closes them together. |
| `ServeCli.java` | The `main` method behind `./gradlew -q serve`; reads the settings file and runs the API for a fixed time. |
| `config/SettingsReader.java` | Loads a `.properties` file and reads typed values from it, collecting every problem. |
| `config/ApiSettings.java` | The checked settings of one API process, as Java records. |
| `config/serve.properties` and the other four files in `config/` | The settings files these classes read. Another note owns them; this one explains how the keys are read. |

## How it works underneath

**A settings file** here is a Java **properties** file: lines of `key=value`. `java.util.Properties` loads it into a map of text to text. Lines that start with `#` are comments. Nothing in the file has a type; `8087` is just the three-character text it is. So the program must turn text into numbers, dates and money, and must say clearly when it cannot.

**No defaults.** If a key is missing, many programs quietly use a built-in value. This repository does not. A missing key is a problem line, never a silent fallback. The reason: a default hides the difference between "I chose 64" and "I forgot to say".

**All problems at once.** `SettingsReader` does not stop at the first bad key. It records a problem, returns a harmless stand-in value, and goes on. At the end the caller asks for the list. A person fixing a file sees every mistake in one run.

**Checked at birth.** The settings are **records** (small immutable data holders). A record can check its own fields when it is made. So an `ApiSettings` object that exists is always valid. An invalid one cannot be built.

**One running API.** `SeatWaveApi.start` creates objects in a fixed order: database first, then the services that use it, then the server that calls the services. `close` runs in the reverse order. Each object is built from the ones before it and handed what it needs through its constructor. Nothing looks up a global variable.

**Exit status.** When a program ends, it gives the operating system a small number. 0 means success. `ServeCli` ends with 2 for a bad command line or settings and 1 for a database or port failure. Scripts and Gradle read this number.

## Walkthrough of this repository's use

### `ContractLimits.java`

The first lines name the package and import one class.

```java
package io.plainround.seatwave.api;

import java.time.Duration;
```

`package` says which package this file belongs to. `import java.time.Duration;` lets the file write `Duration`, the JDK class for a length of time, instead of its full name.

```java
/**
 * The numbers the OpenAPI document publishes. They are constants, not settings, because a client
 * reads them in the contract: changing one is a contract change, made in the document first.
 */
public final class ContractLimits {
```

The comment between `/**` and `*/` is **Javadoc**: a comment that tools can turn into documentation. It says why these are constants and not settings: a client reads these numbers in the contract, so changing one changes the contract, and the change is made in the OpenAPI document first. `public final class` declares a class that anyone can use (`public`) and nobody can extend (`final`).

```java
  /** The largest request body, in bytes ("TooLarge" in the contract); Module 1's limit. */
  public static final int MAX_BODY_BYTES = 1_024;
```

`public static final int` makes a constant: `static` means it belongs to the class itself (you write `ContractLimits.MAX_BODY_BYTES`, no object needed), `final` means it never changes, and `int` is a whole number. `1_024` is the number 1024; Java lets you put underscores inside a number to make it easier to read. The body limit is 1,024 bytes. The contract calls the matching error "TooLarge".

```java
  /** Items per page when the client sends no limit (the "Limit" parameter's default). */
  public static final int DEFAULT_PAGE_LIMIT = 100;
```

When a client sends no `limit`, a list page holds 100 items.

```java
  /** The most items one page may hold (the "Limit" parameter's maximum). */
  public static final int MAX_PAGE_LIMIT = 500;
```

No page may hold more than 500 items, whatever the client asks.

```java
  /** How long a hold lasts (the summary of POST /v1/holds); Module 1's 300 seconds. */
  public static final Duration HOLD_DURATION = Duration.ofSeconds(300);
```

`Duration.ofSeconds(300)` is a `Duration` of 300 seconds, five minutes. A hold on a seat lasts this long.

```java
  /** How long an Idempotency-Key is remembered after its first use (the document's rules). */
  public static final Duration KEY_RETENTION = Duration.ofHours(24);
```

A stored idempotency key is remembered for 24 hours after its first use. `Duration.ofHours(24)` says it in hours, which reads better than 86,400 seconds.

```java
  private ContractLimits() {}
}
```

A **constructor** is the code that builds an object. This one is `private` and empty, so no code outside the class can write `new ContractLimits()`. The class is only a place to keep constants, and a private constructor says so. The final `}` closes the class.

### `SeatWaveApi.java`

```java
package io.plainround.seatwave.api;

import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.http.ApiServer;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentClient;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

The first line is the package. The `import` lines name every class from outside this file that the code uses. The lines starting `io.plainround.seatwave.api.` are this repository's own classes (the services, the server, the codec for JSON). The lines starting `java.` are the JDK: `Connection` and `SQLException` for the database, `Clock` and `Duration` for time, `Executors`, `ScheduledExecutorService` and `TimeUnit` for the background job. `org.slf4j` is the logging interface (see `slf4j-and-logback.md`).

```java
/**
 * One running SeatWave API: the database, the services, the HTTP server and the key purge, wired
 * once and closed together. The command line, the tests and the load tools all start it this way.
 *
 * <p>Thread-safe: after {@link #start} nothing in it changes.
 */
public final class SeatWaveApi implements AutoCloseable {
```

The Javadoc says what the class is: one running API, wired once and closed together. "Thread-safe: after start nothing in it changes" means that many threads can use it at the same time, because nothing in it is modified after it starts. A **thread** is one line of execution in a program. `implements AutoCloseable` promises a `close()` method and lets callers use the class in a `try (...)` block that closes it automatically.

```java
  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveApi.class);
  // Expired keys are deleted once a minute, at most this many per run, so the purge never holds
  // locks on a large part of the table; at 24 hours of retention a minute's delay is noise.
  private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
  private static final int PURGE_BATCH = 10_000;
```

`LOG` is the logger for this class. `LoggerFactory.getLogger(SeatWaveApi.class)` asks the logging library for a logger named after the class. The comment explains the purge: expired idempotency keys are deleted once a minute, at most 10,000 at a time, so one run never locks a large part of the table. `PURGE_INTERVAL` is one minute. `PURGE_BATCH` is 10,000, written `10_000`.

```java
  private final Database database;
  private final ApiServer server;
  private final ScheduledExecutorService purge;

  private SeatWaveApi(Database database, ApiServer server, ScheduledExecutorService purge) {
    this.database = database;
    this.server = server;
    this.purge = purge;
  }
```

Three fields hold what the object needs later to shut down: the database, the HTTP server, and the scheduler of the purge job. They are `private final`, so only this class can see them and they are set once. The constructor is `private`: nobody outside builds this class with `new`. Callers must use the `start` method below, which can fail cleanly. `this.database = database;` copies the constructor's argument into the field with the same name.

```java
  /**
   * Opens the database (migrating and seeding it if needed) and starts serving.
   *
   * @param settings the settings
   * @param clock the clock for every expiry; tests pass one they can move
   * @return the running API
   * @throws SQLException if the database cannot be opened, migrated or seeded
   * @throws IOException if the port cannot be bound
   */
  public static SeatWaveApi start(ApiSettings settings, Clock clock)
      throws SQLException, IOException {
```

The Javadoc lists the parameters and the exceptions. An **exception** is Java's way to signal a failure. `throws SQLException, IOException` declares that `start` may fail in two ways, and the compiler forces every caller to deal with them (these are **checked** exceptions). `Clock` is the JDK's source of "now". Tests pass a clock they can move, so expiry can be tested without waiting.

```java
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(clock, "clock");
    Database database = Database.open(settings.databaseUrl());
    try {
      database.seedIfEmpty(settings.seed(), clock);
```

`Objects.requireNonNull(settings, "settings")` stops at once with a clear message if the argument is `null` (the "no object" value). `Database.open(settings.databaseUrl())` opens the embedded H2 database and runs its migrations. Then the `try {` begins: if anything below fails, the `catch` at the end closes the database again, so a failed start does not leave it open. `database.seedIfEmpty(settings.seed(), clock)` fills an empty database with the demo events and seats.

```java
      JsonCodec json = new JsonCodec();
      IdempotencyStore keys = new IdempotencyStore();
      ConfirmService confirms =
          new ConfirmService(
              database,
              keys,
              new PaymentClient(settings.payment(), json),
              new ConfirmService.Price(settings.seatPrice(), settings.currency()),
              settings.payment().lease(),
              clock);
```

`JsonCodec` is this repository's JSON reader and writer (see `jackson.md`). `IdempotencyStore` handles the key table. `ConfirmService` takes the booking confirm; it needs the database, the key store, a `PaymentClient` that talks to the provider, a `Price` made of the seat price and currency from the settings, the lease length for the seat while it is being paid for, and the clock. `new X(...)` makes an object. Every argument is something the service needs; nothing is global.

```java
      ApiServer server =
          ApiServer.start(
              settings,
              new ApiServer.Services(
                  database,
                  keys,
                  new CatalogService(database, clock),
                  new HoldService(database, keys, clock),
                  confirms,
                  json));
```

`ApiServer.start(settings, new ApiServer.Services(...))` starts the HTTP server. The `Services` record bundles the objects the routes call: the database, the key store, the catalog service, the hold service, the confirm service, and the JSON codec. This is the moment the port is bound; a port already in use makes it throw `IOException`.

```java
      ScheduledExecutorService purge = Executors.newSingleThreadScheduledExecutor();
      purge.scheduleWithFixedDelay(
          () -> purgeExpiredKeys(database, keys, clock),
          PURGE_INTERVAL.toMillis(),
          PURGE_INTERVAL.toMillis(),
          TimeUnit.MILLISECONDS);
      return new SeatWaveApi(database, server, purge);
```

`Executors.newSingleThreadScheduledExecutor()` creates a scheduler with one thread. `scheduleWithFixedDelay` runs the given code repeatedly, waiting a fixed time between the end of one run and the start of the next. Its four arguments are: the code to run, the wait before the first run, the wait between runs (both are one minute in milliseconds) and the unit. `() -> purgeExpiredKeys(database, keys, clock)` is a **lambda**: a small unnamed function. The last line returns the finished object.

```java
    } catch (SQLException | IOException | RuntimeException startFailure) {
      database.close();
      throw startFailure;
    }
  }
```

`catch (SQLException | IOException | RuntimeException startFailure)` catches any of the three kinds of failure. It closes the database and then throws the same failure again with `throw startFailure;`. The compiler accepts this rethrow because it knows the exact kinds: the method already declares `SQLException` and `IOException`, and `RuntimeException` never needs a declaration.

```java
  /**
   * The port the API listens on, on 127.0.0.1.
   *
   * @return the port
   */
  public int port() {
    return server.port();
  }

  /**
   * The database, for tools and tests that read the counts that prove the invariants.
   *
   * @return the database
   */
  public Database database() {
    return database;
  }
```

Two **accessors**: `port()` gives the port the server really listens on (when the settings say port 0, the system picks a free one, which the tests use), and `database()` gives the database so tools and tests can count rows that prove the invariants.

```java
  @Override
  public void close() throws SQLException {
    purge.close();
    server.close();
    database.close();
  }
```

`@Override` marks that this method replaces the one from `AutoCloseable`; the compiler checks the name and signature. The order of closing is the reverse of opening: first the purge scheduler (`purge.close()` waits for a running purge), then the HTTP server, then the database. Stopping the server before the database means no request can arrive when the database is gone.

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

The purge job. `try (Connection connection = database.connect())` is **try-with-resources**: the connection is closed when the block ends, however it ends. `keys.purgeExpired(connection, clock.instant(), PURGE_BATCH)` deletes expired keys and returns how many. `LOG.info("purged {} expired idempotency keys", deleted)` logs a line; the `{}` is replaced by the value (see `slf4j-and-logback.md`). If the database fails, `LOG.warn` logs the SQL state (a five-character code that names the kind of failure) and the exception itself. The job does not rethrow: a scheduled job that throws is cancelled by the scheduler and never runs again, so a failed purge must only be logged and tried again a minute later.

### `ServeCli.java`

```java
package io.plainround.seatwave.api;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
```

Imports. `Result` comes from the result4j library: a value that is either a success or an error. `Path` is a file location. `Locale` is used for number formatting.

```java
/**
 * Command-line entry point: serves the SeatWave API on 127.0.0.1 for a fixed time. Exit status 2
 * means bad usage or settings; 1 means the database or the port failed.
 */
public final class ServeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int MAX_SERVE_SECONDS = 3_600;

  private ServeCli() {}
```

"Cli" means command-line interface. Exit status 2 is the shell's usual number for "the command was used incorrectly", and 1 is a general failure. `MAX_SERVE_SECONDS` limits `serve.seconds` to one hour, so a forgotten server cannot run for ever. The private empty constructor stops anyone from making an instance; the class is only a place for `main`.

```java
  /**
   * Serves the API described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <serve.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

`main` is the method Java calls when you start the program. `String[] arguments` holds the words after the program name on the command line. `./gradlew -q serve --args='config/serve.properties'` passes one word. Anything else prints the usage line to standard error (`System.err`, the stream for messages meant for people, apart from `System.out`) and calls `System.exit(2)` to end the process at once. `throws InterruptedException` is declared because `Thread.sleep` below can throw it when the thread is interrupted.

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) ->
            serve(settings, Duration.ofSeconds(seconds));
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

`SettingsReader.load(Path.of(arguments[0]))` reads the file; `new SettingsReader(...)` wraps it. `reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS)` reads a number from 1 to 3,600. Then `switch (ApiSettings.from(reader))` looks at the `Result`. This is **pattern matching**: `case Result.Success<...>(ApiSettings settings)` matches a success and pulls its content into the variable `settings`, and `case Result.Error<...>(List<String> problems)` matches an error and pulls the list of problem lines. The compiler checks that both cases are covered. For an error, each problem is printed as `invalid: ...` and the exit status is 2. For a success, `serve(settings, Duration.ofSeconds(seconds))` runs.

```java
    } catch (IOException | SQLException failed) {
      System.err.println("serving failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

`IOException` (file or port trouble) and `SQLException` (database trouble) are caught together, printed as `serving failed: ...`, and end the process with status 1.

```java
  private static void serve(ApiSettings settings, Duration serveFor)
      throws SQLException, IOException, InterruptedException {
    try (SeatWaveApi api = SeatWaveApi.start(settings, Clock.systemUTC())) {
      System.out.printf(
          Locale.ROOT,
          "SeatWave API on http://127.0.0.1:%d for %d s (at most %d requests in flight)%n",
          api.port(),
          serveFor.toSeconds(),
          settings.maxInFlight());
      System.out.printf(
          Locale.ROOT, "payment provider expected at %s%n", settings.payment().baseUrl());
      Thread.sleep(serveFor);
    }
  }
```

`try (SeatWaveApi api = SeatWaveApi.start(settings, Clock.systemUTC()))` starts the API and promises to close it when the block ends. `Clock.systemUTC()` is the real clock. `System.out.printf(Locale.ROOT, "...%d...%n", ...)` prints a formatted line: `%d` is replaced by a whole number, `%s` by text, `%n` is a line break. `Locale.ROOT` fixes the number format so it never depends on the computer's language. Then `Thread.sleep(serveFor)` makes the main thread wait for the chosen time. The server's own threads answer requests meanwhile. When the sleep ends, the block ends and `close()` runs.

### `config/SettingsReader.java`

```java
package io.plainround.seatwave.api.config;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
```

Imports. `Properties` is the JDK's holder of key-value text pairs. `Reader` and `Files.newBufferedReader` read a file as text. `StandardCharsets` names the UTF-8 encoding. `BigDecimal` is exact decimal arithmetic, used for money. `Instant` is a point in time. `DateTimeParseException` is what a bad date throws. `Pattern` is a regular expression.

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a reader sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,7}\\.[0-9]{2}");

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();
```

The Javadoc states the contract of the class: typed values, one problem line per missing or invalid key, no defaults, a harmless stand-in value on error, and "the caller must check `problems()` before using any value". It is not thread-safe: one reader belongs to one thread. `AMOUNT` is a regular expression for money: one to seven digits, a point, exactly two digits (`80.00`). In a Java string a backslash is doubled, so `\\.` means "a literal point". The `problems` list is created empty and grows as keys fail.

```java
  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

The constructor stores the loaded properties and refuses `null`.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

`load` is `static`, so you call it without a reader. `Files.newBufferedReader(path, StandardCharsets.UTF_8)` opens the file as UTF-8 text; the `try (...)` closes it afterwards. `properties.load(reader)` parses the `key=value` lines.

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

`intBetween` reads a whole number inside a range. First `text(key)` reads the raw value; if it is empty, a "missing" problem is already recorded, so it returns the minimum as the stand-in. `Integer.parseInt(text)` turns text into a number and throws `NumberFormatException` if it is not one. A number outside the range records `String.format(Locale.ROOT, "%s must be from %d to %d, but was %d", ...)`. A non-number records `key + " must be a whole number, but was " + text`. Both return the minimum. The comment explains the choice: a typo is an expected failure of a settings file, so it becomes a problem line and not a crash.

```java
  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code Millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

`millisBetween` turns a number of milliseconds into a `Duration`. By convention the key ends in `Millis`, so the unit is in the name.

```java
  /**
   * Reads an instant written in ISO-8601 UTC, for example {@code 2026-11-11T00:00:00Z}.
   *
   * @param key the key
   * @return the instant, or the epoch if the key is missing or invalid
   */
  public Instant instant(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException notAnInstant) {
      problems.add(key + " must be an ISO-8601 UTC instant such as 2026-11-11T00:00:00Z");
      return Instant.EPOCH;
    }
  }
```

`instant` reads a date and time such as `2026-11-11T00:00:00Z`. `Instant.parse` accepts only that ISO-8601 form ending in `Z` (UTC). On a problem it returns `Instant.EPOCH` (1 January 1970), a stand-in that is valid but obviously not a real choice.

```java
  /**
   * Reads a money amount with exactly two digits after the point, for example {@code 80.00}.
   *
   * @param key the key
   * @return the amount, or zero if the key is missing or invalid
   */
  public BigDecimal amount(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return BigDecimal.ZERO;
    }
    if (!AMOUNT.matcher(text).matches()) {
      problems.add(key + " must be an amount such as 80.00, but was " + text);
      return BigDecimal.ZERO;
    }
    return new BigDecimal(text);
  }
```

`amount` reads money. The regular expression `AMOUNT` forces exactly two decimals. `new BigDecimal(text)` keeps `80.00` exactly, with scale 2. A `double` would store money approximately; `BigDecimal` does not.

```java
  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @param description what the value must look like, for the problem line
   * @return the value, or an empty string if the key is missing or invalid
   */
  public String matching(String key, Pattern allowed, String description) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return "";
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must be " + description + ", but was " + text);
      return "";
    }
    return text;
  }
```

`matching` reads text that must match a pattern. `allowed.matcher(text).matches()` is true only if the whole text fits the pattern. The `description` argument becomes part of the problem line, so the message says what was expected.

```java
  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

`text` is the base of all the others. `properties.getProperty(key)` returns `null` if the key is absent. `value.isBlank()` is true for empty or spaces-only text. In both cases the problem `key + " is missing"` is recorded. Otherwise `value.strip()` removes spaces at both ends.

```java
  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
```

`List.copyOf(problems)` returns an unchangeable copy, so a caller cannot edit the reader's list.

### `config/ApiSettings.java`

```java
package io.plainround.seatwave.api.config;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
```

Imports. `URI` is a parsed web address. `Result` is result4j's success-or-error type.

```java
/**
 * Everything a SeatWave API process needs that may differ between runs, read from one settings file
 * with no defaults. The limits the contract publishes (page sizes, body size, hold time, key
 * retention) are not here: they live in {@link io.plainround.seatwave.api.ContractLimits}, because
 * changing them would change the contract.
 *
 * @param httpPort the TCP port on 127.0.0.1; 0 lets the system pick one (tests only)
 * @param maxInFlight the most requests served at once; one more is refused with 503
 * @param databaseUrl the H2 JDBC URL
 * @param seed how many events and seats to create in an empty database
 * @param payment where the payment provider is and how long to wait for it
 * @param seatPrice the price of one seat, the amount charged at confirm
 * @param currency the ISO 4217 code of the price
 * @param retryAfter the delay a 503 asks clients to wait before a retry
 * @param version0 the dates announced for the Module 1 paths
 */
public record ApiSettings(
    int httpPort,
    int maxInFlight,
    String databaseUrl,
    Seed seed,
    Payment payment,
    BigDecimal seatPrice,
    String currency,
    Duration retryAfter,
    Version0 version0) {
```

The Javadoc says what the record is for and, in the `@param` lines, what each field means. The note about limits repeats the rule: the page sizes and the other contract numbers are in `ContractLimits`, not here. `public record ApiSettings(int httpPort, int maxInFlight, ...)` declares a **record**: Java writes the constructor, the accessors (`httpPort()`, `maxInFlight()`, and one for each other field), `equals`, `hashCode` and `toString` for you. The nine fields are the whole configuration of one process. Three of them are records of their own, declared at the bottom of the file: `Seed`, `Payment` and `Version0`.

```java
  // Module 1 seeds 20 sections (A to T) of 1,000 seats: 20,000 seats in all.
  private static final int MAX_SECTIONS = 20;
  private static final int MAX_SEATS_PER_SECTION = 1_000;
  private static final int MAX_EVENTS = 10_000;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_RETRY_AFTER_SECONDS = 60;
  private static final Pattern H2_URL = Pattern.compile("jdbc:h2:(mem|file):[A-Za-z0-9_./;=-]+");
  // The provider is a stand-in on this machine: no request may leave the loopback address.
  private static final Pattern LOOPBACK_URL = Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{2,5}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
```

Constants for the allowed ranges and the patterns. Module 1 seeds 20 sections (A to T) of 1,000 seats, so those are the maxima. `LOWEST_UNPRIVILEGED_PORT` is 1,024: ports below it need special rights on most systems, and the program refuses them. `HIGHEST_PORT` is 65,535, the highest TCP port. `H2_URL` accepts only `jdbc:h2:mem:...` or `jdbc:h2:file:...` with a limited set of characters (letters, digits, `_ . / ; = -`). `LOOPBACK_URL` accepts only `http://127.0.0.1:` followed by two to five digits. The comment says why: the provider is a stand-in on this machine, so no request may leave the loopback address. `CURRENCY` is three capital letters.

```java
  /** Checks the fields, so invalid settings cannot exist. */
  public ApiSettings {
    if (httpPort < 0 || httpPort > HIGHEST_PORT) {
      throw new IllegalArgumentException("httpPort out of range: " + httpPort);
    }
    if (maxInFlight < 1 || maxInFlight > MAX_IN_FLIGHT) {
      throw new IllegalArgumentException("maxInFlight out of range: " + maxInFlight);
    }
    if (!H2_URL.matcher(Objects.requireNonNull(databaseUrl, "databaseUrl")).matches()) {
      throw new IllegalArgumentException("databaseUrl must be an H2 URL");
    }
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(payment, "payment");
    if (Objects.requireNonNull(seatPrice, "seatPrice").signum() <= 0 || seatPrice.scale() != 2) {
      throw new IllegalArgumentException("seatPrice must be positive with two decimals");
    }
    if (!CURRENCY.matcher(Objects.requireNonNull(currency, "currency")).matches()) {
      throw new IllegalArgumentException("currency must be three capital letters");
    }
    if (Objects.requireNonNull(retryAfter, "retryAfter").toSeconds() < 1) {
      throw new IllegalArgumentException("retryAfter must be at least one second");
    }
    Objects.requireNonNull(version0, "version0");
  }
```

The line `public ApiSettings {` with no parentheses is a **compact constructor**: code that runs before the fields are set, with the arguments as local variables. Each `if` throws `IllegalArgumentException` for a bad value, so an invalid `ApiSettings` cannot exist. `httpPort` may be 0 here (tests let the system choose a port). `Objects.requireNonNull(databaseUrl, "databaseUrl")` returns its argument when it is not null, so it can sit inside the `if` condition. `seatPrice.signum() <= 0` rejects zero and negative prices; `seatPrice.scale() != 2` rejects `80` or `80.000`, so money always has two decimals. `retryAfter.toSeconds() < 1` demands at least one second. The last line checks `version0` is present.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<ApiSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int port = reader.intBetween("http.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
    int inFlight = reader.intBetween("http.maxInFlight", 1, MAX_IN_FLIGHT);
    String url = reader.matching("database.url", H2_URL, "an H2 URL such as jdbc:h2:mem:seatwave");
    Seed seed =
        new Seed(
            reader.intBetween("seed.events", 1, MAX_EVENTS),
            reader.intBetween("seed.sections", 1, MAX_SECTIONS),
            reader.intBetween("seed.seatsPerSection", 1, MAX_SEATS_PER_SECTION));
```

`from` reads the settings from a `SettingsReader`. It returns a `Result` that is either the settings or a list of problem lines. Each `reader.intBetween(key, min, max)` call reads one key. `reader.matching("database.url", H2_URL, "an H2 URL such as jdbc:h2:mem:seatwave")` reads the database address. The `Seed` is built from three keys: `seed.events`, `seed.sections`, `seed.seatsPerSection`.

```java
    URI paymentUrl =
        URI.create(
            reader
                .matching("payment.url", LOOPBACK_URL, "http://127.0.0.1:<port>")
                // A missing or invalid URL is already a problem line; this stand-in only lets the
                // reading go on, so every other problem is reported in the same run.
                .transform(text -> text.isEmpty() ? "http://127.0.0.1:1" : text));
    Duration connect = reader.millisBetween("payment.connectTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("payment.requestTimeoutMillis", 1, MAX_TIMEOUT_MILLIS);
    Duration lease = reader.millisBetween("payment.leaseMillis", 1, MAX_TIMEOUT_MILLIS);
    BigDecimal price = reader.amount("booking.seatPrice");
    String currency = reader.matching("booking.currency", CURRENCY, "three capital letters");
    int retryAfterSeconds = reader.intBetween("http.retryAfterSeconds", 1, MAX_RETRY_AFTER_SECONDS);
    Instant deprecatedAt = reader.instant("version0.deprecatedAt");
    Instant sunsetAt = reader.instant("version0.sunsetAt");
```

The provider address is read with `LOOPBACK_URL`. If the key is missing or invalid, `matching` returns an empty string, and `URI.create("")` would be a legal but useless address. The `.transform(text -> text.isEmpty() ? "http://127.0.0.1:1" : text)` call replaces an empty string with a valid stand-in. `String.transform` applies a function to the string and returns the result; `cond ? a : b` is the conditional operator. The comment says it: the problem line is already recorded, and this only lets the reading go on, so every other problem is reported in the same run. The next lines read the three durations, the price (with `amount`), the currency, the retry delay in seconds, and the two dates of the old paths.

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    try {
      return Result.success(
          new ApiSettings(
              port,
              inFlight,
              url,
              seed,
              new Payment(paymentUrl, connect, request, lease),
              price,
              currency,
              Duration.ofSeconds(retryAfterSeconds),
              new Version0(deprecatedAt, sunsetAt)));
    } catch (IllegalArgumentException crossFieldProblem) {
      // Each key was valid alone; a rule between two keys failed (for example the lease).
      return Result.error(List.of(crossFieldProblem.getMessage()));
    }
  }
```

If the reader has collected any problem, `Result.error(reader.problems())` returns them all; nothing is built. Otherwise the `ApiSettings` is built inside a `try`. Each key was valid alone, but the constructors also check rules between fields (for example, the lease against the two timeouts). Those throw `IllegalArgumentException`, which is caught and turned into a single-line error result.

```java
  /**
   * The demo data created in an empty database: Module 1's "Arena Night" with its seats, then
   * further events without seats, so the event list has pages to walk.
   *
   * @param events how many events
   * @param sections how many sections, A onward, the first event has
   * @param seatsPerSection how many seats each section has
   */
  public record Seed(int events, int sections, int seatsPerSection) {

    /** Checks the fields. */
    public Seed {
      if (events < 1 || events > MAX_EVENTS) {
        throw new IllegalArgumentException("events out of range: " + events);
      }
      if (sections < 1 || sections > MAX_SECTIONS) {
        throw new IllegalArgumentException("sections out of range: " + sections);
      }
      if (seatsPerSection < 1 || seatsPerSection > MAX_SEATS_PER_SECTION) {
        throw new IllegalArgumentException("seatsPerSection out of range: " + seatsPerSection);
      }
    }
  }
```

The nested record `Seed`: how many events, how many sections (the first event has them) and how many seats in each. Its compact constructor checks each range. Inside a nested record, the outer class's `private static final` constants are visible.

```java
  /**
   * The payment provider and the budget for calling it.
   *
   * @param baseUrl the provider, on 127.0.0.1
   * @param connectTimeout how long to wait for its TCP connection
   * @param requestTimeout how long to wait for its whole reply
   * @param lease how long a confirm owns a seat while it pays; must outlast both timeouts
   */
  public record Payment(
      URI baseUrl, Duration connectTimeout, Duration requestTimeout, Duration lease) {

    /** Checks the fields. */
    public Payment {
      Objects.requireNonNull(baseUrl, "baseUrl");
      if (!LOOPBACK_URL.matcher(baseUrl.toString()).matches()) {
        throw new IllegalArgumentException("payment.url must be http://127.0.0.1:<port>");
      }
      Objects.requireNonNull(connectTimeout, "connectTimeout");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      Objects.requireNonNull(lease, "lease");
      // A lease shorter than the call would let a second confirm take the seat over while the
      // first is still waiting for the provider.
      if (lease.compareTo(connectTimeout.plus(requestTimeout)) <= 0) {
        throw new IllegalArgumentException(
            "payment.leaseMillis must be longer than the connect and request timeouts together");
      }
    }
  }
```

The nested record `Payment`. `baseUrl` must again match `LOOPBACK_URL`, checked a second time here so that a `Payment` built in code (not from a file) is held to the same rule. The last rule is the important one: `lease.compareTo(connectTimeout.plus(requestTimeout)) <= 0` means the lease must be longer than the two timeouts together. The comment says why: a lease shorter than the call would let a second confirm take the seat over while the first still waits for the provider.

```java
  /**
   * The deprecation of the Module 1 paths.
   *
   * @param deprecatedAt when they were deprecated, sent in the Deprecation header
   * @param sunsetAt when they stop answering, sent in the Sunset header
   */
  public record Version0(Instant deprecatedAt, Instant sunsetAt) {

    /** Checks the fields. */
    public Version0 {
      Objects.requireNonNull(deprecatedAt, "deprecatedAt");
      Objects.requireNonNull(sunsetAt, "sunsetAt");
      if (!sunsetAt.isAfter(deprecatedAt)) {
        throw new IllegalArgumentException("version0.sunsetAt must be after version0.deprecatedAt");
      }
    }
  }
```

The nested record `Version0`: the two dates for the old paths. `sunsetAt.isAfter(deprecatedAt)` must hold; a path cannot stop answering before it is announced as deprecated.

## Try it yourself

All commands run in the repository folder. I ran them in a copy of the repository, so the exact timings differ on your machine.

1. Check the Java version the build needs. Expected output (the build number can differ):

```text
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
```

   Command: `java -version`

2. Start the API with no arguments. The program prints the usage line and ends with status 2, which Gradle reports as a failed task:

```text
usage: serve <serve.properties>
```

   Command: `./gradlew -q serve`

3. Point it at a file that does not exist. The message is only the path:

```text
serving failed: /nonexistent.properties
```

   Command: `./gradlew -q serve --args="/nonexistent.properties"`

4. Break a settings file on purpose and see every problem in one run. I copied `config/serve.properties`, removed the lines `http.maxInFlight` and `seed.sections`, set `http.port=80` and set `payment.url=http://example.org:80`. The output:

```text
invalid: http.port must be from 1024 to 65535, but was 80
invalid: http.maxInFlight is missing
invalid: seed.sections is missing
invalid: payment.url must be http://127.0.0.1:<port>, but was http://example.org:80
```

   Command: `./gradlew -q serve --args="/path/to/your/bad.properties"`

5. Break only the cross-field rule: keep every key valid but set `payment.leaseMillis=100`. The one-line result:

```text
invalid: payment.leaseMillis must be longer than the connect and request timeouts together
```

   This rule shows only when every single key is valid, because the settings record is built only then.

## Mistakes people make

- **Using `double` for money.** `0.1 + 0.2` is not exactly `0.3` in a `double`. Here the price is a `BigDecimal` with exactly two decimals, and `ApiSettings` rejects anything else.
- **Stopping at the first bad key.** A person then fixes one thing per run. Here `SettingsReader` collects every problem.
- **A silent default.** A missing key that quietly falls back to a number hides a forgotten choice. Here a missing key is a problem line.
- **Letting a scheduled job throw.** A scheduler cancels a job that throws. `purgeExpiredKeys` catches the failure and logs it.
- **Closing in the wrong order.** Closing the database before the server would let a request arrive with no database. `close()` stops the server before the database.
- **A lease shorter than the call.** Two confirms could then pay for one seat. `Payment` refuses the combination at construction.

## Check yourself

1. Why are the page limits in `ContractLimits` and not in a settings file?
   Answer: the contract publishes them. Changing one changes what clients may rely on, so it is a contract change made in the OpenAPI document first, not a deployment choice.
2. What does the compact constructor `public ApiSettings { ... }` guarantee?
   Answer: every `ApiSettings` object that exists has passed every check, because the code runs before the fields are set and throws on a bad value.
3. What does `SettingsReader.intBetween` return for a missing key, and why does the caller still get the problem?
   Answer: the minimum, as a harmless stand-in; the problem line was added to the list, and `ApiSettings.from` returns that list instead of building the settings.
4. Why does the purge job catch `SQLException` and log it?
   Answer: a scheduled job that throws is cancelled and never runs again; logging and trying again next minute keeps the purge alive.
5. What exit status does `ServeCli` use for a bad settings file, and for a failed database?
   Answer: 2 for bad usage or settings, 1 for a database or port failure.

## Further reading

- Java SE 25 API, `java.util.Properties`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- Java SE 25 API, `java.time.Duration` and `java.time.Instant`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/package-summary.html
- Java SE 25 API, `java.math.BigDecimal`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html
- Java Language Specification, SE 25, records (Section 8.10): https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10
- Java SE 25 API, `java.util.concurrent.ScheduledExecutorService`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html
