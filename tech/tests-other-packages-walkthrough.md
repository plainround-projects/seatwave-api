# Walkthrough of the other test packages and the test resources

## What it is

This file covers every test source file that the two other walkthroughs do not: the five helpers in the root test package, the test packages `catalog`, `config`, `http`, `load`, `payment` and `store`, and the one file under `src/test/resources`. The `contract` package is in `tests-contract-walkthrough.md`; the `booking` and `idempotency` packages are in `tests-booking-and-idempotency-walkthrough.md`.

All paths are under `src/test/java/io/plainround/seatwave/api/` unless they start with `src/test/resources/`.

| File | Kind | What it does |
| --- | --- | --- |
| `TestApi.java` | helper | One API, one stand-in payment provider, one database and one clock; judges every reply against the contract. |
| `Contract.java` | helper | The OpenAPI document as a judge of replies and requests. |
| `MutableClock.java` | helper | A clock that moves only when a test moves it. |
| `HttpStatus.java` | helper | Names for the status numbers the tests expect. |
| `AllAtOnce.java` | helper | Runs many tasks at the same moment. |
| `catalog/CursorsTest.java` | test | The pagination cursors: written, read back, forged. |
| `catalog/PaginationConsistencyTest.java` | test | The cursor walk skips and repeats nothing while data changes; the offset walk does. |
| `config/ApiSettingsTest.java` | test | The settings file and the rules for its values. |
| `http/ApiServerTest.java` | test | Unknown paths, wrong methods, overload. |
| `http/Version0CompatibilityTest.java` | test | Module 1's paths still answer as before, with deprecation headers. |
| `load/RetryStormTest.java` | test | The retry storm finds no double effect. |
| `payment/PaymentStandInTest.java` | test | The stand-in provider charges once per key and never pretends. |
| `store/DatabaseTest.java` | test | Migrations apply once; the seed runs once; the database refuses an unknown status. |
| `src/test/resources/logback-test.xml` | resource | The logging setup of the tests. |

## Why this repository uses it

The helpers make every other test short and strict. `TestApi.send` judges every reply against the contract, so a test cannot forget to. `MutableClock` makes time a value the test controls, so expiry is tested without waiting. `AllAtOnce` makes "100 retries at once" real. The other tests prove the parts the lesson depends on: cursors that cannot be forged, settings that cannot be wrong, a database that migrates once and refuses bad data, and a stand-in provider that behaves like a real one.

## Where it appears

The tests run in `./gradlew test` and in `check`. The helpers are used by the tests of every package. Nothing here is shipped: it is all in the test source set.

## How it works underneath

- Every test that needs the service builds a `TestApi`. It starts the real server code on `127.0.0.1` on a port the system picks (port 0 means "any free port"), with an **in-memory** H2 database that has a unique name, so two tests never share data. A stand-in payment provider runs beside it on another free port. Both are closed after the test.
- Requests go over real HTTP with the JDK's `HttpClient`. The reply is read, then handed to the contract validator (`openapi-validator-and-diff.md`).
- Time is an instant read from a `MutableClock`. The server code reads the clock it is given instead of the system clock.
- Counts are read straight from the database with plain SQL, because the proof of an effect is in the data.

## Walkthrough of this repository's use

### `MutableClock.java`

```java
package io.plainround.seatwave.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that moves only when a test moves it, so expiry is tested without waiting. Thread-safe:
 * the instant is an atomic reference read by the server's threads.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  /**
   * Starts the clock.
   *
   * @param start the first instant
   */
  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /**
   * Moves the clock forward.
   *
   * @param step how far
   */
  public void advance(Duration step) {
    now.updateAndGet(current -> current.plus(step));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("SeatWave keeps every instant in UTC");
  }
}
```

- `java.time.Clock` is the JDK's abstract class for "what time is it". The service asks a `Clock` instead of calling `Instant.now()`, which is what lets a test replace it. `MutableClock extends Clock` and implements its three methods.
- `AtomicReference<Instant> now` holds the current instant. "Atomic" means reads and writes are safe when several threads touch it. The Javadoc: "Thread-safe: the instant is an atomic reference read by the server's threads." The server reads the clock on its own threads while the test thread advances it.
- The constructor takes the first instant.
- `advance(Duration step)` moves the clock forward. `now.updateAndGet(current -> current.plus(step))` replaces the value by `current + step` in one atomic step. There is no way to move it backward or to set it.
- `instant()` returns the current value.
- `getZone()` returns UTC. `withZone(...)` throws `UnsupportedOperationException("SeatWave keeps every instant in UTC")`: the API has no use for another zone, and a silent copy with another zone would hide a mistake.

### `HttpStatus.java`

```java
package io.plainround.seatwave.api;

/** The status codes the tests expect, by their RFC 9110 names. */
public final class HttpStatus {

  /** 200 OK. */
  public static final int OK = 200;

  /** 201 Created. */
  public static final int CREATED = 201;

  /** 204 No Content. */
  public static final int NO_CONTENT = 204;

  /** 400 Bad Request. */
  public static final int BAD_REQUEST = 400;

  /** 404 Not Found. */
  public static final int NOT_FOUND = 404;

  /** 405 Method Not Allowed. */
  public static final int METHOD_NOT_ALLOWED = 405;

  /** 409 Conflict. */
  public static final int CONFLICT = 409;

  /** 413 Content Too Large. */
  public static final int CONTENT_TOO_LARGE = 413;

  /** 415 Unsupported Media Type. */
  public static final int UNSUPPORTED_MEDIA_TYPE = 415;

  /** 422 Unprocessable Content. */
  public static final int UNPROCESSABLE_CONTENT = 422;

  /** 503 Service Unavailable. */
  public static final int SERVICE_UNAVAILABLE = 503;

  private HttpStatus() {}
}
```

A class of named constants, so a test says `HttpStatus.CONFLICT` and not `409`. The Javadoc: "The status codes the tests expect, by their RFC 9110 names." The list: 200, 201, 204, 400, 404, 405, 409, 413 (Content Too Large), 415 (Unsupported Media Type), 422 (Unprocessable Content) and 503. The private constructor stops anyone from making an instance.

### `AllAtOnce.java`

```java
package io.plainround.seatwave.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Starts many tasks behind one gate and opens it, so they really run at the same moment. */
public final class AllAtOnce {

  private AllAtOnce() {}
```

The class comment: "Starts many tasks behind one gate and opens it, so they really run at the same moment." The imports are the JDK's concurrency tools. A **thread** is one path of execution; a **virtual thread** is a very cheap thread that the JVM schedules itself, so 100 of them cost almost nothing.

```java
  /**
   * Runs the tasks together.
   *
   * @param count how many
   * @param task the task for each index
   * @param <T> what a task returns
   * @return the results, in index order
   * @throws Exception if a task fails
   */
  public static <T> List<T> run(int count, IntFunction<Callable<T>> task) throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<T>> pending = new ArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < count; index++) {
        Callable<T> work = task.apply(index);
        pending.add(
            threads.submit(() -> {
              startGate.await();
              return work.call();
            }));
      }
      // Every thread is waiting at the gate; open it so the requests leave together.
      startGate.countDown();
    }
    List<T> results = new ArrayList<>();
    for (Future<T> result : pending) {
      results.add(result.get());
    }
    return results;
  }
}
```

- `<T> List<T> run(int count, IntFunction<Callable<T>> task)`: `<T>` is a type parameter, "whatever the tasks return". `IntFunction<Callable<T>>` is a function that takes the task's index (an `int`) and returns the work (a `Callable<T>`, a function that returns a `T` and may throw).
- `new CountDownLatch(1)` is the **gate**: a counter that starts at 1. Threads that call `await()` on it wait until the count reaches 0.
- `Executors.newVirtualThreadPerTaskExecutor()` creates an executor that starts a new virtual thread for each task. It is opened in a `try ( ... )`, and an executor's `close()` waits for all submitted tasks to finish.
- The loop gives each task its index with `task.apply(index)` and submits a small wrapper: `startGate.await(); return work.call();`. Every wrapper first waits at the gate.
- After all tasks are submitted, `startGate.countDown()` brings the count to 0 and **every thread is released at once**. The comment on line 38: "Every thread is waiting at the gate; open it so the requests leave together."
- After the `try` block (so after all tasks ended) the results are collected in index order with `result.get()`. If any task threw, `get` throws an `ExecutionException` that wraps it, and the test fails with the cause. That is why the method is declared `throws Exception`.

### `Contract.java`

