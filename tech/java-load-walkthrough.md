# Walkthrough: the `load` package (the retry storm and the page walk)

## What it is

This file reads the eight Java files of the `load/` package line by line: `RetryStorm` and `RetryStormCli`, `PageWalk` and `PageWalkCli`, `OffsetSeatReader` (the rejected alternative, kept only to measure it), the small helpers `ApiClient` and `Latencies`, and `KeyStoreSizeCli`.

These classes are **load tools**. They are not part of the service. They start the real service inside their own process, send it many requests over real HTTP on `127.0.0.1`, and then check the **result**, not the replies. Two experiments carry the lesson of Module 7:

- **The retry storm** sends every write many times at once and proves that nothing happened twice: no seat booked twice, no hold charged twice.
- **The page walk** reads a long list page by page while the list changes, once with cursors and once with offsets, and counts the seats that were skipped or repeated.

A third small tool, `KeyStoreSizeCli`, measures what one stored idempotency key costs on disk.

## Why this repository uses it

A claim such as "retries are safe" is worth nothing until something tries to break it. The storm tries: a hundred copies of one request at the same moment is the worst case for a retry rule. The proof is read from the database and from the payment provider's ledger, because replies can lie (a client can be told "created" twice for one creation); rows and charges cannot.

The page walk does the same for pagination. It does not argue that offsets are worse. It computes, from the walk itself, which seats were skipped and which repeated, with the same changes made in the same order for both ways.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/load/`) | What it does |
|---|---|
| `RetryStorm.java`, `RetryStormCli.java` | The storm and its command line (`./gradlew -q retryStorm`). |
| `PageWalk.java`, `PageWalkCli.java` | The walk and its command line (`./gradlew -q pageWalk`). |
| `OffsetSeatReader.java` | Reads available seats with `LIMIT ... OFFSET ...`, to compare. |
| `ApiClient.java` | One JDK HTTP client used by both tools. |
| `Latencies.java` | Percentiles of the measured times. |
| `KeyStoreSizeCli.java` | Bytes per stored key (`./gradlew -q keyStoreSize --args=1000`). |

The settings files are `config/retry-storm.properties`, `config/page-walk.properties` and `config/page-walk-inserts.properties`.

## How it works underneath

**One process, both sides.** A load tool starts `SeatWaveApi` (and, for the storm, the stand-in payment provider) inside its own process, on `127.0.0.1`, with an in-memory database that disappears when the process ends. It then acts as the client, through the JDK's HTTP client. Real sockets and real HTTP are used, so the server's limit, headers and threads all take part.

**Concurrency.** A **thread** is one line of execution. To send 100 requests "at once", the tool starts 100 virtual threads, each of which sends one request. A **virtual thread** is a cheap thread managed by the JDK, so thousands are fine. A **semaphore** is a counter of permits: a thread takes a permit before it sends and gives it back after, so at most a chosen number are on the wire together.

**Retry with backoff and jitter.** When the server answers 503 ("try again"), the client waits and sends the same request with the same key. **Backoff** means the wait grows each time (20 ms, 40 ms, 80 ms, up to 640 ms). **Jitter** means the wait is a random time from zero up to that cap, so a hundred clients that failed together do not all come back at the same instant. A **retry** with the same idempotency key is the case the contract makes safe.

**The proof.** After the storm, the tool counts rows with SQL (`SELECT COUNT(*) FROM bookings`) and asks the provider's ledger how many times each hold was charged.

**Exit status.** The tools end with a number the shell can read: 0 means the claim held, 3 means a double effect or an inconsistency was found, 2 means bad usage, 1 means a failure to run.

## Walkthrough of this repository's use

### `ApiClient.java` (the HTTP client)

```java
package io.plainround.seatwave.api.load;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
```

Imports. `java.net.http` is the JDK's own HTTP client (since Java 11). `HttpRequest` describes a request, `HttpResponse` a reply, `HttpClient` sends.

```java
/**
 * A small HTTP client for the load tools: one JDK {@link HttpClient}, created once, talking to
 * 127.0.0.1 only.
 *
 * <p>Thread-safe: {@link HttpClient} is; many virtual threads send through one instance.
 */
final class ApiClient implements AutoCloseable {
```

The Javadoc says it is one client, created once, talking to `127.0.0.1` only, and thread-safe: many virtual threads send through one instance. The class is package-private (no `public`).

```java
  private final URI base;
  private final Duration timeout;
  private final HttpClient httpClient;

  ApiClient(int port, Duration timeout) {
    this.base = URI.create("http://127.0.0.1:" + port);
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }
```

The constructor builds the base address from the port. `HttpClient.newBuilder()` starts a builder: `.connectTimeout(timeout)` limits the wait for the TCP connection, `.executor(Executors.newVirtualThreadPerTaskExecutor())` makes the client run its own work on virtual threads, `.version(HttpClient.Version.HTTP_1_1)` forces HTTP/1.1 (the JDK server speaks 1.1; this avoids an attempt to upgrade), and `.build()` creates it.

```java
  /**
   * One reply.
   *
   * @param status the status code
   * @param body the body
   * @param elapsed the time from send to the last byte
   */
  record Answer(int status, String body, Duration elapsed) {}
```

`Answer` is a record of one reply: the status code, the body text and `elapsed`, the time from sending to the last byte.

```java
  Answer get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).GET().build());
  }
```

`get` sends a GET. `base.resolve(path)` joins the base address and a path. `.timeout(timeout)` limits the wait for the whole reply.

```java
  Answer postJson(String path, String key, String json) throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "\"" + key + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build());
  }
```

`postJson` sends a POST with a JSON body. It sets the two headers a write needs: `Content-Type: application/json` and `Idempotency-Key`, with the key wrapped in double quotes, because the contract writes the header as a quoted string. `BodyPublishers.ofString(json, UTF_8)` turns the text into the request body.

```java
  @Override
  public void close() {
    httpClient.close();
  }
```

`close` closes the client (`HttpClient` is `AutoCloseable` since Java 21).

```java
  private Answer send(HttpRequest request) throws IOException, InterruptedException {
    long startNanos = System.nanoTime();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Answer(
        response.statusCode(), response.body(), Duration.ofNanos(System.nanoTime() - startNanos));
  }
```

`send` takes the time with `System.nanoTime()` (a clock that only measures elapsed time), sends, and reads the whole body as text. `Duration.ofNanos(...)` is the elapsed time.

### `Latencies.java` (percentiles)

```java
package io.plainround.seatwave.api.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
```

```java
/** Percentiles of measured latencies, by the nearest-rank method. Not thread-safe. */
final class Latencies {

  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private final List<Duration> samples = new ArrayList<>();