```java
package io.plainround.seatwave.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The OpenAPI document as a judge: it says whether a reply (or a request) is one the contract
 * allows. The document is read from the repository, the one copy clients read too.
 *
 * <p>Thread-safe: the validator is built once and only read.
 */
public final class Contract {

  /** The path of the contract, set by the build for the test task. */
  public static final Path DOCUMENT =
      Path.of(System.getProperty("seatwave.contract", "openapi/seatwave-api.yaml"));

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForInlineApiSpecification(text(DOCUMENT)).build();

  private Contract() {}
```

- The imports are the validator's classes (`openapi-validator-and-diff.md` explains each) and JDK file classes.
- `DOCUMENT` is the contract's path, from the system property `seatwave.contract` that `build.gradle.kts` sets, or `openapi/seatwave-api.yaml` when run another way.
- `VALIDATOR` is built once from the document's text: "inline" means the argument is the text, not an address. `static final` means it is created when the class is first used and shared by all tests. The Javadoc: "Thread-safe: the validator is built once and only read."

```java
  /**
   * Reads a contract file.
   *
   * @param path the file
   * @return its text
   */
  public static String text(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Judges a reply.
   *
   * @param method the request's method
   * @param path the request's path, without the query
   * @param status the reply's status
   * @param headers the reply's headers
   * @param body the reply's body
   * @return the validator's report; empty of errors when the contract allows the reply
   */
  public static ValidationReport judgeReply(
      String method, String path, int status, Map<String, String> headers, String body) {
    SimpleResponse.Builder response = SimpleResponse.Builder.status(status);
    headers.forEach(response::withHeader);
    if (!body.isEmpty()) {
      response.withBody(body);
    }
    return VALIDATOR.validateResponse(
        path, Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), response.build());
  }
```

- `text(Path)` reads a file as a string. `Files.readString` throws `IOException`, a **checked** exception that must be handled; the method wraps it in `UncheckedIOException`, which needs no declaration, so callers (including lambdas) stay simple.
- `judgeReply` builds a `SimpleResponse` from the status, headers and body and asks the validator. The method is turned into the library's enum by `Request.Method.valueOf(method.toUpperCase(Locale.ROOT))`; `Locale.ROOT` makes the upper-casing the same in every country.

```java
  /**
   * Judges a request.
   *
   * @param method the method
   * @param path the path, without the query
   * @param query the query parameters
   * @param headers the headers
   * @param body the body, or an empty string
   * @return the validator's report; empty of errors when the contract allows the request
   */
  public static ValidationReport judgeRequest(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      String body) {
    SimpleRequest.Builder request =
        new SimpleRequest.Builder(Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), path);
    query.forEach(request::withQueryParam);
    headers.forEach(request::withHeader);
    if (!body.isEmpty()) {
      request.withBody(body);
    }
    return VALIDATOR.validateRequest(request.build());
  }
}
```

`judgeRequest` does the same for a request: path and method first, then every query parameter, header and the body if there is one.

### `TestApi.java`

#### Header, constants and fields

```java
package io.plainround.seatwave.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.report.ValidationReport;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.payment.PaymentStandIn;
import io.plainround.seatwave.api.payment.StandInMode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One SeatWave API and one stand-in provider, both on 127.0.0.1 on ports the system picks, an
 * in-memory database of its own and a clock the test moves. Every reply that {@link #send} returns
 * has already been judged against the OpenAPI document: a reply the contract does not allow fails
 * the test that caused it.
 */
public final class TestApi implements AutoCloseable {

  /** The clock's start: a fixed instant, so every run computes the same expiries. */
  public static final Instant START = Instant.parse("2026-11-11T09:00:00Z");

  // A small seed: three events, sections A to C of 20 seats each, on event 1.
  private static final ApiSettings.Seed SMALL_SEED = new ApiSettings.Seed(3, 3, 20);
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration PROVIDER_SLOW_REPLY = Duration.ofSeconds(2);
  private static final int MAX_IN_FLIGHT = 200;
  // The provider budget of the tests: short, so a timeout costs half a second; the lease must
  // outlast connect plus request (ApiSettings.Payment checks it).
  private static final Duration PROVIDER_CONNECT_TIMEOUT = Duration.ofMillis(250);
  private static final Duration PROVIDER_REQUEST_TIMEOUT = Duration.ofMillis(500);
  private static final Duration CONFIRM_LEASE = Duration.ofSeconds(2);

  private final MutableClock clock;
  private final PaymentStandIn provider;
  private final SeatWaveApi api;
  private final HttpClient http;

  private TestApi(MutableClock clock, PaymentStandIn provider, SeatWaveApi api) {
    this.clock = clock;
    this.provider = provider;
    this.api = api;
    this.http = HttpClient.newBuilder().connectTimeout(CLIENT_TIMEOUT).build();
  }
```

- The Javadoc: one API and one stand-in provider, both on 127.0.0.1 on ports the system picks, an in-memory database and a clock the test moves. "Every reply that `send` returns has already been judged against the OpenAPI document: a reply the contract does not allow fails the test that caused it."
- `TestApi implements AutoCloseable`, so a test can open it in a `try ( ... )` and it closes by itself.
- `START` is the clock's first instant, a fixed time on 11 November 2026 at 09:00 UTC. A fixed start means every run computes the same expiry times.
- `SMALL_SEED = new ApiSettings.Seed(3, 3, 20)`: three events, three sections (A to C) and 20 seats in each, on event 1 (the comment on line 39).
- `CLIENT_TIMEOUT` (10 s) bounds every request of the test client. `PROVIDER_SLOW_REPLY` (2 s) is how late the stand-in answers in `CHARGE_THEN_TIMEOUT` mode. `MAX_IN_FLIGHT` (200) is the server's limit of requests at once.
- The provider budget (comment lines 44 and 45): `PROVIDER_CONNECT_TIMEOUT` 250 ms and `PROVIDER_REQUEST_TIMEOUT` 500 ms are short, "so a timeout costs half a second". `CONFIRM_LEASE` is 2 s. The settings class refuses a lease that does not outlast connect plus request (`ApiSettingsTest` tests that).
- The fields: the clock, the provider, the API and an `HttpClient` with a connect timeout. The constructor is private; tests use `start`.

#### `start`

```java
  /**
   * Starts an API on the small seed.
   *
   * @return the running API
   * @throws Exception if it cannot start
   */
  public static TestApi start() throws Exception {
    return start(SMALL_SEED, MAX_IN_FLIGHT);
  }

  /**
   * Starts an API.
   *
   * @param seed what to seed
   * @param maxInFlight the in-flight limit
   * @return the running API
   * @throws Exception if it cannot start
   */
  public static TestApi start(ApiSettings.Seed seed, int maxInFlight) throws Exception {
    MutableClock clock = new MutableClock(START);
    PaymentStandIn provider =
        PaymentStandIn.start(0, StandInMode.OK, PROVIDER_SLOW_REPLY, new JsonCodec());
    try {
      return new TestApi(
          clock, provider, SeatWaveApi.start(settings(seed, maxInFlight, provider.port()), clock));
    } catch (SQLException | IOException | RuntimeException startFailure) {
      provider.close();
      throw startFailure;
    }
  }
```

- `start()` uses the small seed and the default in-flight limit. `start(seed, maxInFlight)` is the full form.
- It creates the clock, then the provider: `PaymentStandIn.start(0, StandInMode.OK, PROVIDER_SLOW_REPLY, new JsonCodec())`. Port `0` asks the system for a free port. The mode is `OK`.
- The API is started with `settings(seed, maxInFlight, provider.port())`, so the API's payment client points at the provider's actual port, and with the clock.
- `catch (SQLException | IOException | RuntimeException startFailure)` catches three kinds of failure in one clause. If the API cannot start, the provider is closed before the failure is rethrown, so no port stays open.

#### `settings`

```java
  /**
   * Settings for a test API: port 0, a fresh in-memory database, the provider on the given port.
   *
   * @param seed what to seed
   * @param maxInFlight the in-flight limit
   * @param providerPort the provider's port
   * @return the settings
   */
  public static ApiSettings settings(ApiSettings.Seed seed, int maxInFlight, int providerPort) {
    return new ApiSettings(
        0,
        maxInFlight,
        "jdbc:h2:mem:test-" + UUID.randomUUID() + ";LOCK_TIMEOUT=5000",
        seed,
        new ApiSettings.Payment(
            URI.create("http://127.0.0.1:" + providerPort),
            PROVIDER_CONNECT_TIMEOUT,
            PROVIDER_REQUEST_TIMEOUT,
            CONFIRM_LEASE),
        new BigDecimal("80.00"),
        "USD",
        Duration.ofSeconds(1),
        new ApiSettings.Version0(
            Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2027-05-11T00:00:00Z")));
  }
```