  void add(Duration sample) {
    samples.add(sample);
  }
```

A **percentile** answers "how long did the slowest few take?". p50 is the median: half the requests were faster. p99 is the time that 99 percent of requests beat. `P50` and `P99` are the two shares used. The class is not thread-safe, and the storm calls it only from `synchronized` methods. `add` stores one sample.

```java
  String summary() {
    if (samples.isEmpty()) {
      return "no samples";
    }
    List<Duration> ordered = new ArrayList<>(samples);
    Collections.sort(ordered);
    return String.format(
        Locale.ROOT,
        "p50 %.2f ms, p99 %.2f ms, max %.2f ms (%d samples)",
        millis(rank(ordered, P50)),
        millis(rank(ordered, P99)),
        millis(ordered.getLast()),
        ordered.size());
  }
```

`summary` returns `no samples` if there are none. Otherwise it copies the list, sorts it with `Collections.sort` and formats one line: p50, p99, the maximum (`ordered.getLast()`, a Java 21 method) and the number of samples. `%.2f` prints two decimals.

```java
  private static Duration rank(List<Duration> ordered, double quantile) {
    // Nearest rank: the smallest value with at least that share of samples at or below it.
    int index = (int) Math.ceil(quantile * ordered.size()) - 1;
    return ordered.get(Math.max(0, index));
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
```

`rank` is the **nearest-rank** method: the index is `ceil(quantile * count) - 1`, which picks the smallest value with at least that share of samples at or below it. `Math.max(0, index)` guards the smallest case. `millis` converts nanoseconds to milliseconds.

### `OffsetSeatReader.java` (the rejected alternative)

```java
package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
```

```java
/**
 * The rejected alternative, kept only to measure it: available seats paged with {@code LIMIT ...
 * OFFSET ...}. The offset counts rows, so a row that leaves the filter or arrives before the
 * position shifts every later row by one, and a walker skips or repeats one.
 *
 * <p>Thread-safe: it holds no mutable state.
 */
final class OffsetSeatReader {
```

The Javadoc says it is kept only to measure the alternative: available seats paged with `LIMIT ... OFFSET ...`. The offset counts rows, so a row that leaves the filter or arrives before the position shifts every later row by one, and a walker skips or repeats one (see `java-catalog-walkthrough.md`).

```java
  private final Database database;
  private final Clock clock;

  OffsetSeatReader(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }
```

The reader holds the database and a clock.

```java
  List<String> availableSeats(int limit, int offset) throws SQLException {
    long now = clock.instant().toEpochMilli();
    try (Connection connection = database.connect();
        PreparedStatement query =
            connection.prepareStatement(
                """
                SELECT seat_label FROM seats
                WHERE event_id = 1
                  AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
                ORDER BY section, seat_number
                LIMIT ? OFFSET ?
                """)) {
      query.setLong(1, now);
      query.setInt(2, limit);
      query.setInt(3, offset);
      List<String> labels = new ArrayList<>();
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          labels.add(rows.getString(1));
        }
      }
      return labels;
    }
  }
```

`availableSeats(limit, offset)` runs one query. The SQL selects the labels of event 1's seats that are available (status `AVAILABLE`, or `HELD` with an expiry in the past, using the same lazy-expiry rule as the catalog), in map order, `LIMIT ? OFFSET ?`. The three values are bound to the `?`. It returns the labels.

### `PageWalk.java` (cursor against offset)

```java
package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.SeatWaveApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

```java
/**
 * The pagination consistency test: walk every available seat of the demo event while fans hold
 * seats and the venue adds seats between pages, once with the API's cursors and once with an
 * offset, on two identical databases with the same seeded schedule of changes.
 *
 * <p>The truth is computed, not trusted: a seat available from the first page to the last must be
 * returned exactly once. Seats that changed during the walk may appear or not; neither counts.
 *
 * <p>Not thread-safe: one walk runs from one thread.
 */
public final class PageWalk {
```

The Javadoc: walk every available seat of the demo event while fans hold seats and the venue adds seats between pages, once with the API's cursors and once with an offset, on two identical databases with the same seeded schedule of changes. "The truth is computed, not trusted": a seat available from the first page to the last must be returned exactly once. Seats that changed during the walk may appear or not; neither counts.

```java
  private static final Pattern SEAT = Pattern.compile("\"seat\":\"([A-T]-[0-9]{1,4})\"");
  private static final Pattern NEXT_CURSOR = Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_-]+)\"");
  private static final long RANDOM_SEED = 20_261_111L;
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final int CREATED = 201;
  // New seats get numbers after Module 1's 1 to 1,000 in their section.
  private static final int FIRST_NEW_SEAT_NUMBER = 1_001;
```

`SEAT` and `NEXT_CURSOR` are regular expressions that pull a seat label and the next cursor out of a reply's JSON text. The tool reads replies as text and does not parse JSON. `RANDOM_SEED` makes the random choices the same in both walks. New seats get numbers from 1,001, after Module 1's 1 to 1,000.

```java
  private final ApiSettings settings;
  private final Changes changes;

  /**
   * Prepares a walk.
   *
   * @param settings the API's settings; the database must be in memory
   * @param changes the page size and how much changes between pages
   */
  public PageWalk(ApiSettings settings, Changes changes) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.changes = Objects.requireNonNull(changes, "changes");
  }
```

The constructor stores the settings and the `Changes`.

```java
  /**
   * The walk's size and the changes made between pages.
   *
   * @param pageSize seats per page
   * @param holdsPerPage seats fans hold after each page
   * @param insertsPerPage seats the venue adds after each page
   */
  public record Changes(int pageSize, int holdsPerPage, int insertsPerPage) {

    /** Checks the fields. */
    public Changes {
      if (pageSize < 1 || holdsPerPage < 0 || insertsPerPage < 0) {
        throw new IllegalArgumentException("page size 1 or more, changes 0 or more");
      }
    }
  }
```

`Changes` says how big a page is and what changes after each page: how many seats fans hold, how many the venue adds. The constructor refuses a page size below 1 and negative counts.

```java
  /**
   * One walk's result.
   *
   * @param pages pages read
   * @param returned seats returned in all
   * @param stableSeats seats that were available for the whole walk
   * @param skipped stable seats never returned
   * @param repeated seats returned more than once
   */
  public record Walk(int pages, int returned, int stableSeats, int skipped, int repeated) {}
```

`Walk` is the result of one walk: pages read, seats returned in all, `stableSeats` (seats that were available for the whole walk), `skipped` (stable seats never returned) and `repeated` (seats returned more than once).

```java
  /**
   * Both walks and the sizes of the cursor walk's pages.
   *
   * @param cursor the walk with the API's cursors
   * @param offset the walk with LIMIT and OFFSET
   * @param averagePageBytes the mean size of a cursor page's body
   * @param largestPageBytes the largest cursor page's body
   * @param pageLatency the latency of the cursor pages, through HTTP
   */
  public record Report(
      Walk cursor, Walk offset, long averagePageBytes, long largestPageBytes, String pageLatency) {}
```

`Report` holds both walks and the size and latency of the cursor pages.

```java
  /**
   * Runs both walks.
   *
   * @return the report
   * @throws IOException if the port cannot be bound
   * @throws SQLException if a database fails
   * @throws InterruptedException if interrupted while waiting for a reply
   */
  public Report run() throws IOException, SQLException, InterruptedException {
    Latencies latencies = new Latencies();
    long[] bytes = new long[2];
    Walk cursorWalk;
```

`run` runs both walks. `long[] bytes = new long[2]` is a two-slot array used as two counters (the total size of the pages and the largest page).

```java
    try (SeatWaveApi api = SeatWaveApi.start(withDatabase("cursor"), Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
      Tracker tracker = new Tracker(api, client, initialSeats());
      String cursor = "";
      int pages = 0;
```

The first walk. `try (SeatWaveApi api = SeatWaveApi.start(withDatabase("cursor"), Clock.systemUTC()); ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT))` starts a whole API on its own in-memory database and a client for it, and closes both at the end. The `Tracker` remembers what the walk saw and makes the changes. `initialSeats()` is the set of every seat that exists at the start.

```java
      do {
        String query = cursor.isEmpty() ? "" : "&cursor=" + cursor;
        ApiClient.Answer page =
            client.get("/v1/events/1/seats?state=available&limit=" + changes.pageSize() + query);
        pages++;
        latencies.add(page.elapsed());
        long pageBytes = page.body().getBytes(StandardCharsets.UTF_8).length;
        bytes[0] += pageBytes;
        bytes[1] = Math.max(bytes[1], pageBytes);
        tracker.see(labelsIn(page.body()));
        tracker.change();
        Matcher next = NEXT_CURSOR.matcher(page.body());
        cursor = next.find() ? next.group(1) : "";
      } while (!cursor.isEmpty());
      cursorWalk = tracker.result(pages);
    }
```

The loop `do { ... } while (!cursor.isEmpty())` asks for pages until no cursor is left. The request is `GET /v1/events/1/seats?state=available&limit=<pageSize>`, plus `&cursor=...` after the first page. For each page: count it, record its latency, add its size in bytes, tell the tracker which seats it contained (`labelsIn`), and then call `tracker.change()`, which holds and inserts seats **between pages**. `NEXT_CURSOR.matcher(page.body()).find()` looks for the `nextCursor` in the reply; no match means the last page, so the cursor becomes empty and the loop ends.

```java
    Walk offsetWalk;
    try (SeatWaveApi api = SeatWaveApi.start(withDatabase("offset"), Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
      Tracker tracker = new Tracker(api, client, initialSeats());
      OffsetSeatReader reader = new OffsetSeatReader(api.database(), Clock.systemUTC());
      int pages = 0;
      List<String> page;
      do {
        page = reader.availableSeats(changes.pageSize(), pages * changes.pageSize());
        pages++;
        tracker.see(page);
        tracker.change();
      } while (page.size() == changes.pageSize());
      offsetWalk = tracker.result(pages);
    }
```

The second walk uses a second API with its own database (`withDatabase("offset")`) and the `OffsetSeatReader`. Page `pages` starts at offset `pages * pageSize`. It stops when a page holds fewer rows than the page size. The same tracker logic makes the same changes in the same order.

```java
    return new Report(
        cursorWalk, offsetWalk, bytes[0] / cursorWalk.pages(), bytes[1], latencies.summary());
  }
```

The report carries both walks, the average page size in bytes (`bytes[0] / cursorWalk.pages()`), the largest page and the latency summary.

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

`initialSeats` builds every label `A-1` to the last seat of the last section. `(char) ('A' + section)` turns the section number 0, 1, 2 into the letters A, B, C: adding a number to a character gives a number, and the cast `(char)` turns it back into a letter.

```java
  private ApiSettings withDatabase(String name) {
    return new ApiSettings(
        settings.httpPort(),
        settings.maxInFlight(),
        "jdbc:h2:mem:page-walk-" + name,
        settings.seed(),
        settings.payment(),
        settings.seatPrice(),
        settings.currency(),
        settings.retryAfter(),
        settings.version0());
  }
```

`withDatabase` copies the settings and replaces only the database URL, so each walk has an in-memory database of its own (`jdbc:h2:mem:page-walk-cursor` and `...-offset`).

```java
  private static List<String> labelsIn(String body) {
    return SEAT.matcher(body).results().map(match -> match.group(1)).toList();
  }
```

`labelsIn` finds every `"seat":"X-n"` in a reply body: `SEAT.matcher(body).results()` gives all matches as a stream, and `.map(match -> match.group(1))` takes the label.

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

`Tracker` is an **inner class** (declared inside `PageWalk`, without `static`), so it can read the walk's `settings` and `changes`. It holds the set of stable seats (a copy of the starting set), how many times each seat was seen, a random generator seeded the same for both walks (`SplittableRandom`), and the next free number for new seats in each section.

```java
    void see(List<String> labels) {
      returned += labels.size();
      labels.forEach(label -> timesSeen.merge(label, 1, Integer::sum));
    }
```

`see` adds the page's seats to the count. `timesSeen.merge(label, 1, Integer::sum)` adds one to the count of that label, starting from 1.

```java
    void change() throws IOException, InterruptedException, SQLException {
      for (int hold = 0; hold < changes.holdsPerPage(); hold++) {
        String seat = randomSeat();
        ApiClient.Answer answer =
            client.postJson(
                "/v1/holds",
                "walk-" + seat,
                "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"walker\"}");
        if (answer.status() == CREATED) {
          // A seat held during the walk is not stable: the walk may or may not return it.
          stable.remove(seat);
        }
      }
      for (int insert = 0; insert < changes.insertsPerPage(); insert++) {
        insertSeat((char) ('A' + random.nextInt(settings.seed().sections())));
      }
    }
```

`change` makes the changes between two pages. For each hold: pick a random seat (`randomSeat`), and `POST /v1/holds` with the key `"walk-" + seat`. If the reply is `201 Created`, the seat is removed from the stable set: it is no longer available, so the walk may or may not return it, and neither counts against the walk. For each insert: choose a random section and add a new seat at the end of it.

```java
    Walk result(int pages) {
      int skipped = (int) stable.stream().filter(seat -> !timesSeen.containsKey(seat)).count();
      int repeated = (int) timesSeen.values().stream().filter(times -> times > 1).count();
      return new Walk(pages, returned, stable.size(), skipped, repeated);
    }
```

`result` computes the answer. `skipped` counts stable seats that were never seen. `repeated` counts seats seen more than once. `stable.size()` is the number of stable seats.

```java
    private String randomSeat() {
      char section = (char) ('A' + random.nextInt(settings.seed().sections()));
      return section + "-" + (1 + random.nextInt(settings.seed().seatsPerSection()));
    }
```

`randomSeat` picks a random section letter and a random number from 1 to the section size.

```java
    private void insertSeat(char section) throws SQLException {
      int number = nextNewSeat.merge(section, 1, Integer::sum) + FIRST_NEW_SEAT_NUMBER - 1;
      try (Connection connection = api.database().connect();
          PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO seats (event_id, seat_label, section, seat_number, status)"
                      + " VALUES (1, ?, ?, ?, 'AVAILABLE')")) {
        insert.setString(1, section + "-" + number);
        insert.setString(2, String.valueOf(section));
        insert.setInt(3, number);
        insert.executeUpdate();
      }
    }
```

`insertSeat` adds a seat straight into the database with `INSERT INTO seats (...) VALUES (1, ?, ?, ?, 'AVAILABLE')`, because the API has no operation that adds seats. The number comes from `nextNewSeat.merge(section, 1, Integer::sum) + FIRST_NEW_SEAT_NUMBER - 1`: 1,001 for the first new seat of a section, 1,002 for the second, then 1,003. The two string conversions fill the label and the section.

### `PageWalkCli.java`

```java
package io.plainround.seatwave.api.load;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
```

```java
/**
 * Command-line entry point for the pagination consistency test. Exit status 0 means the cursor walk
 * skipped and repeated nothing; 3 means it did; 2 bad usage; 1 a failure to run.
 */
public final class PageWalkCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int EXIT_INCONSISTENT = 3;
  private static final int MAX_CHANGES = 1_000;

  private PageWalkCli() {}
```

The Javadoc gives the exit statuses: 0 means the cursor walk skipped and repeated nothing; 3 means it did; 2 is bad usage; 1 a failure to run.

```java
  /**
   * Runs the walk described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting for a reply
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: pageWalk <page-walk.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

`main` needs exactly one argument, the settings file.

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      PageWalk.Changes changes =
          new PageWalk.Changes(
              reader.intBetween("walk.pageSize", 1, ContractLimits.MAX_PAGE_LIMIT),
              reader.intBetween("walk.holdsPerPage", 0, MAX_CHANGES),
              reader.intBetween("walk.insertsPerPage", 0, MAX_CHANGES));
```

The settings file holds the three walk keys (`walk.pageSize`, `walk.holdsPerPage`, `walk.insertsPerPage`) beside the API's own keys. The page size is limited to the contract's maximum (500), and the change counts to 1,000.

```java
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) -> {
          PageWalk.Report report = new PageWalk(settings, changes).run();
          print(report, changes);
          boolean isConsistent = report.cursor().skipped() == 0 && report.cursor().repeated() == 0;
          System.exit(isConsistent ? 0 : EXIT_INCONSISTENT);
        }
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

If the API settings are valid, the walk runs, the result is printed, and the exit status is 0 only if the cursor walk skipped nothing and repeated nothing. Otherwise the problems are printed and the status is 2.

```java
    } catch (IOException | SQLException failed) {
      System.err.println("walk failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

I/O and SQL failures end with status 1.

```java
  private static void print(PageWalk.Report report, PageWalk.Changes changes) {
    System.out.printf(
        Locale.ROOT,
        "Java %s on %s %s; SeatWave API in this process on 127.0.0.1; H2 in memory%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"));
    System.out.printf(
        Locale.ROOT,
        "walk: every available seat of event 1, %d per page; after each page fans hold %d seats"
            + " and the venue adds %d (same seeded schedule for both walks)%n",
        changes.pageSize(),
        changes.holdsPerPage(),
        changes.insertsPerPage());
    System.out.printf(
        Locale.ROOT,
        "%-8s %6s %9s %13s %8s %9s%n",
        "walk",
        "pages",
        "returned",
        "stable seats",
        "skipped",
        "repeated");
    printWalk("cursor", report.cursor());
    printWalk("offset", report.offset());
    System.out.printf(
        Locale.ROOT,
        "cursor pages: %d bytes on average, %d at most; latency %s%n",
        report.averagePageBytes(),
        report.largestPageBytes(),
        report.pageLatency());
  }
```

`print` writes the report: the Java version and the machine (`Runtime.version()`, `System.getProperty("os.name")`), a line that says what the walk was, the table header, the two table rows, and the page size and latency of the cursor pages. In `%-8s %6s ...`, a number after `%` is the field width and `-` aligns left.

```java
  private static void printWalk(String name, PageWalk.Walk walk) {
    System.out.printf(
        Locale.ROOT,
        "%-8s %6d %9d %13d %8d %9d%n",
        name,
        walk.pages(),
        walk.returned(),
        walk.stableSeats(),
        walk.skipped(),
        walk.repeated());
  }
```

`printWalk` prints one row of the table.

### `RetryStorm.java`

```java
package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.SeatWaveApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentStandIn;
import io.plainround.seatwave.api.payment.StandInMode;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

Imports. `Semaphore`, `Future`, `ExecutorService` and `ConcurrentHashMap` are the concurrency tools. `IntFunction` is a function from a whole number to a value.

```java
/**
 * The retry storm: every write sent many times at once, through real HTTP on 127.0.0.1, then the
 * proof read from the database and from the payment provider's ledger, never from the replies.
 *
 * <p>Three scenarios: one hold sent {@code concurrency} times at once with one key; {@code pairs}
 * holds and their confirms, each sent twice at once with its own key; and one seat wanted by {@code
 * concurrency} fans with different keys (Module 1's race, as a control). A client that gets 503
 * retries the same request with the same key, with exponential backoff and jitter, as the contract
 * asks.
 *
 * <p>Not thread-safe: one storm runs once, from one thread; it starts its own virtual threads.
 */
public final class RetryStorm {
```

The Javadoc names the three scenarios. One: one hold sent `concurrency` times at once with one key. Two: `pairs` holds and their confirms, each sent twice at once with its own key. Three: one seat wanted by `concurrency` fans with different keys (Module 1's race, as a control). A client that gets a 503 retries the same request with the same key, with exponential backoff and jitter, as the contract asks. The class is not thread-safe: one storm runs once from one thread, and starts its own virtual threads.

```java
  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final int CREATED = 201;
  private static final int UNAVAILABLE = 503;
  private static final int CONFLICT = 409;
  // Retries after a 503: at most 6, starting at 20 ms and doubling, capped at 640 ms, with full
  // jitter (a random wait from 0 to the cap) so the retries do not arrive together again.
  private static final int MAX_RETRIES = 6;
  private static final Duration FIRST_BACKOFF = Duration.ofMillis(20);
  private static final Duration MAX_BACKOFF = Duration.ofMillis(640);
  private static final long RANDOM_SEED = 20_261_111L;
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final char FIRST_PAIR_SECTION = 'C';
  // Sections A and B belong to scenarios 1 and 3.
  private static final int SECTIONS_NOT_FOR_PAIRS = 2;

```

`HOLD_ID` pulls a hold id (36 characters of hex digits and hyphens) from a reply. The comment explains the retry rule: at most 6 retries, starting at 20 ms and doubling, capped at 640 ms, with full jitter. The scenarios use different seats of the 20,000: scenario one uses A-1, scenario three B-1, and the pairs start in section C (the first pair section is `'C'`, and two sections are not for pairs).

```java
  private final ApiSettings settings;
  private final int concurrency;
  private final int pairs;
  private final SplittableRandom random = new SplittableRandom(RANDOM_SEED);
  private final Latencies latencies = new Latencies();
  private final Map<Integer, Integer> statusCounts = new ConcurrentHashMap<>();
  private int retries;
```

Fields. `statusCounts` is a concurrent map, because many threads add to it. `retries` is a plain `int` and is only changed in a `synchronized` method.

```java
  /**
   * Prepares a storm.
   *
   * @param settings the API's settings; the database must be in memory and the provider's port free
   * @param concurrency how many copies of one request are sent at once
   * @param pairs how many seats are held and confirmed, each request twice
   */
  public RetryStorm(ApiSettings settings, int concurrency, int pairs) {
    this.settings = Objects.requireNonNull(settings, "settings");
    if (concurrency < 1 || pairs < 1) {
      throw new IllegalArgumentException("concurrency and pairs must be 1 or more");
    }
    int seatsForPairs =
        (settings.seed().sections() - SECTIONS_NOT_FOR_PAIRS) * settings.seed().seatsPerSection();
    if (pairs > seatsForPairs) {
      throw new IllegalArgumentException("pairs must fit in sections C onward: " + seatsForPairs);
    }
    this.concurrency = concurrency;
    this.pairs = pairs;
  }

```

The constructor refuses fewer than one copy or pair, and a number of pairs that does not fit: pairs use sections C onward, so with 20 sections of 1,000 seats at most 18,000 pairs fit.

```java
  /**
   * What the storm sent and what the database and the provider hold afterwards.
   *
   * @param sameKeyReplies scenario 1: status code to count
   * @param sameKeyHoldIds scenario 1: distinct hold ids in the replies
   * @param sameKeySeatsHeld scenario 1: seats the database shows held under any of those ids
   * @param pairsRequests scenario 2: requests sent (holds and confirms, each twice)
   * @param pairsBookings scenario 2: rows in {@code bookings}
   * @param seatsBookedTwice scenario 2: seats with more than one booking row
   * @param providerCharges scenario 2: charges in the provider's ledger
   * @param holdsChargedTwice scenario 2: holds with more than one charge
   * @param sameSeatReplies scenario 3: status code to count
   * @param sameSeatHolders scenario 3: rows holding the seat
   * @param keysStored rows in the idempotency-key table at the end
   * @param retries how many times a client retried after a 503 or a confirm-in-progress 409
   * @param allReplies every final status code, to count
   * @param latency the latency of every request, retries included
   */
  public record Report(
      Map<Integer, Integer> sameKeyReplies,
      int sameKeyHoldIds,
      long sameKeySeatsHeld,
      int pairsRequests,
      long pairsBookings,
      long seatsBookedTwice,
      int providerCharges,
      long holdsChargedTwice,
      Map<Integer, Integer> sameSeatReplies,
      long sameSeatHolders,
      long keysStored,
      int retries,
      Map<Integer, Integer> allReplies,
      String latency) {}

```

`Report` is the record of everything the storm measured: for scenario one the replies, the number of distinct hold ids and the seats the database shows held; for scenario two the requests sent, the booking rows, the seats with more than one booking, the provider's charges and the holds charged twice; for scenario three the replies and the rows holding the seat; and the keys stored, the retries, every final status and the latency.

```java
  /**
   * Runs the three scenarios.
   *
   * @return the report
   * @throws IOException if a port cannot be bound
   * @throws SQLException if the database fails
   * @throws InterruptedException if interrupted while waiting for the requests
   */
  public Report run() throws IOException, SQLException, InterruptedException {
```

`run` runs the three scenarios.

```java
    try (PaymentStandIn provider =
            PaymentStandIn.start(
                settings.payment().baseUrl().getPort(),
                StandInMode.OK,
                Duration.ZERO,
                new JsonCodec());
        SeatWaveApi api = SeatWaveApi.start(settings, Clock.systemUTC());
        ApiClient client = new ApiClient(api.port(), CLIENT_TIMEOUT)) {
```

Three resources in one `try`: the stand-in provider on the port of `payment.url`, in mode `OK` with no delay; the API; and the client. All three are closed at the end, in reverse order.

```java
      Map<Integer, Integer> sameKey = new TreeMap<>();
      List<String> sameKeyIds = new ArrayList<>();
      for (ApiClient.Answer answer :
          sendAtOnce(client, concurrency, copy -> holdRequest("A-1", "storm-one-key"))) {
        sameKey.merge(answer.status(), 1, Integer::sum);
        holdIdIn(answer.body()).filter(id -> !sameKeyIds.contains(id)).ifPresent(sameKeyIds::add);
      }
      long sameKeyHeld = countSeatsHeldBy(api, sameKeyIds);
      int pairsRequests = runPairs(client);
```

Scenario one. `sendAtOnce(client, concurrency, copy -> holdRequest("A-1", "storm-one-key"))` sends the same hold request, seat A-1 with the key `storm-one-key`, `concurrency` times. The loop counts the final statuses and the distinct hold ids found in the replies. `countSeatsHeldBy` then asks the database how many seats are `HELD` under those ids. The expected proof: one id, one seat.

```java
      Map<Integer, Integer> sameSeat = new TreeMap<>();
      for (ApiClient.Answer answer :
          sendAtOnce(client, concurrency, fan -> holdRequest("B-1", "fan-" + fan))) {
        sameSeat.merge(answer.status(), 1, Integer::sum);
      }
```

Scenario three. `fan -> holdRequest("B-1", "fan-" + fan)` sends one request per fan with a different key for the same seat.

```java
      return new Report(
          sameKey,
          sameKeyIds.size(),
          sameKeyHeld,
          pairsRequests,
          count(api, "SELECT COUNT(*) FROM bookings"),
          count(
              api,
              "SELECT COUNT(*) FROM (SELECT event_id, seat_label FROM bookings"
                  + " GROUP BY event_id, seat_label HAVING COUNT(*) > 1)"),
          provider.chargeCount(),
          countHoldsChargedTwice(api, provider),
          sameSeat,
          count(
              api,
              "SELECT COUNT(*) FROM seats WHERE event_id = 1 AND seat_label = 'B-1'"
                  + " AND status = 'HELD'"),
          count(api, "SELECT COUNT(*) FROM idempotency_keys"),
          retries,
          new TreeMap<>(statusCounts),
          latencies.summary());
    }
  }

```

The `Report` is built from the counts. The proofs are SQL: the number of rows in `bookings`; the number of seats that have more than one booking (`GROUP BY event_id, seat_label HAVING COUNT(*) > 1`); the provider's `chargeCount()`; the holds charged twice; the rows with seat `B-1` in state `HELD`; and the rows in `idempotency_keys`.

```java
  private int runPairs(ApiClient client) throws InterruptedException {
    List<HttpCall> holds = new ArrayList<>();
    for (int pair = 0; pair < pairs; pair++) {
      HttpCall hold = holdRequest(seatFor(pair), "pair-hold-" + pair);
      holds.add(hold);
      holds.add(hold);
    }
    List<ApiClient.Answer> held = sendAll(client, holds);
    List<HttpCall> confirms = new ArrayList<>();
    for (int index = 0; index < held.size(); index += 2) {
      String holdId = holdIdIn(held.get(index).body()).orElseThrow();
      HttpCall confirm =
          new HttpCall("/v1/bookings", "pair-confirm-" + index, "{\"holdId\":\"" + holdId + "\"}");
      confirms.add(confirm);
      confirms.add(confirm);
    }
    sendAll(client, confirms);
    return holds.size() + confirms.size();
  }
```

`runPairs` is scenario two. For each pair it builds a hold request with the key `pair-hold-<n>` and adds it **twice** to the list. All of them are sent. From each pair's first reply it takes the hold id and builds a confirm request with the key `pair-confirm-<index>`, again added twice, and sends them. It returns the number of requests sent.

```java
  private String seatFor(int pair) {
    // Sections C onward, so the pairs never touch the seats of scenarios 1 (A) and 3 (B).
    int seatsPerSection = settings.seed().seatsPerSection();
    char section = (char) (FIRST_PAIR_SECTION + pair / seatsPerSection);
    return section + "-" + (pair % seatsPerSection + 1);
  }
```

`seatFor` maps a pair number to a seat from section C onward: the section letter is `'C'` plus `pair / seatsPerSection`, and the number is `pair % seatsPerSection + 1`.

```java
  private static HttpCall holdRequest(String seat, String key) {
    return new HttpCall(
        "/v1/holds", key, "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"storm\"}");
  }
```

`holdRequest` builds the JSON body `{"eventId":1,"seat":"...","customer":"storm"}`.

```java
  private List<ApiClient.Answer> sendAtOnce(
      ApiClient client, int copies, IntFunction<HttpCall> callFor) throws InterruptedException {
    List<HttpCall> calls = new ArrayList<>();
    for (int copy = 0; copy < copies; copy++) {
      calls.add(callFor.apply(copy));
    }
    return sendAll(client, calls);
  }
```

`sendAtOnce` builds `copies` calls and sends them all.

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

`sendAll` sends a list of calls. The comment explains: all requests start together, and the semaphore keeps at most `concurrency` on the wire. `Executors.newVirtualThreadPerTaskExecutor()` in a `try` makes one virtual thread per call; the `try` block does not end until all tasks have finished. Each task takes a permit (`onTheWire.acquire()`), sends with retries, and returns the permit in `finally`. `threads.submit(...)` returns a `Future`, a handle on a result that will arrive. After the block, every `Future` is read with `resultOf`, in order, so the answers keep the order of the calls.

```java
  private ApiClient.Answer sendWithRetries(ApiClient client, HttpCall call)
      throws IOException, InterruptedException {
    Duration backoff = FIRST_BACKOFF;
    ApiClient.Answer answer = client.postJson(call.path(), call.key(), call.json());
    record(answer);
    for (int retry = 0; retry < MAX_RETRIES && isRetryable(answer); retry++) {
      Thread.sleep(Duration.ofMillis(jitter(backoff.toMillis())));
      Duration doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
      countRetry();
      // The same key and the same body: a retry, not a new request.
      answer = client.postJson(call.path(), call.key(), call.json());
      record(answer);
    }
    statusCounts.merge(answer.status(), 1, Integer::sum);
    return answer;
  }
```

`sendWithRetries` is the retry rule. It sends once and records the latency. While the answer is retryable and fewer than six retries were made: wait a random time from 0 to the current backoff (`jitter`), double the backoff up to the cap, count the retry, and send **the same path, key and body again**. At the end it counts the final status once and returns the last answer.

```java
  private static boolean isRetryable(ApiClient.Answer answer) {
    // 503 says "try again"; 409 confirm-in-progress says "the first attempt is still running".
    // Both are retried with the same key. Every other answer is final.
    return answer.status() == UNAVAILABLE
        || (answer.status() == CONFLICT && answer.body().contains("/problems/confirm-in-progress"));
  }
```

`isRetryable`: a 503 says "try again", and a 409 whose body contains `/problems/confirm-in-progress` says "the first attempt is still running". Both are retried with the same key. Every other answer, including a 409 `seat-unavailable`, is final.

```java
  private synchronized long jitter(long capMillis) {
    // SplittableRandom is not thread-safe, so the draw is synchronized; it is not on the hot path.
    return random.nextLong(capMillis + 1);
  }

  private synchronized void countRetry() {
    retries++;
  }

  private synchronized void record(ApiClient.Answer answer) {
    latencies.add(answer.elapsed());
  }
```

Three `synchronized` methods. `synchronized` lets only one thread at a time run the method on the same object. `jitter` draws a random number (`SplittableRandom` is not safe for many threads), `countRetry` adds one, and `record` adds a latency sample.

```java
  private static ApiClient.Answer resultOf(Future<ApiClient.Answer> answer)
      throws InterruptedException {
    try {
      return answer.get();
    } catch (ExecutionException failed) {
      throw new IllegalStateException("a storm request failed outright", failed.getCause());
    }
  }
```

`resultOf` reads a `Future`. `answer.get()` waits for the result; a task that failed throws `ExecutionException`, and the code rethrows it as an `IllegalStateException` with the real cause.

```java
  private static Optional<String> holdIdIn(String body) {
    Matcher matcher = HOLD_ID.matcher(body);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }
```

`holdIdIn` finds a hold id in a reply body.

```java
  private static long countSeatsHeldBy(SeatWaveApi api, List<String> holdIds) throws SQLException {
    long held = 0;
    try (Connection connection = api.database().connect();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM seats WHERE hold_id = ? AND status = 'HELD'")) {
      for (String holdId : holdIds) {
        query.setString(1, holdId);
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          held += rows.getLong(1);
        }
      }
    }
    return held;
  }
```

`countSeatsHeldBy` runs one prepared statement for each hold id and adds the counts. The statement asks for the seats whose `hold_id` is the id and whose status is `HELD`.

```java
  private static long countHoldsChargedTwice(SeatWaveApi api, PaymentStandIn provider)
      throws SQLException {
    long chargedTwice = 0;
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement("SELECT hold_id FROM bookings");
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        if (provider.chargesFor(rows.getString(1)) > 1) {
          chargedTwice++;
        }
      }
    }
    return chargedTwice;
  }