It builds an `ApiSettings` by hand, in the same order as the fields of the record (`ApiSettingsTest` shows the order):
- `0`: the API's port, any free port.
- `maxInFlight`: the limit of requests at once.
- `"jdbc:h2:mem:test-" + UUID.randomUUID() + ";LOCK_TIMEOUT=5000"`: an H2 database that lives in memory, with a name no other test uses. `LOCK_TIMEOUT=5000` makes a transaction wait up to 5 seconds for a lock, which is what lets 100 racing retries queue behind the first one.
- the seed;
- the payment settings: the provider's address `http://127.0.0.1:<port>`, the two timeouts and the lease;
- `new BigDecimal("80.00")` and `"USD"`: the seat price. A `BigDecimal` is an exact decimal number; a `double` would not be exact for money.
- `Duration.ofSeconds(1)`: the `Retry-After` hint;
- the version 0 dates: deprecated from 11 November 2026, sunset on 11 May 2027.

The method is `public static` because `PaginationConsistencyTest`, `ApiSettingsTest` and `RetryStormTest` call it too.

#### The `Response` record

```java
  /**
   * One reply.
   *
   * @param status the status
   * @param headers the headers, names in lower case, first values
   * @param body the body
   */
  public record Response(int status, Map<String, String> headers, String body) {
    /**
     * Reads a header.
     *
     * @param name the name in any case
     * @return its value, or an empty string
     */
    public String header(String name) {
      return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }
  }
```

A record of three parts: the status, the headers (names in lower case, first values) and the body text. `header(name)` reads a header whatever case the caller uses, with an empty string when it is missing (`getOrDefault`).

#### `send`: the reply is judged

```java
  /**
   * Sends a request and checks the reply against the contract.
   *
   * @param method the method
   * @param pathAndQuery the path, with a query if any
   * @param headers request headers
   * @param body the body, or an empty string
   * @return the reply, already judged by the contract
   * @throws Exception if the exchange fails
   */
  public Response send(String method, String pathAndQuery, Map<String, String> headers, String body)
      throws Exception {
    Response response = sendUnchecked(method, pathAndQuery, headers, body);
    String path = URI.create(pathAndQuery).getPath();
    ValidationReport report =
        Contract.judgeReply(method, path, response.status(), response.headers(), response.body());
    assertThat(report.getMessages())
        .as(
            "the contract's judgment of %s %s -> %d %s",
            method, path, response.status(), response.body())
        .isEmpty();
    return response;
  }
```

`send` calls `sendUnchecked`, then `Contract.judgeReply` with the **path without the query** (`URI.create(pathAndQuery).getPath()`), and asserts that the validator's message list is empty. The description (`as(...)`, `assertj.md`) names the method, path, status and body. Only a reply the contract allows is returned.

#### `sendUnchecked`: the real exchange

```java
  /**
   * Sends a request without judging the reply, for paths the contract does not describe.
   *
   * @param method the method
   * @param pathAndQuery the path, with a query if any
   * @param headers request headers
   * @param body the body, or an empty string
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response sendUnchecked(
      String method, String pathAndQuery, Map<String, String> headers, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + pathAndQuery))
            .timeout(CLIENT_TIMEOUT)
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    headers.forEach(request::header);
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Map<String, String> replyHeaders = new LinkedHashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> replyHeaders.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
    return new Response(response.statusCode(), replyHeaders, response.body());
  }
```

- `HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + pathAndQuery))` starts a request to the loopback address and the API's real port. `.timeout(CLIENT_TIMEOUT)` limits the wait for the reply.
- `.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))` sets the verb and the body. An empty body means no body at all.
- `headers.forEach(request::header)` adds every header of the map.
- `http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))` sends and waits, and reads the body as text.
- The reply headers come as a map from name to a **list** of values. The code builds a `LinkedHashMap` with the names in lower case and only the first value: `values.getFirst()`. That is enough for this API, which does not repeat headers.

#### `hold`, `confirm` and the body builders

```java
  /**
   * Holds a seat of event 1.
   *
   * @param seat the seat
   * @param key the Idempotency-Key, without quotes
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response hold(String seat, String key) throws Exception {
    return send("POST", "/v1/holds", jsonWithKey(key), holdBody(seat, "fan_1"));
  }

  /**
   * Confirms a hold.
   *
   * @param holdId the hold
   * @param key the Idempotency-Key, without quotes
   * @return the reply
   * @throws Exception if the exchange fails
   */
  public Response confirm(String holdId, String key) throws Exception {
    return send("POST", "/v1/bookings", jsonWithKey(key), "{\"holdId\":\"" + holdId + "\"}");
  }

  /**
   * The body of a hold request for event 1.
   *
   * @param seat the seat
   * @param customer the customer
   * @return the JSON
   */
  public static String holdBody(String seat, String customer) {
    return "{\"eventId\":1,\"seat\":\"" + seat + "\",\"customer\":\"" + customer + "\"}";
  }

  /**
   * The headers of a JSON write with a key.
   *
   * @param key the key, without quotes
   * @return the headers
   */
  public static Map<String, String> jsonWithKey(String key) {
    return Map.of("Content-Type", "application/json", "Idempotency-Key", "\"" + key + "\"");
  }
```

- `hold(seat, key)` sends `POST /v1/holds` for event 1 with the customer `fan_1`. `confirm(holdId, key)` sends `POST /v1/bookings` with `{"holdId":"..."}`. Both go through `send`, so both are judged.
- `holdBody(seat, customer)` writes the JSON by joining strings. The backslash before a quote (`\"`) puts a quote inside a Java string.
- `jsonWithKey(key)` returns the headers of a JSON write: `Content-Type: application/json` and `Idempotency-Key` with the key **inside double quotes**, because the contract requires a quoted string.

#### `count` and `execute`

```java
  /**
   * Reads one number from the database: the counts that prove the invariants come from here, not
   * from the replies.
   *
   * @param sql a query that returns one number
   * @param parameters its parameters
   * @return the number
   * @throws SQLException if the query fails
   */
  public long count(String sql, Object... parameters) throws SQLException {
    try (Connection connection = api.database().connect();
        PreparedStatement query = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        query.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /**
   * Runs one statement against the database, for tests that set up a fault or a leftover state.
   *
   * @param sql the statement, a constant of the test, with ? for every value
   * @param parameters the values
   * @throws SQLException if it fails
   */
  public void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = api.database().connect();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.execute();
    }
  }
```

- `count(sql, parameters...)` runs a query that returns one number. The Javadoc says why it exists: "the counts that prove the invariants come from here, not from the replies." `Object... parameters` is a **varargs** parameter: zero or more values.
- `api.database().connect()` opens a connection to the same in-memory database the API uses. `prepareStatement(sql)` makes a **prepared statement**: the SQL has `?` slots, and values are set separately, so a value can never be read as SQL. `query.setObject(index + 1, parameters[index])` fills slot number `index + 1` (JDBC counts slots from 1).
- `rows.next()` moves to the first (only) row, and `rows.getLong(1)` reads the first column. Both `try ( ... )` blocks close the resources.
- `execute(sql, parameters...)` is the same without a result, "for tests that set up a fault or a leftover state". Its Javadoc: "the statement, a constant of the test, with ? for every value." `ConfirmPaymentTest` uses it to fake a dead process and `HoldRetryTest` to install the failing trigger.

#### Accessors and `close`

```java
  /**
   * The clock the API uses.
   *
   * @return the clock
   */
  public MutableClock clock() {
    return clock;
  }

  /**
   * The stand-in provider.
   *
   * @return the provider
   */
  public PaymentStandIn provider() {
    return provider;
  }

  /**
   * The API's port.
   *
   * @return the port
   */
  public int port() {
    return api.port();
  }

  @Override
  public void close() throws SQLException {
    http.close();
    try {
      api.close();
    } finally {
      provider.close();
    }
  }
}
```

Four getters (`clock`, `provider`, `port`) and `close()`. `close` shuts the client, then the API; the `finally` makes sure the provider is closed even if closing the API fails.

### `catalog/CursorsTest.java`

#### What a cursor is

A **cursor** is the text a client sends back to ask for the next page. This API's cursors name the last item by its place in the sort order (so rows added or removed before it cannot move it). A cursor is **base64url** text: the bytes of a short text such as `s1.1.A.available.A.500` written with letters, digits, `-` and `_` (the URL-safe variant of Base64, here without `=` padding). The class `Cursors` (in `src/main`, same package) makes and reads them.

#### Setup and the first two tests

```java
package io.plainround.seatwave.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CursorsTest {

  private static final SeatFilter AVAILABLE_IN_A =
      new SeatFilter("A", SeatFilter.StateFilter.AVAILABLE);
  private static final int SEAT_NUMBER = 500;
  private static final long EVENT_ID = 7;

  @Test
  void readsBackTheSeatPositionItWrote() {
    Cursors.SeatPosition position = new Cursors.SeatPosition(1, AVAILABLE_IN_A, "A", SEAT_NUMBER);

    assertThat(Cursors.decodeSeat(Cursors.encode(position))).contains(position);
  }

  @Test
  void readsBackTheEventPositionItWrote() {
    Cursors.EventPosition position =
        new Cursors.EventPosition(Instant.parse("2026-12-11T09:00:00Z"), EVENT_ID);

    assertThat(Cursors.decodeEvent(Cursors.encode(position))).contains(position);
  }
```

- The test class is in the same package as `Cursors`, which is package-private, so the test can call it.
- `AVAILABLE_IN_A` is a seat filter for section `A`, state `available`. `SEAT_NUMBER` is 500 and `EVENT_ID` is 7.
- Test 1: a `SeatPosition` (event 1, that filter, last seat in section `A`, number 500) is encoded and decoded again. `decodeSeat` returns an `Optional`, and `.contains(position)` says it holds an equal position.
- Test 2: the same round trip for an `EventPosition` (the start time and the event id).

#### Forged cursors

```java
  @Test
  void refusesACursorItDidNotMake() {
    String edited =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("s1.1.A.available.A.500;DROP".getBytes(StandardCharsets.UTF_8));

    assertThat(Cursors.decodeSeat(edited)).isEmpty();
    assertThat(Cursors.decodeSeat("%%%")).isEmpty();
    assertThat(
            Cursors.decodeEvent(
                Cursors.encode(new Cursors.SeatPosition(1, AVAILABLE_IN_A, "A", 1))))
        .isEmpty();
  }
}
```

Three cursors the service did not make, each of which must read as empty:
- `edited`: the text `s1.1.A.available.A.500;DROP` encoded as base64url. It is well-formed base64 but the text has junk after the number, and the reader accepts only an exact pattern. The `;DROP` is a joke about SQL injection; the text never reaches SQL.
- `"%%%"` is not base64url at all (the decoder throws, and the reader turns that into empty).
- A valid **seat** cursor handed to `decodeEvent`: the prefix `s1` is not `e1`, so an event reader refuses it.

### `catalog/PaginationConsistencyTest.java`

```java
package io.plainround.seatwave.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.load.PageWalk;
import org.junit.jupiter.api.Test;

/**
 * The pagination consistency test: while fans hold seats and the venue adds seats between pages,
 * the cursor walk returns every seat that stayed available exactly once; an offset walk does not.
 */
class PaginationConsistencyTest {

  private static final int SECTIONS = 5;
  private static final int SEATS_PER_SECTION = 200;
  private static final int ALL_SEATS = SECTIONS * SEATS_PER_SECTION;
  private static final int CHANGES_PER_PAGE = 10;
  private static final int MAX_IN_FLIGHT = 64;
  private static final int UNUSED_PROVIDER_PORT = 18_099;
  // A seat is about 50 bytes of JSON, for example {"seat":"A-17","section":"A","state":"held"}.
  private static final long MAX_BYTES_PER_SEAT = 60;
  // Five sections of 200 seats: 1,000 seats, 20 pages of 50.
  private static final ApiSettings.Seed SEED = new ApiSettings.Seed(1, SECTIONS, SEATS_PER_SECTION);
  private static final int PAGE_SIZE = 50;

```

- The Javadoc: while fans hold seats and the venue adds seats between pages, the cursor walk returns every seat that stayed available exactly once; an offset walk does not.
- The seed: 1 event, 5 sections, 200 seats per section: 1,000 seats, so 20 pages of 50 (comment on line 24). `CHANGES_PER_PAGE = 10` is how many holds or inserts happen between two pages.
- `MAX_BYTES_PER_SEAT = 60`: a seat is about 50 bytes of JSON, for example `{"seat":"A-17","section":"A","state":"held"}`.
- `UNUSED_PROVIDER_PORT = 18_099` is a port that no one listens on. The settings need one, but no confirm runs in a walk, so it is never called.

```java
  @Test
  void neitherSkipsNorRepeatsASeatWhileFansHoldSeats() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, CHANGES_PER_PAGE, 0));

    assertThat(report.cursor().skipped()).isZero();
    assertThat(report.cursor().repeated()).isZero();
    // The same changes make the offset walk skip seats: each hold behind it moves the rest back.
    assertThat(report.offset().skipped()).isPositive();
  }

  @Test
  void neitherSkipsNorRepeatsASeatWhileTheVenueAddsSeats() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, 0, CHANGES_PER_PAGE));

    assertThat(report.cursor().skipped()).isZero();
    assertThat(report.cursor().repeated()).isZero();
    // Each seat added behind an offset walk pushes one it has seen onto its next page.
    assertThat(report.offset().repeated()).isPositive();
  }
```

- `PageWalk` (in `src/main`, package `load`) walks every available seat of the demo event twice, once with the API's cursors and once with an offset (`LIMIT ... OFFSET ...` in `OffsetSeatReader`, a class kept only to measure the rejected design), on two identical databases with the same seeded schedule of changes. `PageWalk.Changes(pageSize, holdsPerPage, insertsPerPage)` says how much changes between pages.
- Test 1: ten seats are **held** after each page. The cursor walk skips and repeats nothing (`isZero()`). The offset walk **skips** seats (`isPositive()`): when a seat before the offset is held, it leaves the available list and everything behind it moves back one place, so the next page starts one seat too late.
- Test 2: ten seats are **added** after each page. The cursor walk is still perfect. The offset walk **repeats** seats: a seat added before the offset pushes one the walk has already seen onto the next page.

```java
  @Test
  void keepsEveryPageWithinTheLimit() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, 0, 0));

    assertThat(report.cursor().pages()).isEqualTo(ALL_SEATS / PAGE_SIZE);
    assertThat(report.cursor().returned()).isEqualTo(ALL_SEATS);
    assertThat(report.largestPageBytes()).isLessThan(PAGE_SIZE * MAX_BYTES_PER_SEAT);
  }

  private static PageWalk.Report walk(PageWalk.Changes changes) throws Exception {
    // No confirm runs in a walk, so the provider's port is never called.
    return new PageWalk(TestApi.settings(SEED, MAX_IN_FLIGHT, UNUSED_PROVIDER_PORT), changes).run();
  }
}
```

- Test 3: no changes. The cursor walk makes `ALL_SEATS / PAGE_SIZE` = 20 pages and returns all 1,000 seats. `largestPageBytes()` must stay under `PAGE_SIZE * MAX_BYTES_PER_SEAT` = 3,000 bytes, which keeps every page small.
- `walk(changes)` builds `new PageWalk(TestApi.settings(SEED, MAX_IN_FLIGHT, UNUSED_PROVIDER_PORT), changes).run()`. The comment says why the provider port is never called.

### `config/ApiSettingsTest.java`

#### Setup and the shipped file

```java
package io.plainround.seatwave.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.TestApi;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ApiSettingsTest {

  private static final int UNUSED_PROVIDER_PORT = 18_100;
  private static final int MORE_SECTIONS_THAN_A_TO_T = 21;

  @Test
  void readsTheShippedSettingsFile() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));

    Result<ApiSettings, List<String>> settings = ApiSettings.from(new SettingsReader(properties));

    assertThat(settings).isInstanceOf(Result.Success.class);
  }
```

- `Result<ApiSettings, List<String>>` is a value from the result4j library (`result4j.md`): it is either a `Result.Success` holding the settings or a `Result.Error` holding the list of problems. The settings reader never throws for a bad value; it collects the problems.
- `SettingsReader.load(Path)` reads a `.properties` file into a `Properties` object (`java-properties.md` explains the format). `new SettingsReader(properties)` wraps it in a typed reader. `ApiSettings.from(reader)` builds the settings or lists every problem.
- Test 1: the file the repository ships, `config/serve.properties`, must give a `Result.Success`. If someone breaks the shipped file, the build fails.
- `UNUSED_PROVIDER_PORT = 18_100` and `MORE_SECTIONS_THAN_A_TO_T = 21` are used below.