```

`countHoldsChargedTwice` reads every `hold_id` from `bookings` and asks the provider's ledger how many charges that hold has. A count above one is a double charge.

```java
  private static long count(SeatWaveApi api, String sql) throws SQLException {
    // The SQL is one of this class's constants, never input.
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement(sql);
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
```

`count` runs a single `SELECT COUNT(*)` and returns the number. The comment says the SQL is always one of this class's own strings, never input.

```java
  /** One request: where, under which key, with which body. */
  private record HttpCall(String path, String key, String json) {}
```

`HttpCall` is the path, key and body of one request.

### `RetryStormCli.java`

```java
package io.plainround.seatwave.api.load;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
```

```java
/**
 * Command-line entry point for the retry storm: starts the API and the stand-in provider in this
 * process on 127.0.0.1, fires the storm, and prints what the database and the ledger hold. Exit
 * status 0 means no double effect; 3 means one was found; 2 bad usage; 1 a failure to run.
 */
public final class RetryStormCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int EXIT_DOUBLE_EFFECT = 3;
  private static final int MAX_CONCURRENCY = 1_000;
  private static final int MAX_PAIRS = 18_000;

  private RetryStormCli() {}
```

The exit statuses: 0 means no double effect, 3 means one was found, 2 bad usage, 1 a failure. `MAX_CONCURRENCY` is 1,000 and `MAX_PAIRS` 18,000.

```java
  /**
   * Runs the storm described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting for the requests
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: retryStorm <retry-storm.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

`main` takes one argument.

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int concurrency = reader.intBetween("storm.concurrency", 1, MAX_CONCURRENCY);
      int pairs = reader.intBetween("storm.pairs", 1, MAX_PAIRS);
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) -> {
          RetryStorm.Report report = new RetryStorm(settings, concurrency, pairs).run();
          print(report, concurrency, pairs);
          boolean isClean =
              report.sameKeyHoldIds() == 1
                  && report.sameKeySeatsHeld() == 1
                  && report.seatsBookedTwice() == 0
                  && report.holdsChargedTwice() == 0
                  && report.sameSeatHolders() == 1;
          System.exit(isClean ? 0 : EXIT_DOUBLE_EFFECT);
        }
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

It reads `storm.concurrency` and `storm.pairs`, then the API settings, runs the storm, prints, and decides: the run is clean only if scenario one gave exactly one hold id and one held seat, no seat was booked twice, no hold was charged twice, and the contested seat has exactly one holder.

```java
    } catch (IOException | SQLException failed) {
      System.err.println("storm failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

```java
  private static void print(RetryStorm.Report report, int concurrency, int pairs) {
    System.out.printf(
        Locale.ROOT,
        "Java %s on %s %s; SeatWave API and the stand-in provider in this process, on 127.0.0.1;"
            + " H2 in memory%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"));
    System.out.printf(
        Locale.ROOT, "scenario 1: one hold sent %d times at once with one key%n", concurrency);
    System.out.printf(Locale.ROOT, "  final replies by status: %s%n", report.sameKeyReplies());
    System.out.printf(
        Locale.ROOT,
        "  distinct hold ids in the replies: %d; seats the database shows held by them: %d%n",
        report.sameKeyHoldIds(),
        report.sameKeySeatsHeld());
    System.out.printf(
        Locale.ROOT,
        "scenario 2: %d holds and %d confirms, each sent twice at once with its own key"
            + " (%d requests)%n",
        pairs,
        pairs,
        report.pairsRequests());
    System.out.printf(
        Locale.ROOT,
        "  database: %d bookings, %d seats booked twice; provider: %d charges, %d holds charged"
            + " twice%n",
        report.pairsBookings(),
        report.seatsBookedTwice(),
        report.providerCharges(),
        report.holdsChargedTwice());
    System.out.printf(
        Locale.ROOT,
        "scenario 3: one seat wanted by %d fans with %d keys%n",
        concurrency,
        concurrency);
    System.out.printf(Locale.ROOT, "  final replies by status: %s%n", report.sameSeatReplies());
    System.out.printf(
        Locale.ROOT, "  database: %d holder(s) of the seat%n", report.sameSeatHolders());
    System.out.printf(
        Locale.ROOT,
        "all requests: final statuses %s; retries (503, or 409 in progress): %d;"
            + " idempotency keys stored: %d%n",
        report.allReplies(),
        report.retries(),
        report.keysStored());
    System.out.printf(Locale.ROOT, "latency of every attempt: %s%n", report.latency());
  }
```

`print` writes the lines you see in the output below: the environment, then each scenario with its counts, the totals and the latency.

### `KeyStoreSizeCli.java`

```java
package io.plainround.seatwave.api.load;

import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
```

```java
/**
 * Measures what one stored key costs on disk: fills a fresh H2 file database under {@code
 * build/key-size/} with keys that each hold a hold's real reply, compacts it, and prints the file
 * size with and without the keys. Exit status 2 means bad usage; 1 a failure to run.
 */
public final class KeyStoreSizeCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final Pattern COUNT = Pattern.compile("[1-9][0-9]{0,6}");
  private static final int MAX_KEYS = 1_000_000;
  // Commit every 1,000 keys: one transaction per key would measure fsyncs, not bytes.
  private static final int KEYS_PER_COMMIT = 1_000;
  private static final Path FOLDER = Path.of("build", "key-size");
  private static final String ENDPOINT = "POST /v1/holds";
  private static final int CREATED = 201;

  private KeyStoreSizeCli() {}
```

The Javadoc: it fills a fresh H2 **file** database under `build/key-size/` with keys that each hold a hold's real reply, compacts it, and prints the file size with and without the keys. The comment on `KEYS_PER_COMMIT` says why it commits every 1,000 keys: one transaction per key would measure disk syncs, not bytes.

```java
  /**
   * Measures the bytes per key.
   *
   * @param arguments how many keys to store, 1 to 1,000,000
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1
        || !COUNT.matcher(arguments[0]).matches()
        || Integer.parseInt(arguments[0]) > MAX_KEYS) {
      System.err.println("usage: keyStoreSize <keys, 1 to 1000000>");
      System.exit(EXIT_BAD_USAGE);
    }
```

`main` accepts one argument that must be one to seven digits and not above 1,000,000.

```java
    int keys = Integer.parseInt(arguments[0]);
    try {
      long empty = sizeAfter(0, "empty");
      long full = sizeAfter(keys, "full");
      System.out.printf(
          Locale.ROOT,
          "H2 file database, compacted: %,d bytes with no keys, %,d bytes with %,d keys%n",
          empty,
          full,
          keys);
      System.out.printf(
          Locale.ROOT,
          "bytes per stored key, table and indexes: %.0f%n",
          (full - empty) / (double) keys);
    } catch (IOException | SQLException failed) {
      System.err.println("measurement failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

It measures a database with no keys and one with `keys` keys, prints both sizes (`%,d` adds thousands separators) and the growth divided by the number of keys.

```java
  private static long sizeAfter(int keys, String name) throws IOException, SQLException {
    Files.createDirectories(FOLDER);
    Path file = FOLDER.resolve(name + ".mv.db");
    Files.deleteIfExists(file);
    String url = "jdbc:h2:file:" + FOLDER.resolve(name).toAbsolutePath();
    IdempotencyStore store = new IdempotencyStore();
    Instant now = Instant.now();
```

`sizeAfter` makes the folder, deletes an old file, and builds the database URL from the folder and a name.

```java
    try (Database database = Database.open(url);
        Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      for (int index = 0; index < keys; index++) {
        String holdId = UUID.randomUUID().toString();
        KeyClaim claim =
            new KeyClaim(
                ENDPOINT,
                new IdempotencyKey(UUID.randomUUID().toString()),
                Fingerprint.of(ENDPOINT, holdId));
        store.claim(connection, claim, now);
```

It opens the database and a connection, turns **auto-commit** off (so many changes can be committed together), and for each key claims it with a random key and a fingerprint.

```java
        // The same reply a real hold stores: 131 bytes of JSON and its Location.
        store.complete(
            connection,
            claim,
            new StoredReply(
                CREATED,
                "/v1/holds/" + holdId,
                "{\"holdId\":\""
                    + holdId
                    + "\",\"eventId\":1,\"seat\":\"A-7\",\"state\":\"held\","
                    + "\"expiresAt\":\"2026-11-11T09:05:00.000000Z\"}"));
        if ((index + 1) % KEYS_PER_COMMIT == 0) {
          connection.commit();
        }
      }
```

Then it completes the claim with a stored reply: a 201, the `Location` and a JSON body like a real hold reply (the comment says 131 bytes).

```java
      connection.commit();
      connection.setAutoCommit(true);
      try (Statement statement = connection.createStatement()) {
        // Compacting writes the live data densely, so the file size is the data, not free space.
        statement.execute("SHUTDOWN COMPACT");
      }
    }
    return Files.size(file);
  }
```

Every 1,000 keys it commits. At the end it commits, turns auto-commit back on, and runs `SHUTDOWN COMPACT`: the comment says compacting writes the live data densely, so the file size is the data, not free space. The size of the file is the result.

## Try it yourself

All commands run in the repository folder. I ran them in a copy of the repository with other port numbers in the copied settings files (the originals use 18087 to 18090), so that they could not clash with another program. The numbers differ from run to run, and timings differ on every machine.

1. The shortest storm: 10 copies and 20 pairs. I wrote a small settings file (a copy of `config/retry-storm.properties` with `storm.concurrency=10` and `storm.pairs=20`) and ran it. It took about two seconds:

```text
$ ./gradlew -q retryStorm --args='/path/to/storm-small.properties'
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API and the stand-in provider in this process, on 127.0.0.1; H2 in memory
scenario 1: one hold sent 10 times at once with one key
  final replies by status: {201=10}
  distinct hold ids in the replies: 1; seats the database shows held by them: 1
scenario 2: 20 holds and 20 confirms, each sent twice at once with its own key (80 requests)
  database: 20 bookings, 0 seats booked twice; provider: 20 charges, 0 holds charged twice
scenario 3: one seat wanted by 10 fans with 10 keys
  final replies by status: {201=1, 409=9}
  database: 1 holder(s) of the seat
all requests: final statuses {201=91, 409=9}; retries (503, or 409 in progress): 18; idempotency keys stored: 51
latency of every attempt: p50 3.95 ms, p99 137.22 ms, max 137.29 ms (118 samples)
```

   Read it like this. Scenario one: ten copies, ten `201` replies, but one hold id and one held seat: the other nine were replays. Scenario two: 20 bookings and 20 charges for 20 seats. Scenario three: one `201` and nine `409`, the race of Module 1. The 18 retries here were all `409 confirm-in-progress` (the log lines show 18 `409` on `POST /v1/bookings`): the second copy of a confirm arrived while the first was paying, and was told to retry with the same key. 51 keys: 1, plus 20 and 20, plus 10. The 118 samples are the 100 requests plus 18 retries. The server wrote 118 log lines to standard error, one for each attempt.

2. The full storm from the repository settings (100 copies, 500 pairs). Command: `./gradlew -q retryStorm --args='config/retry-storm.properties'`. It took about three seconds of work after the first build:

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API and the stand-in provider in this process, on 127.0.0.1; H2 in memory
scenario 1: one hold sent 100 times at once with one key
  final replies by status: {201=100}
  distinct hold ids in the replies: 1; seats the database shows held by them: 1
scenario 2: 500 holds and 500 confirms, each sent twice at once with its own key (2000 requests)
  database: 500 bookings, 0 seats booked twice; provider: 500 charges, 0 holds charged twice
scenario 3: one seat wanted by 100 fans with 100 keys
  final replies by status: {201=1, 409=99}
  database: 1 holder(s) of the seat
all requests: final statuses {201=2101, 409=99}; retries (503, or 409 in progress): 661; idempotency keys stored: 1101
latency of every attempt: p50 24.12 ms, p99 349.65 ms, max 382.48 ms (2861 samples)
```

   The retry count (661 here, 620 in the README's run) and the latencies change from run to run, because the interleaving of threads does. The proof lines never change: `0 seats booked twice`, `0 holds charged twice`, one holder. The 2,861 samples are the 2,200 requests plus 661 retries. The log showed 132 replies of `503` (61 on `POST /v1/bookings` and 71 on `POST /v1/holds`); the log line does not say which kind of 503 each was, so I did not separate them.

3. The page walk with fans holding 25 seats and the venue adding 5 after each page. Command: `./gradlew -q pageWalk --args='config/page-walk.properties'`:

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API in this process on 127.0.0.1; H2 in memory
walk: every available seat of event 1, 500 per page; after each page fans hold 25 seats and the venue adds 5 (same seeded schedule for both walks)
walk      pages  returned  stable seats  skipped  repeated
cursor       40     19611         19026        0         0
offset       39     19264         19050      345         0
cursor pages: 25009 bytes on average, 25557 at most; latency p50 5.14 ms, p99 93.84 ms, max 93.84 ms (40 samples)
```

   The cursor walk returned every stable seat exactly once. The offset walk skipped 345 seats that stayed available the whole time: each hold removes a seat from somewhere in the list and moves every later row up one place, so rows that had not been read yet slide into the part the offset already counted as read, and the next page jumps over them. (The numbers of this run equal the README's, because the schedule is seeded; only the latency differs.)

4. The walk with no holds and 25 inserts after each page (`config/page-walk-inserts.properties`):

```text
walk      pages  returned  stable seats  skipped  repeated
cursor       42     20538         20000        0         0
offset       43     21050         20000        0       500
```

   Now nothing is skipped by the offset walk, and 500 seats are returned twice: a seat added before the read position pushes everything after it one place later, so the next page starts with a row already shown. The cursor walk was clean in both runs. A page of 500 seats is about 25,000 bytes.

5. The cost of one stored key, with 1,000 keys. Command: `./gradlew -q keyStoreSize --args=1000`:

```text
H2 file database, compacted: 12,288 bytes with no keys, 245,760 bytes with 1,000 keys
bytes per stored key, table and indexes: 233
```

   The README's run used 100,000 keys and also found 233 bytes per key. The file is written under `build/key-size/`.

## Mistakes people make

- **Trusting the replies.** A server can answer `201` twice for one booking. The proof here is read from the database and the ledger.
- **A retry storm without jitter.** A hundred clients that fail at once and wait the same time come back together and fail again. Here the wait is random from zero to the cap.
- **Retrying with a new key.** Then it is a new request, and the effect repeats. Here the key and the body never change between attempts.
- **Retrying everything.** A `409 seat-unavailable` is a final answer. Only `503` and `409 confirm-in-progress` are retried.
- **Comparing two pagination styles on different data.** The comparison means nothing if the data differs. Here both walks use the same seed, so they see the same changes in the same order.
- **Measuring commits instead of bytes.** One transaction per key would time disk syncs. `KeyStoreSizeCli` commits every 1,000 keys.
- **A random generator shared by many threads.** `SplittableRandom` is not safe for that. The storm draws jitter inside a `synchronized` method.

## Check yourself

1. Why does the storm read its proof from the database and the provider's ledger?
   Answer: replies can repeat or lie; rows and charges are the actual effects, so they show whether anything happened twice.
2. In scenario one, 100 requests give 100 `201` replies. Why is that not 100 holds?
   Answer: all share one key; the first did the work and the other 99 got the stored reply. The database shows one held seat and the replies hold one hold id.
3. What does jitter do, and what would happen without it?
   Answer: it makes each client wait a random time up to the cap. Without it, clients that failed together retry together and overload the server again.
4. How can an offset walk skip a seat that was available the whole time?
   Answer: a seat before the read position leaves the list (a hold), every later row moves up one place, and the next `OFFSET` jumps over a row that moved into the already-read part.
5. Which exit status means the cursor walk found a skipped or repeated seat?
   Answer: 3 (`EXIT_INCONSISTENT`).

## Further reading

- Java SE 25 API, package `java.net.http`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- Java SE 25 API, `java.util.concurrent.Executors` (virtual-thread executors): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- IETF draft, The Idempotency-Key HTTP Header Field: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- RFC 9110, HTTP Semantics, Section 15.6.4 (503 Service Unavailable) and Section 10.2.3 (Retry-After): https://www.rfc-editor.org/rfc/rfc9110
- H2 database 2.5 documentation, `SHUTDOWN COMPACT`: https://h2database.com/html/commands.html#shutdown