#### Every missing key at once

```java
  @Test
  void listsEveryMissingKeyInOneRun() {
    Result<ApiSettings, List<String>> settings =
        ApiSettings.from(new SettingsReader(new Properties()));

    assertThat(settings)
        .isInstanceOfSatisfying(
            Result.Error.class,
            error ->
                assertThat(error.toString())
                    .contains("http.port is missing")
                    .contains("payment.url is missing")
                    .contains("version0.sunsetAt is missing"));
  }
```

An empty `Properties` gives an `Error` whose text contains `http.port is missing`, `payment.url is missing` and `version0.sunsetAt is missing` (and more). One run lists **all** the problems, so a person fixes the file once instead of once per key. `isInstanceOfSatisfying` checks the type and then the inner condition (`assertj.md`). `error.toString()` is the text of the record, which includes the list.

#### Bad values

```java
  @Test
  void refusesAProviderOutsideTheLoopbackAddressAndBadValues() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("payment.url", "http://payments.example:443");
    properties.setProperty("booking.seatPrice", "80");
    properties.setProperty("version0.sunsetAt", "next spring");
    properties.setProperty("http.port", "80");

    Result<ApiSettings, List<String>> settings = ApiSettings.from(new SettingsReader(properties));

    assertThat(settings.toString())
        .contains("payment.url must be http://127.0.0.1:<port>")
        .contains("booking.seatPrice must be an amount such as 80.00")
        .contains("version0.sunsetAt must be an ISO-8601 UTC instant")
        .contains("http.port must be from 1024 to 65535");
  }
```

The shipped file is loaded and four values are spoiled with `setProperty`:
- `payment.url` is `http://payments.example:443`, not the loopback address;
- `booking.seatPrice` is `80`, not an amount with two decimals;
- `version0.sunsetAt` is `next spring`, not an ISO-8601 UTC instant;
- `http.port` is `80`, a privileged port.

The messages must say, respectively, `payment.url must be http://127.0.0.1:<port>`, `booking.seatPrice must be an amount such as 80.00`, `version0.sunsetAt must be an ISO-8601 UTC instant` and `http.port must be from 1024 to 65535`.

#### A lease that is too short

```java
  @Test
  void refusesALeaseThatDoesNotOutlastThePaymentCall() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("payment.leaseMillis", "1000");

    assertThat(ApiSettings.from(new SettingsReader(properties)).toString())
        .contains("payment.leaseMillis must be longer than the connect and request timeouts");
  }
```

`payment.leaseMillis` is set to 1000. The shipped connect and request timeouts add up to more than that. A lease shorter than the payment call would let a second attempt take over while the first is still paying, so the settings refuse it: `payment.leaseMillis must be longer than the connect and request timeouts`.

#### Impossible values cannot be built

```java
  @Test
  void cannotBeBuiltWithImpossibleValues() {
    ApiSettings valid = TestApi.settings(new ApiSettings.Seed(1, 1, 1), 1, UNUSED_PROVIDER_PORT);

    assertThatThrownBy(() -> new ApiSettings.Seed(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApiSettings.Seed(1, MORE_SECTIONS_THAN_A_TO_T, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ApiSettings(
                valid.httpPort(),
                0,
                valid.databaseUrl(),
                valid.seed(),
                valid.payment(),
                valid.seatPrice(),
                valid.currency(),
                valid.retryAfter(),
                valid.version0()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ApiSettings.Version0(valid.version0().sunsetAt(), valid.version0().deprecatedAt()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- `TestApi.settings(...)` builds a valid `ApiSettings`; its accessor methods (`valid.httpPort()`, `valid.databaseUrl()`, `valid.seed()`) feed the next constructor call.
- `new ApiSettings.Seed(0, 1, 1)` (zero events), `new ApiSettings.Seed(1, 21, 1)` (21 sections, but the letters A to T give 20) and `new ApiSettings(... 0 ...)` (an in-flight limit of zero) must throw `IllegalArgumentException`. The last call builds `Version0` with the two dates swapped (`sunsetAt`, then `deprecatedAt`): a sunset before the deprecation must be refused too. The records check themselves in their constructors, so an invalid object cannot exist.
- `assertThatThrownBy(() -> ...)` runs the constructor and catches what it throws (`assertj.md`).

### `http/ApiServerTest.java`

```java
package io.plainround.seatwave.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.payment.StandInMode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The boundary: unknown paths, wrong methods and overload, each as a problem body. */
class ApiServerTest {

  private static final int SEATS = 10;
  private static final int ONE_REQUEST_IN_FLIGHT = 1;
  private static final Duration CAPTURE_WITHIN = Duration.ofSeconds(5);

  @Test
  void answersAnUnknownPathAndAWrongMethodWithProblems() throws Exception {
    try (TestApi api = TestApi.start()) {
      TestApi.Response unknown = api.sendUnchecked("GET", "/v2/events", Map.of(), "");
      TestApi.Response wrongMethod = api.sendUnchecked("PUT", "/v1/holds", Map.of(), "");

      assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(unknown.header("Content-Type")).isEqualTo("application/problem+json");
      assertThat(wrongMethod.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
      assertThat(wrongMethod.header("Allow")).isEqualTo("POST");
    }
  }
```

- Test 1 opens the API in a `try ( ... )`. `sendUnchecked` is used because the paths are not in the contract: `GET /v2/events` (an unknown path) and `PUT /v1/holds` (a method the path does not allow).
- The unknown path must answer `404` with `Content-Type: application/problem+json`: even the server's own errors are problem bodies. The wrong method must answer `405 Method Not Allowed` with the header `Allow: POST`, which tells the client what the path does accept.

```java
  @Test
  void refusesAtOnceWithRetryAfterWhenTheInFlightLimitIsReached() throws Exception {
    try (TestApi api = TestApi.start(new ApiSettings.Seed(1, 1, SEATS), ONE_REQUEST_IN_FLIGHT)) {
      String holdId = api.hold("A-1", "slot").header("Location").substring("/v1/holds/".length());
      // A confirm that waits for a slow provider keeps the one slot busy.
      api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT);

      try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<TestApi.Response> slowConfirm =
            background.submit(() -> api.confirm(holdId, "slow-confirm"));
        // The provider has captured: the confirm is now waiting for its reply, holding the slot.
        await().atMost(CAPTURE_WITHIN).until(() -> api.provider().chargeCount() == 1);

        TestApi.Response refused = api.send("GET", "/v1/events", Map.of(), "");

        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.body()).contains("/problems/service-busy");
        assertThat(refused.header("Retry-After")).isEqualTo("1");
        assertThat(slowConfirm.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }
  }
}
```

- Test 2 starts an API whose limit is **one** request in flight. A seat is held, the provider is set to `CHARGE_THEN_TIMEOUT`, and a confirm is started in the background. The test waits (Awaitility) until the provider has captured the charge, which means the confirm is waiting for the provider and holding the only slot (`awaitility.md` walks through this test).
- While the slot is busy, `GET /v1/events` must be refused at once: `503`, the problem type `/problems/service-busy`, and `Retry-After: 1`. The server sheds load instead of queueing it. The background confirm then ends with `503` too, because the provider's reply is later than the timeout.
- `ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()` runs the confirm on a virtual thread; the `try` closes it. `Future<TestApi.Response> slowConfirm` is the promise of its result, and `slowConfirm.get()` waits for it.

### `http/Version0CompatibilityTest.java`

#### Setup

```java
package io.plainround.seatwave.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Module 1's clients keep working, unchanged, and are told every time that they must move. */
class Version0CompatibilityTest {

  private static final Map<String, String> FORM =
      Map.of("Content-Type", "application/x-www-form-urlencoded");

  private TestApi api;

  @BeforeEach
  void startApi() throws Exception {
    api = TestApi.start();
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }

```

**Version 0** means Module 1's paths (`/events`, `/events/{id}/seats`, `/holds`, `/bookings`) that old clients still use. Their answers are kept exactly as before, but every reply tells the client to move. `FORM` is the header map for the old form-encoded bodies (`application/x-www-form-urlencoded`: `eventId=1&seat=A-1&customer=fan_1`). The Javadoc: "Module 1's clients keep working, unchanged, and are told every time that they must move."

#### The event list and the deprecation headers

```java
  @Test
  void answersModuleOnesEventListWithEpochMillisecondsAndDeprecationHeaders() throws Exception {
    TestApi.Response events = api.send("GET", "/events", Map.of(), "");

    assertThat(events.status()).isEqualTo(HttpStatus.OK);
    assertThat(events.body()).startsWith("[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":");
    assertThat(events.header("Deprecation")).isEqualTo("@1794355200");
    assertThat(events.header("Sunset")).isEqualTo("Tue, 11 May 2027 00:00:00 GMT");
    assertThat(events.header("Link")).isEqualTo("</v1/events>; rel=\"successor-version\"");
  }
```

- The old event list answers `200` with a JSON array that starts with `[{"eventId":1,"name":"Arena Night","startsAt":` (in version 0, `startsAt` is epoch **milliseconds**, in version 1 a `date-time` text).
- `Deprecation: @1794355200` says since when the path is deprecated: `@` and the number of seconds since 1 January 1970, here 11 November 2026 00:00 UTC.
- `Sunset: Tue, 11 May 2027 00:00:00 GMT` says when it will stop working, in the HTTP date format.
- `Link: </v1/events>; rel="successor-version"` points at the replacement.

#### The seat list

```java
  @Test
  void answersModuleOnesSeatListForOneSection() throws Exception {
    TestApi.Response seats = api.send("GET", "/events/1/seats?section=A", Map.of(), "");
    TestApi.Response badSection = api.send("GET", "/events/1/seats?section=Z", Map.of(), "");
    TestApi.Response unknownEvent = api.send("GET", "/events/999/seats?section=A", Map.of(), "");

    assertThat(seats.body()).startsWith("[{\"seat\":\"A-1\",\"state\":\"available\"}");
    assertThat(badSection.body()).isEqualTo("{\"error\":\"invalid section\"}");
    assertThat(unknownEvent.body()).isEqualTo("[]");
  }
```

Module 1's answers, kept: a list for section `A` that starts `[{"seat":"A-1","state":"available"}`; a bad section gives the plain `{"error":"invalid section"}` (not a problem body); an unknown event gives an empty list `[]`.

#### Hold and confirm with forms

```java
  @Test
  void holdsAndConfirmsWithFormBodiesAndTakesThePaymentNow() throws Exception {
    TestApi.Response hold = api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");
    String holdId = hold.body().replaceAll(".*\"holdId\":\"([^\"]+)\".*", "$1");

    TestApi.Response booking = api.send("POST", "/bookings", FORM, "holdId=" + holdId);

    assertThat(hold.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(hold.body()).endsWith(",\"expiresInSeconds\":300}");
    assertThat(booking.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(booking.body()).startsWith("{\"bookingId\":\"");
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }
```

- A form-encoded hold gives `201` and a body ending `,"expiresInSeconds":300}`. The test reads the hold id from the body with `replaceAll(".*\"holdId\":\"([^\"]+)\".*", "$1")`.
- The form-encoded confirm gives `201` with a `bookingId`, and the **payment is taken now**: `api.provider().chargesFor(holdId)` is 1. In version 0, confirming charged immediately without a key.

#### The retry failure that version 0 had

```java
  @Test
  void stillAnswersARetryAfterALostReplyWithConflictForTheFansOwnHold() throws Exception {
    // Failure mode 1 as version 0 shows it: no key, so the retry is a new request and loses to the
    // fan's own first hold. The app then holds another seat: one fan, two holds.
    api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");

    TestApi.Response retry = api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=fan_1");

    assertThat(retry.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(retry.body()).isEqualTo("{\"error\":\"seat not available\"}");
  }
```

The comment says it: "Failure mode 1 as version 0 shows it: no key, so the retry is a new request and loses to the fan's own first hold." The same hold is sent twice without a key. The second gets `409` with `{"error":"seat not available"}`. This is the problem that keys were invented to solve, kept here as a compatibility fact.

#### Old error answers

```java
  @Test
  void keepsModuleOnesAnswersForBadInput() throws Exception {
    assertThat(api.send("POST", "/holds", FORM, "eventId=x&seat=A-1&customer=fan_1").body())
        .isEqualTo("{\"error\":\"invalid eventId\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=bad&customer=fan_1").body())
        .isEqualTo("{\"error\":\"invalid seat\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=A-1&customer=a%20b").body())
        .isEqualTo("{\"error\":\"invalid customer\"}");
    assertThat(api.send("POST", "/holds", FORM, "eventId=1&seat=A-0&customer=fan_1").status())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(api.send("POST", "/holds", FORM, "seat").body())
        .isEqualTo("{\"error\":\"malformed form field\"}");
    assertThat(
            api.send("POST", "/holds", FORM, "x=" + "a".repeat(ContractLimits.MAX_BODY_BYTES))
                .status())
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(api.send("POST", "/bookings", FORM, "holdId=nope").body())
        .isEqualTo("{\"error\":\"invalid holdId\"}");
    assertThat(
            api.send("POST", "/bookings", FORM, "holdId=00000000-0000-4000-8000-000000000000")
                .body())
        .isEqualTo("{\"error\":\"hold missing or expired\"}");
  }
}
```

Eight requests with exactly the old answers: `invalid eventId`, `invalid seat`, `invalid customer`, `409` for a seat that does not exist (`A-0`), `malformed form field` for a field with no `=`, `413` for a body over the limit, `invalid holdId`, and `hold missing or expired` for an unknown hold.

### `load/RetryStormTest.java`

```java
package io.plainround.seatwave.api.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class RetryStormTest {

  private static final int SECTIONS = 4;
  private static final int SEATS_PER_SECTION = 50;
  // In-flight limit 8 against 20 requests at once, so the 503-and-retry path runs too.
  private static final int MAX_IN_FLIGHT = 8;
  private static final int CONCURRENCY = 20;
  private static final int PAIRS = 30;

  @Test
  void findsNoDoubleEffectInTheDatabaseOrTheLedger() throws Exception {
    ApiSettings settings =
        TestApi.settings(
            new ApiSettings.Seed(1, SECTIONS, SEATS_PER_SECTION), MAX_IN_FLIGHT, freePort());

    RetryStorm.Report report = new RetryStorm(settings, CONCURRENCY, PAIRS).run();

    assertThat(report.sameKeyHoldIds()).isEqualTo(1);
    assertThat(report.sameKeySeatsHeld()).isEqualTo(1);
    assertThat(report.pairsBookings()).isEqualTo(PAIRS);
    assertThat(report.seatsBookedTwice()).isZero();
    assertThat(report.providerCharges()).isEqualTo(PAIRS);
    assertThat(report.holdsChargedTwice()).isZero();
    assertThat(report.sameSeatHolders()).isEqualTo(1);
    assertThat(report.sameSeatReplies())
        .containsEntry(HttpStatus.CREATED, 1)
        .containsEntry(HttpStatus.CONFLICT, CONCURRENCY - 1);
    // In-flight limit 8 against 20 at once: some requests were refused and retried with the key.
    assertThat(report.retries()).isPositive();
  }

  private static int freePort() throws Exception {
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return probe.getLocalPort();
    }
  }
}
```

- The seed: 1 event, 4 sections of 50 seats. The in-flight limit is **8** against **20** requests at once, so the 503-and-retry path runs too (comment on line 16). 30 pairs of hold-and-confirm.
- `RetryStorm` (in `src/main`) sends three scenarios over real HTTP, then reads the proof from the database and the provider's ledger: (1) one hold sent 20 times at once with one key; (2) 30 holds and their confirms, each sent twice at once, each with its own key; (3) one seat wanted by 20 fans with different keys. A client that gets `503` retries with the same key, with backoff.
- The assertions, in order: the 20 same-key replies name one hold id (`sameKeyHoldIds` is 1) and the database shows one held seat (`sameKeySeatsHeld` is 1); the pairs make 30 bookings (`pairsBookings`) and no seat has two (`seatsBookedTwice` is 0); the provider's ledger has 30 charges (`providerCharges`) and no hold was charged twice (`holdsChargedTwice` is 0); the fans race ends with one holder (`sameSeatHolders` is 1), replies `201` once and `409` 19 times (`containsEntry`); and `retries()` is positive, which proves some requests were refused with 503 and retried.
- `freePort()` opens a `ServerSocket` on port 0 of the loopback address (`new ServerSocket(0, 1, InetAddress.getLoopbackAddress())`), reads which port the system gave it, and closes it. `RetryStorm` starts its own stand-in provider on the port named in the settings, so the port must be free.

### `payment/PaymentStandInTest.java`

#### Setup

```java
package io.plainround.seatwave.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The stand-in never pretends: it charges once per key, and a failure is never a 2xx. */
class PaymentStandInTest {

  private static final Duration SLOW_REPLY = Duration.ofMillis(300);
  private static final Duration SHORTER_THAN_SLOW_REPLY = Duration.ofMillis(100);
  private static final int PROVIDER_TIMEOUT_MILLIS = 200;
  private static final BigDecimal PRICE = new BigDecimal("80.00");
  private static final String CHARGE =
      "{\"amount\":\"80.00\",\"currency\":\"USD\",\"reference\":\"hold-1\"}";

  private PaymentStandIn standIn;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws Exception {
    standIn = PaymentStandIn.start(0, StandInMode.OK, SLOW_REPLY, new JsonCodec());
  }

  @AfterEach
  void stop() {
    standIn.close();
    http.close();
  }

```

- The Javadoc: "The stand-in never pretends: it charges once per key, and a failure is never a 2xx."
- `CHARGE` is the JSON of one charge: amount `80.00`, currency `USD`, reference `hold-1`.
- `SLOW_REPLY` (300 ms) is the stand-in's delay in timeout mode, `SHORTER_THAN_SLOW_REPLY` (100 ms) is a client timeout shorter than that, and `PROVIDER_TIMEOUT_MILLIS` is 200.
- `@BeforeEach` starts a stand-in on a free port in mode `OK`; `@AfterEach` closes it and the HTTP client.

#### Test 1: one charge per key

```java
  @Test
  void chargesOncePerKeyAndReplaysTheFirstCharge() throws Exception {
    HttpResponse<String> first = charge("\"key-1\"", CHARGE);
    HttpResponse<String> second = charge("\"key-1\"", CHARGE);

    assertThat(first.statusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.statusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(standIn.chargesFor("hold-1")).isEqualTo(1);
  }
```

The same charge is sent twice with the key `"key-1"`. The first answer is `201 Created`. The second is `200 OK` with the **same body**: a replay of the first charge. The ledger shows one charge for the reference `hold-1`.

#### Test 2: a reused key and bad input

```java
  @Test
  void refusesAKeyReusedForAnotherChargeAndAChargeWithoutAKey() throws Exception {
    charge("\"key-1\"", CHARGE);

    assertThat(charge("\"key-1\"", CHARGE.replace("80.00", "1.00")).statusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(charge("no-quotes", CHARGE).statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(charge("\"key-2\"", "not json").statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
```

The key `"key-1"` with a different amount (`CHARGE.replace("80.00", "1.00")`) gets `422`. A key without quotes gets `400`. A body that is not JSON gets `400`. The stand-in follows the same rules the SeatWave API asks of any service.

#### Test 3: told to fail

```java
  @Test
  void capturesNothingAndNeverAnswersSuccessWhenToldToFail() throws Exception {
    control("POST", "/control/mode?mode=fail");

    assertThat(charge("\"key-1\"", CHARGE).statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(standIn.chargeCount()).isZero();
    assertThat(control("GET", "/control/ledger").body()).contains("\"charges\":0");
  }
```

`POST /control/mode?mode=fail` switches the mode. A charge then gets `503`, `chargeCount()` is zero, and `GET /control/ledger` reports `"charges":0`. A failure never captures money and never answers success.

#### Test 4: captures, then answers late

```java
  @Test
  void capturesAndThenAnswersLateWhenToldToTimeOut() throws Exception {
    standIn.setMode(StandInMode.CHARGE_THEN_TIMEOUT);
    PaymentClient client =
        new PaymentClient(
            new ApiSettings.Payment(
                URI.create("http://127.0.0.1:" + standIn.port()),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                SHORTER_THAN_SLOW_REPLY,
                Duration.ofSeconds(1)),
            new JsonCodec());

    PaymentOutcome outcome = client.charge("hold-9", PRICE, "USD");

    assertThat(outcome).isEqualTo(new PaymentOutcome.Unavailable(PaymentOutcome.Reason.TIMEOUT));
    assertThat(standIn.chargesFor("hold-9")).isEqualTo(1);
  }
```

- `standIn.setMode(StandInMode.CHARGE_THEN_TIMEOUT)` switches to the case that idempotency exists for.
- A real `PaymentClient` (the SeatWave API's client) is built with a request timeout of 100 ms, shorter than the stand-in's 300 ms reply. `client.charge("hold-9", PRICE, "USD")` returns `PaymentOutcome.Unavailable` with the reason `TIMEOUT`, because the reply came too late.
- The ledger still shows **one** charge for `hold-9`: the money moved though the client never heard so. `PaymentOutcome` is a sealed family of records (`Captured`, `Unavailable`), compared by `isEqualTo`.

#### Test 5: no provider at all

```java
  @Test
  void reportsAProviderThatIsNotThereAsUnreachable() throws Exception {
    int port = standIn.port();
    standIn.close();
    PaymentClient client =
        new PaymentClient(
            new ApiSettings.Payment(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                Duration.ofMillis(PROVIDER_TIMEOUT_MILLIS),
                Duration.ofSeconds(1)),
            new JsonCodec());

    assertThat(client.charge("hold-1", PRICE, "USD"))
        .isInstanceOf(PaymentOutcome.Unavailable.class);
    standIn = PaymentStandIn.start(0, StandInMode.OK, SLOW_REPLY, new JsonCodec());
  }
```

The test remembers the stand-in's port, closes the stand-in, and builds a client for that port. Nothing listens, so the connection is refused, and the client must answer `Unavailable` (any reason), not throw. At the end a fresh stand-in is started so that `@AfterEach` has something to close.

#### Test 6: unknown control requests

```java
  @Test
  void answersUnknownControlRequestsWithNotFound() throws Exception {
    assertThat(control("POST", "/control/mode?mode=maybe").statusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(control("POST", "/control/mode?mode=ok").body()).isEqualTo("{\"mode\":\"ok\"}");
  }
```

An unknown mode (`mode=maybe`) gets `404`. A known one (`mode=ok`) answers `{"mode":"ok"}`.

#### The two helpers

```java
  private HttpResponse<String> charge(String key, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + standIn.port() + "/v1/charges"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> control(String method, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + standIn.port() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
```

`charge(key, body)` sends `POST /v1/charges` with the JSON content type, the key header and the body. `control(method, path)` sends a control request with no body. Both use the JDK's `HttpClient`, built once per test object, and read the answer as a string.

### `store/DatabaseTest.java`

#### The migrations run once

```java
package io.plainround.seatwave.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.config.ApiSettings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

  private static final int EVENTS = 2;
  private static final int SECTIONS = 2;
  private static final int SEATS_PER_SECTION = 3;
  private static final int MIGRATIONS = 3;
  private static final int LAST_SEAT_OF_A_SECTION = 1_000;

  @Test
  void appliesEachMigrationOnceAndSeedsOnce(@TempDir Path folder) throws Exception {
    String url = "jdbc:h2:file:" + folder.resolve("seatwave").toAbsolutePath();
    ApiSettings.Seed seed = new ApiSettings.Seed(EVENTS, SECTIONS, SEATS_PER_SECTION);
    Clock clock = Clock.fixed(TestApi.START, ZoneOffset.UTC);
    try (Database first = Database.open(url)) {
      first.seedIfEmpty(seed, clock);
    }

    try (Database again = Database.open(url)) {
      again.seedIfEmpty(seed, clock);

      assertThat(number(again, "SELECT COUNT(*) FROM schema_history")).isEqualTo(MIGRATIONS);
      assertThat(number(again, "SELECT COUNT(*) FROM events")).isEqualTo(EVENTS);
      assertThat(number(again, "SELECT COUNT(*) FROM seats"))
          .isEqualTo(SECTIONS * SEATS_PER_SECTION);
    }
  }
```

- `@TempDir Path folder` gives the test an empty folder (`junit.md`). The database is a **file** there: `"jdbc:h2:file:" + folder.resolve("seatwave").toAbsolutePath()`.
- The seed is 2 events, 2 sections and 3 seats per section. `Clock.fixed(TestApi.START, ZoneOffset.UTC)` is a clock that never moves.
- The first `Database.open(url)` applies the three migrations (the versioned SQL files under `src/main/resources/db/migration`, `h2-sql-and-migrations.md`) and `seedIfEmpty` fills the tables. The block ends and the database closes.
- The second `Database.open(url)` opens the **same file**. It must apply nothing again, and `seedIfEmpty` must add nothing. The test counts: `schema_history` has `MIGRATIONS` = 3 rows (one per migration), `events` has 2 rows and `seats` has `SECTIONS * SEATS_PER_SECTION` = 6 rows. Running everything twice gives the same data as running it once.

#### The third migration fills the seat number from the label

```java

  @Test
  void fillsTheSeatNumberFromTheLabelForSeatsThatExistedBeforeIt() throws Exception {
    // V3 must migrate Module 1's rows, which had no seat_number: insert one as V2 left it.
    try (Database database = Database.open("jdbc:h2:mem:migration-" + System.nanoTime())) {
      try (Connection connection = database.connect();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM schema_history WHERE version = 3");
        statement.execute("DROP INDEX seats_in_map_order");
        statement.execute("DROP INDEX events_in_start_order");
        statement.execute("ALTER TABLE seats DROP COLUMN seat_number");
        statement.execute(
            "INSERT INTO seats (event_id, seat_label, section, status) VALUES (1, 'T-1000', 'T', 'AVAILABLE')");
        statement.execute(
            "RUNSCRIPT FROM 'classpath:/db/migration/V3__seat_order_for_cursors.sql'");
      }

      assertThat(number(database, "SELECT seat_number FROM seats WHERE seat_label = 'T-1000'"))
          .isEqualTo(LAST_SEAT_OF_A_SECTION);
    }
  }
```

Module 1's database had seats without a `seat_number`. Migration `V3__seat_order_for_cursors.sql` adds the column and fills it from the label (`T-1000` gives 1000). To test that, the test **rewinds** an in-memory database to the state before V3:
- `DELETE FROM schema_history WHERE version = 3` forgets that V3 ran.
- `DROP INDEX seats_in_map_order` and `DROP INDEX events_in_start_order` remove the two indexes V3 made, and `ALTER TABLE seats DROP COLUMN seat_number` removes the column.
- An `INSERT` adds a seat `T-1000` in the old shape, with no number.
- `RUNSCRIPT FROM 'classpath:/db/migration/V3__seat_order_for_cursors.sql'` runs the real migration script again (H2's command to run a script file; `classpath:` means "from the program's resources").
- The check: the number of seat `T-1000` is now `LAST_SEAT_OF_A_SECTION` = 1,000. The old row was migrated correctly.

#### The database refuses an unknown status

```java
  @Test
  void refusesAnUnknownSeatStatus() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:status-" + System.nanoTime());
        Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() ->
              statement.execute(
                  "INSERT INTO seats (event_id, seat_label, section, seat_number, status)"
                      + " VALUES (1, 'A-1', 'A', 1, 'SOLD_TWICE')"))
          .isInstanceOf(SQLException.class);
    }
  }

  private static long number(Database database, String sql) throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
```

- An `INSERT` with the status `SOLD_TWICE` must throw `SQLException`. The table has a rule (`known_seat_status`, a `CHECK` that allows only `AVAILABLE`, `HELD`, `CONFIRMING` and `CONFIRMED`). The database itself guards the data, whatever the application code does.
- `number(database, sql)` is a helper that opens a connection, runs a query and returns the first column of the first row as a `long`. Three resources are opened in one `try ( ... )`: a connection, a statement and a result set; all are closed in reverse order.

### `src/test/resources/logback-test.xml`

```xml
<configuration>
  <!-- Tests print only warnings and errors, so the build output stays readable. -->
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
  <!-- The OpenAPI parser reports a broken reference both as a message (which the lint prints) and
       as an ERROR log with a stack trace; the message is enough. The schema validator warns about
       OpenAPI keywords it does not know, which are not JSON Schema keywords. -->
  <logger name="io.swagger.v3.parser" level="OFF"/>
  <logger name="com.networknt.schema" level="ERROR"/>
  <root level="WARN">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

**Logback** is the logging engine (`slf4j-and-logback.md`). When the test JVM starts, Logback looks for a file named `logback-test.xml` on the classpath, and uses it before any other. Everything in `src/test/resources` is on the test classpath.

- `<configuration>` is the root element of the settings.
- The first comment (line 2): "Tests print only warnings and errors, so the build output stays readable."
- `<appender name="console" class="ch.qos.logback.core.ConsoleAppender">` creates an **appender**, a destination for log lines: here the console.
- `<encoder>` with `<pattern>` is the line layout. `%d{HH:mm:ss.SSS}` is the time with milliseconds. `%-5level` is the level (`WARN`, `ERROR`) padded to five characters. `[%X{correlationId:-none}]` prints the `correlationId` from the logging context (a per-request id), or `none` when there is none. `%logger{0}` is the logger's name shortened to the class name. `%msg` is the message and `%n` the end of the line.
- The second comment (lines 8 to 10) explains two special loggers. `<logger name="io.swagger.v3.parser" level="OFF"/>`: the OpenAPI parser reports a broken reference both as a message (which the lint prints) and as an `ERROR` log with a stack trace; the message is enough, so its log is switched off. `<logger name="com.networknt.schema" level="ERROR"/>`: the schema validator warns about OpenAPI keywords it does not know, which are not JSON Schema keywords, so only errors are shown.
- `<root level="WARN">` with `<appender-ref ref="console"/>` sets the default for every other logger: print warnings and errors to the console appender.

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the tests of these packages and count them:

```
./gradlew --offline cleanTest test --tests '*catalog*' --tests '*config*' --tests '*http*' --tests '*load*' --tests '*payment*' --tests '*store*' | grep -c PASSED
```

   Output: `28`: `CursorsTest` 3, `PaginationConsistencyTest` 3, `ApiSettingsTest` 5, `ApiServerTest` 2, `Version0CompatibilityTest` 5, `RetryStormTest` 1, `PaymentStandInTest` 6 and `DatabaseTest` 3.

2. Run the retry storm test alone:

```
./gradlew --offline cleanTest test --tests '*RetryStormTest'
```

   Output: `RetryStormTest > findsNoDoubleEffectInTheDatabaseOrTheLedger() PASSED`.

3. Look at the test log setup:

```
cat -n src/test/resources/logback-test.xml
```

   Output: the 16 lines quoted above.

4. Count the helpers' users:

```
grep -rl "TestApi" src/test/java | wc -l
```

   Output: `11` (the 10 test classes that call `TestApi`, plus `TestApi.java` itself, whose file name contains the word).

## Mistakes people make

- **A test that shares a database with another.** `TestApi` gives each test an in-memory database with a random name.
- **Sleeping instead of moving the clock.** `MutableClock.advance` makes 24 hours pass in a microsecond.
- **Judging only the body.** `send` judges status, headers and body together.
- **Hard-coding a port.** Port 0 makes the system choose, so two builds on one machine never collide. The two `UNUSED_PROVIDER_PORT` constants name ports that nothing listens on and no test calls.
- **Building SQL with string joins.** `count` and `execute` take `?` slots and values apart.
- **Forgetting to close servers.** Every test closes its API and provider.
- **A logging file in `src/main`.** The test configuration lives in `src/test/resources`, so it never reaches the service.

## Check yourself

1. *Why does `TestApi.send` call `Contract.judgeReply` on every reply?* So a reply the contract does not allow fails the test that caused it, whether or not that test remembered to check.
2. *What does `MutableClock.advance` change?* The instant the clock returns; the service reads that clock, so expiry can be tested without waiting.
3. *Why does `AllAtOnce` use a latch?* All tasks are submitted first, and the latch releases them together so the requests really overlap.
4. *Why does the offset walk skip seats while fans hold seats?* A held seat leaves the available list, so every seat behind it moves one place back, and the next offset starts too late.
5. *What does the third migration test prove?* That rows from Module 1, which had no seat number, get the right number from their label.

## Further reading

- Logback 1.6.6 configuration: <https://logback.qos.ch/manual/configuration.html>
- Logback layouts (the pattern letters): <https://logback.qos.ch/manual/layouts.html>
- Java `java.time.Clock`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html>
- Java `java.net.http.HttpClient`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html>
- H2 `RUNSCRIPT` and `CHECK` constraints: <https://h2database.com/html/commands.html>
- Keyset pagination, as described by the PostgreSQL project: <https://www.postgresql.org/docs/current/queries-limit.html>
- HTTP Semantics, RFC 9110: <https://www.rfc-editor.org/rfc/rfc9110>
- The Deprecation HTTP header, RFC 9745: <https://www.rfc-editor.org/rfc/rfc9745>
- The Sunset HTTP header, RFC 8594: <https://www.rfc-editor.org/rfc/rfc8594>
