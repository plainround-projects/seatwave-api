# The `payment` and `store` packages, line by line (the stand-in provider and the database)

## What it is

Two packages that sit at the edges of the program.

- **`payment`** talks to a payment provider: a company's server that takes money for a purchase. SeatWave never talks to a real one. It talks to a **stand-in**: a small server written for the lab that behaves like a provider, moves no money and listens only on the loopback address `127.0.0.1` (the machine's own address, unreachable from the network). The package holds the **client** that SeatWave uses to call the provider and the **stand-in server** itself, plus a command-line starter.
- **`store`** opens the database. It holds `Database` (open the embedded H2 database, apply the migrations, hand out connections) and `DemoSeed` (fill an empty database with demo events and seats).

A **database connection** is one open conversation with the database. Every query and every transaction goes through one. Opening a connection costs something, which is why many programs keep a pool of them. This program does not, and the reasons are in the walkthrough.

## Why this repository uses it

The confirm step of the lesson has to take a payment, and a payment is the classic place where a retry can hurt: if the charge succeeds but the reply is lost, a careless retry charges twice. The stand-in can be told to time out after charging, so the lab can show the exact failure. The provider key made from the hold lets SeatWave call the provider as often as it likes and still charge once. The `store` package gives the booking code a database with the same tables on every machine, with nothing to install.

## Where it appears

| File | What it does |
|---|---|
| `payment/ChargeRequest.java` | The body of a charge: amount, currency, reference. |
| `payment/ChargeReply.java` | The provider's reply: the payment id and the status `captured`. |
| `payment/PaymentOutcome.java` | What one charge call learned: captured, or unavailable with a reason. |
| `payment/PaymentClient.java` | SeatWave's client of the provider: one JDK `HttpClient`, timeouts, the provider key. |
| `payment/StandInMode.java` | The three behaviors of the stand-in: `OK`, `CHARGE_THEN_TIMEOUT`, `FAIL`. |
| `payment/PaymentStandIn.java` | The stand-in provider server. |
| `payment/PaymentStandInCli.java` | The command that runs it (`./gradlew -q paymentStandIn`). |
| `store/Database.java` | Opens H2, runs the migrations, hands out connections. |
| `store/DemoSeed.java` | Writes the demo events and 20,000 seats. Package-private. |

Settings files: `config/payment-stand-in.properties` for the stand-in, and the `payment.*` keys of `config/serve.properties` for the client (see `java-properties.md`).

## How it works underneath

### The conversation with the provider

```text
SeatWave (ConfirmService)                    Stand-in provider
   |  POST /v1/charges                          |
   |  Idempotency-Key: "seatwave-hold-<holdId>" |
   |  {"amount":"80.00","currency":"USD",       |
   |   "reference":"<holdId>"}  ------------->  |  first time for this key: capture, remember
   |                                            |  answer 201 {"paymentId":"pay_...","status":"captured"}
   |  <-------------------------------------    |
   |  the same call again ------------------>   |  key known, same charge: answer 200 with the same paymentId
```

It is **HTTP**, the web's request and reply protocol, over TCP on the loopback address. The client sends a request and waits. Three things can go wrong, and each one is a different `PaymentOutcome.Reason`:

| What happens | Reason | Meaning for the money |
|---|---|---|
| No connection can be made | `UNREACHABLE` | Nothing was sent: no charge. |
| No complete reply in time | `TIMEOUT` | Unknown: the provider may have charged. |
| A reply that is not a capture | `PROVIDER_ERROR` | Unknown for a garbled reply; none for a clean 503. |
| The waiting thread was interrupted | `INTERRUPTED` | Unknown. |

SeatWave cannot tell the unknown cases apart from "it failed". So it never decides "no charge". It ends the lease, answers the client 503, and waits for the client's retry, which asks the provider again with the same provider key. The provider answers 200 with the first charge, and no second charge is made.

### The provider key

The provider's key is `seatwave-hold-` followed by the hold id. It is made from the hold, not from the client's key. That means every attempt to pay for one hold, from any client key, any retry, any process, and even after SeatWave forgot the client's key (24 hours), names the same charge. A client that sends two different keys for the same hold is also safe. The key is written in the same quoted form as SeatWave's own header. It is 14 characters plus 36 for the UUID: 50, under the limit of 64.

### The three behaviors of the stand-in

- `OK`: capture the charge and answer 201 at once.
- `CHARGE_THEN_TIMEOUT`: capture the charge and put it in the ledger, then wait `standIn.slowReplyMillis` (3,000 ms in `config/payment-stand-in.properties`) before answering. SeatWave's own limit is `payment.requestTimeoutMillis` (1,500), so it gives up first. The money has moved but SeatWave never hears so. This is the case idempotency exists for.
- `FAIL`: capture nothing and answer 503. It never answers 2xx for a failure, so a test cannot pass by accident.

Mode changes while it runs: `POST /control/mode?mode=fail` and back. `GET /control/ledger` shows the count of charges and a count per reference. A test or a person reads these to see the truth: one hold, at most one charge.

### How the database is kept alive and opened

H2 is a database that runs inside the Java program (embedded). An **in-memory** H2 database, `jdbc:h2:mem:name`, lives only as long as at least one connection to it is open. I checked that: after the last connection closed, the table was gone. A **file** database, `jdbc:h2:file:path`, keeps its data in a file and survives restarts, but only one process may open it at a time. I started two Java processes on one file and the second failed with `Database may be already in use`.

`Database` therefore keeps one connection, the **anchor**, open for its whole life. It exists only to keep an in-memory database alive and to run the migrations. Every unit of work then gets its own short-lived connection from `connect()`.

Opening a connection to an embedded database that is already open is cheap. I measured 10,000 open-and-close pairs on H2 2.5.252 on this machine: 131 ms in total, about 13 microseconds each (a rough figure that includes the program's warm-up). That is why there is no pool. The number of connections in use at once is bounded by the HTTP layer's limit on requests in flight (`http.maxInFlight=64`), and by the one purge thread. A request that needs a second connection while it holds the first (the confirm's lost-race path does) uses at most two.

A connection that is closed with an open transaction does not commit it. I checked: I inserted a row with auto-commit off, closed the connection, and read zero rows from another connection. Every unit of work is inside a `try (Connection ...)`, so a failure that skips `commit()` also loses the changes.

## Walkthrough of this repository's use

### `ChargeRequest.java` and `ChargeReply.java`

```java
package io.plainround.seatwave.api.payment;

import java.util.Objects;

/**
 * The body of a charge, as the stand-in provider's contract defines it.
 *
 * @param amount the amount, a decimal with two digits after the point
 * @param currency the ISO 4217 code
 * @param reference SeatWave's reference for the charge: the hold id
 */
public record ChargeRequest(String amount, String currency, String reference) {

  /** Checks the fields. */
  public ChargeRequest {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(reference, "reference");
  }
}
```

- `ChargeRequest` is the body SeatWave sends: `amount` as text with two decimals (`"80.00"`), `currency` as a three-letter code, and `reference`, SeatWave's own label for the charge, which is the hold id. The provider keeps the reference in its ledger so a test can ask "how many charges carry this hold?". The three null checks are the only rules.

```java
package io.plainround.seatwave.api.payment;

import java.util.Objects;

/**
 * The stand-in provider's reply to a charge.
 *
 * @param paymentId the provider's identifier of the charge
 * @param status always {@code captured}: the provider never answers 2xx for anything else
 */
public record ChargeReply(String paymentId, String status) {

  /** Checks the fields. */
  public ChargeReply {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(status, "status");
  }
}
```

- `ChargeReply` is the provider's answer: its own `paymentId` for the charge (`pay_` plus a UUID) and a `status`. The Javadoc says the status is "always `captured`": the provider never answers 2xx for anything else.

### `PaymentOutcome.java`

```java
package io.plainround.seatwave.api.payment;

import java.util.Objects;

/** What one charge call learned. Anything but a confirmed capture is "unavailable". */
public sealed interface PaymentOutcome {

  /**
   * The provider confirmed the charge.
   *
   * @param paymentId the provider's identifier of the charge
   */
  record Captured(String paymentId) implements PaymentOutcome {

    /** Checks the field. */
    public Captured {
      Objects.requireNonNull(paymentId, "paymentId");
    }
  }

```

- A sealed interface with two cases. `Captured(String paymentId)` means the provider confirmed. The id is stored in the booking.

```java
  /**
   * The provider did not confirm a charge. The money may or may not have moved: a timeout after the
   * provider captured looks exactly like a timeout before, which is why the caller retries with the
   * same provider key instead of deciding "no charge".
   *
   * @param reason what happened
   */
  record Unavailable(Reason reason) implements PaymentOutcome {

    /** Checks the field. */
    public Unavailable {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Why a charge was not confirmed. */
  enum Reason {
    /** No complete reply within the request timeout. */
    TIMEOUT,
    /** No connection could be made. */
    UNREACHABLE,
    /** The provider answered with something other than a capture. */
    PROVIDER_ERROR,
    /** The waiting thread was interrupted, which happens when the server shuts down. */
    INTERRUPTED
  }
}
```

- `Unavailable(Reason reason)` means anything else. The Javadoc is the key warning: "The money may or may not have moved: a timeout after the provider captured looks exactly like a timeout before, which is why the caller retries with the same provider key instead of deciding no charge".
- The nested `enum Reason` has the four reasons in the table above. It is declared without `public`; inside an interface a nested type is automatically public.

### `StandInMode.java`

```java
package io.plainround.seatwave.api.payment;

/** How the stand-in payment provider behaves. It never pretends: a failure is never a 2xx. */
public enum StandInMode {
  /** Capture the charge and answer 201 at once. */
  OK,
  /**
   * Capture the charge, then wait longer than SeatWave's timeout before answering: the money moved
   * but SeatWave never hears so. This is the case idempotency exists for.
   */
  CHARGE_THEN_TIMEOUT,
  /** Capture nothing and answer 503. */
  FAIL
}
```

- The three behaviors. The comment on the enum states the rule of the stand-in: it never pretends, and a failure is never a 2xx.

### `PaymentClient.java`

```java
package io.plainround.seatwave.api.payment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

```

- Imports. `java.net.http` is the JDK's built-in web client (`HttpClient`, `HttpRequest`, `HttpResponse`). The three exception classes are the ways a call fails. `URI` is a typed address. `JsonCodec` and `Result` come from this repository and result4j.

```java
/**
 * Calls the payment provider. One instance, and one JDK {@link HttpClient}, per process: the client
 * keeps connections alive across calls (Module 5), so it is created once and shared.
 *
 * <p>Every charge carries the provider's own Idempotency-Key, made from the hold. SeatWave may call
 * the provider any number of times for one hold (a client retry, a takeover after a crash, a retry
 * after SeatWave forgot the client's key); the provider charges once.
 *
 * <p>Thread-safe: {@link HttpClient} is, and this class holds nothing else that changes.
 */
public final class PaymentClient {

  private static final int CREATED = 201;
  private static final int REPLAYED = 200;

  private final URI chargesUri;
  private final ApiSettings.Payment settings;
  private final JsonCodec json;
  private final HttpClient httpClient;

```

- The class comment gives two rules. One `HttpClient` per process, shared: the client keeps connections alive across calls, so it is created once. And every charge carries the provider's own `Idempotency-Key`, made from the hold. SeatWave may call the provider any number of times for one hold (a client retry, a takeover after a crash, a retry after SeatWave forgot the client's key). The provider charges once.
- "Thread-safe: `HttpClient` is": many request threads can call `charge` together.
- `CREATED = 201` is the status for a new charge, `REPLAYED = 200` for a known one. The fields are all `final`.

```java
  /**
   * Creates the client.
   *
   * @param settings where the provider is and how long to wait
   * @param json the JSON codec
   */
  public PaymentClient(ApiSettings.Payment settings, JsonCodec json) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.json = Objects.requireNonNull(json, "json");
    this.chargesUri = settings.baseUrl().resolve("/v1/charges");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(settings.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }
```

- `settings.baseUrl().resolve("/v1/charges")` builds the address once. `URI.resolve` with a path that starts with `/` replaces the path of the base, so `http://127.0.0.1:8088` becomes `http://127.0.0.1:8088/v1/charges`.
- `HttpClient.newBuilder()` starts a builder. `.connectTimeout(...)` is how long to wait for the TCP connection (250 ms in `config/serve.properties`). `.version(HttpClient.Version.HTTP_1_1)` forces the plain HTTP/1.1 protocol. The client's default is HTTP/2, which for a plain `http://` address starts with an upgrade request; this code asks for HTTP/1.1 directly, so every call is the simple form. `.build()` makes the client.

```java
  /**
   * The provider key for a hold: the same hold always gives the same key.
   *
   * @param holdId the hold
   * @return the key SeatWave sends to the provider
   */
  public static String providerKeyFor(String holdId) {
    return "seatwave-hold-" + Objects.requireNonNull(holdId, "holdId");
  }
```

- `providerKeyFor` is a **public static** method so tests and tools can compute it. The same hold id always gives the same key: `"seatwave-hold-" + holdId`. It also refuses null.

```java
  /**
   * Charges for one hold, at most once whatever the number of calls.
   *
   * @param holdId the hold, which names the charge
   * @param amount the amount
   * @param currency the currency
   * @return captured, or unavailable with the reason
   */
  public PaymentOutcome charge(String holdId, BigDecimal amount, String currency) {
    String body = json.write(new ChargeRequest(amount.toPlainString(), currency, holdId));
    HttpRequest request =
        HttpRequest.newBuilder(chargesUri)
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            // The provider follows the same draft as SeatWave: the key is an RFC 9651 String.
            .header("Idempotency-Key", "\"" + providerKeyFor(holdId) + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
```

- `json.write(new ChargeRequest(amount.toPlainString(), currency, holdId))` writes the body as JSON. `BigDecimal.toPlainString()` writes `80.00` and never an exponent form such as `8.0E+1`.
- `HttpRequest.newBuilder(chargesUri)` starts a request. `.timeout(settings.requestTimeout())` sets how long to wait for the reply (1,500 ms). `.header("Content-Type", "application/json")` says what the body is.
- `.header("Idempotency-Key", "\"" + providerKeyFor(holdId) + "\"")` adds the key inside double quotes. The `\"` is a quote character inside a Java string. The comment: the provider follows the same draft as SeatWave, so the key is an RFC 9651 String.
- `.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))` sets the method and the body. `.build()` finishes the request.

```java
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != CREATED && response.statusCode() != REPLAYED) {
        return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
      }
      return switch (json.read(response.body(), ChargeReply.class)) {
        case Result.Success<ChargeReply, JsonCodec.BindingProblem>(ChargeReply reply) ->
            "captured".equals(reply.status())
                ? new PaymentOutcome.Captured(reply.paymentId())
                : new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
        case Result.Error<ChargeReply, JsonCodec.BindingProblem>(
                JsonCodec.BindingProblem problem) ->
            new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
      };
```

- `httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))` sends the request and **blocks** (the calling thread waits) until the reply arrives or the timeout fires. The body handler says to read the reply body as text. The server's request threads are virtual threads, so waiting is cheap.
- A status other than 201 or 200 (a 503 from the stand-in in `FAIL` mode, a 422 for a reused key) is `PROVIDER_ERROR`. SeatWave does not try to understand the provider's error.
- `json.read(response.body(), ChargeReply.class)` returns a `Result`. A success whose status is `"captured"` becomes `Captured(paymentId)`. Any other status, or a body that does not fit `ChargeReply`, is `PROVIDER_ERROR`. `"captured".equals(reply.status())` puts the constant first, so a null status cannot crash it.

```java
    } catch (HttpTimeoutException timedOut) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.TIMEOUT);
    } catch (ConnectException refused) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.UNREACHABLE);
    } catch (IOException brokenExchange) {
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
    } catch (InterruptedException interrupted) {
      // Restore the flag so the server's shutdown sees it, and stop: no charge is confirmed.
      Thread.currentThread().interrupt();
      return new PaymentOutcome.Unavailable(PaymentOutcome.Reason.INTERRUPTED);
    }
  }
}
```

- Four `catch` blocks, in this order, each returning a reason. The order matters: `HttpTimeoutException` and `ConnectException` are both subclasses of `IOException`, so the specific ones must come before `IOException`.
- `HttpTimeoutException` is `TIMEOUT`. Its subclass `HttpConnectTimeoutException` (the connection itself timed out) lands here too; I checked with `javap` that it extends `HttpTimeoutException`.
- `ConnectException` (connection refused: nothing listens on the port) is `UNREACHABLE`.
- Any other `IOException` (the connection broke in the middle) is `PROVIDER_ERROR`.
- `InterruptedException` happens when the thread is told to stop, for example at shutdown. The code calls `Thread.currentThread().interrupt()` to put the flag back, so the code above still sees it, and returns `INTERRUPTED`. Swallowing the flag would make shutdown hang.

### `PaymentStandIn.java`

```java
package io.plainround.seatwave.api.payment;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

```

- It uses the JDK's own web server, `com.sun.net.httpserver`: `HttpServer` listens, and a `HttpExchange` is one request with its reply. Other imports: `ConcurrentHashMap` and `AtomicReference` for safe sharing between threads, `Executors` for the thread pool, `UUID` for payment ids.

```java
/**
 * A stand-in for a payment provider, on 127.0.0.1 only. It is not a payment system: it moves no
 * money and talks to nothing. It keeps a ledger in memory, deduplicates charges by Idempotency-Key
 * as real providers do, and can be told to time out after charging or to fail without charging.
 *
 * <p>Endpoints: {@code POST /v1/charges} (the charge), {@code POST /control/mode?mode=ok|
 * charge_then_timeout|fail} and {@code GET /control/ledger} (for the lab and the tests).
 *
 * <p>Thread-safe: the ledger is a concurrent map updated with one atomic {@code computeIfAbsent}
 * per key, so two racing charges with one key capture once.
 */
public final class PaymentStandIn implements AutoCloseable {

  private static final int MAX_BODY_BYTES = 1_024;
  private static final int OK = 200;
  private static final int CREATED = 201;
  // A charge sent again with the same key answers 200 with the first charge, as providers do.
  private static final int REPLAYED = 200;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int KEY_REUSED = 422;
  private static final int UNAVAILABLE = 503;
  // The JDK server's backlog: 0 lets the operating system choose.
  private static final int SYSTEM_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;

  private final HttpServer server;
  private final ExecutorService workers;
  private final JsonCodec json;
  private final Duration slowReply;
  private final AtomicReference<StandInMode> mode;
  private final Map<String, Charge> chargesByKey = new ConcurrentHashMap<>();

```

- The class comment lists the endpoints: `POST /v1/charges`, `POST /control/mode?mode=ok|charge_then_timeout|fail`, and `GET /control/ledger`. It says it keeps a ledger in memory, deduplicates by `Idempotency-Key` as real providers do, and moves no money.
- "Thread-safe: the ledger is a concurrent map updated with one atomic `computeIfAbsent` per key, so two racing charges with one key capture once." This is the same idea as the database's primary key, done in memory.
- `implements AutoCloseable` lets it sit in a `try (...)` and be stopped automatically.
- The constants are HTTP status numbers. Two are worth a note: `OK = 200` and `REPLAYED = 200` are the same number with two meanings, so each has its own name. `REPLAYED` is the answer to a charge sent again with a known key (the comment says so: 200 with the first charge, as providers do); `OK` is the plain success of the two control endpoints. `SYSTEM_BACKLOG = 0` means the operating system chooses the size of the queue of waiting connections; `STOP_DELAY_SECONDS = 0` means stop at once.

```java
  private PaymentStandIn(
      HttpServer server, ExecutorService workers, JsonCodec json, StandInMode mode, Duration slow) {
    this.server = server;
    this.workers = workers;
    this.json = json;
    this.mode = new AtomicReference<>(mode);
    this.slowReply = slow;
  }
```

- `mode` is an `AtomicReference<StandInMode>`: a box whose content can be read and replaced safely from many threads without a lock. The control endpoint changes it while charges are running. `chargesByKey` is a `ConcurrentHashMap` from provider key to `Charge`: the ledger.
- The constructor is `private`. The only way to get a stand-in is `start`.

```java
  /**
   * Starts the stand-in on 127.0.0.1.
   *
   * @param port the port, or 0 for one the system picks
   * @param mode how it behaves at first
   * @param slowReply how long CHARGE_THEN_TIMEOUT waits before answering
   * @param json the JSON codec
   * @return the running stand-in
   * @throws IOException if the port cannot be bound
   */
  public static PaymentStandIn start(int port, StandInMode mode, Duration slowReply, JsonCodec json)
      throws IOException {
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), SYSTEM_BACKLOG);
    // One virtual thread per call: a slow reply parks a virtual thread, not a platform thread.
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    PaymentStandIn standIn =
        new PaymentStandIn(
            server,
            workers,
            Objects.requireNonNull(json, "json"),
            Objects.requireNonNull(mode, "mode"),
            Objects.requireNonNull(slowReply, "slowReply"));
    server.setExecutor(workers);
    server.createContext("/v1/charges", standIn::handleCharge);
    server.createContext("/control/", standIn::handleControl);
    server.start();
    return standIn;
  }
```

- `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), SYSTEM_BACKLOG)` makes a server bound to the loopback address only. `getLoopbackAddress()` gives `127.0.0.1` here. Port 0 asks the system for any free port (tests use it).
- `Executors.newVirtualThreadPerTaskExecutor()` gives each call its own **virtual thread**: a very light thread that the JDK parks while it waits. The comment: a slow reply parks a virtual thread, not a platform thread, so a stand-in that sleeps 3 seconds per call does not use up a pool.
- `server.setExecutor(workers)` tells the server to run handlers on it. Without it the server would handle one request at a time.
- `server.createContext("/v1/charges", standIn::handleCharge)` maps a path to a handler. `standIn::handleCharge` is a method reference. `"/control/"` with a trailing slash matches every path that starts with it.
- `server.start()` begins accepting connections on a background thread.

```java
  /**
   * The port it listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  /**
   * Changes how it behaves from the next charge on.
   *
   * @param newMode the mode
   */
  public void setMode(StandInMode newMode) {
    mode.set(Objects.requireNonNull(newMode, "newMode"));
  }

  /**
   * Counts captured charges.
   *
   * @return the number of charges captured since start, one per provider key
   */
  public int chargeCount() {
    return chargesByKey.size();
  }

  /**
   * Counts captured charges for one reference.
   *
   * @param reference SeatWave's reference, the hold id
   * @return how many charges carry it; more than 1 would be a double charge
   */
  public long chargesFor(String reference) {
    return chargesByKey.values().stream()
        .filter(charge -> charge.reference().equals(reference))
        .count();
  }
```

- `port()` reports the real port (needed when port 0 was asked). `setMode` changes the behavior for the next charge on. `chargeCount()` is the size of the ledger. `chargesFor(reference)` streams over the charges and counts the ones whose reference equals the hold id; the Javadoc says more than 1 would be a double charge. The retry storm calls it for every hold.

```java
  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.close();
  }
```

- `close()` stops the server with no delay and then closes the executor. `ExecutorService.close()` waits for running tasks, so a call that is sleeping in `CHARGE_THEN_TIMEOUT` delays the close by up to the slow-reply time.

#### `handleCharge`

```java
  private void handleCharge(HttpExchange exchange) throws IOException {
    try (exchange) {
      Optional<IdempotencyKey> key =
          IdempotencyKey.fromHeader(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      if (!"POST".equals(exchange.getRequestMethod()) || key.isEmpty()) {
        send(exchange, BAD_REQUEST, "{\"error\":\"POST with an Idempotency-Key\"}");
        return;
      }
      Result<ChargeRequest, JsonCodec.BindingProblem> body =
          json.read(readBody(exchange), ChargeRequest.class);
      if (!(body
          instanceof
          Result.Success<ChargeRequest, JsonCodec.BindingProblem>(ChargeRequest request))) {
        send(exchange, BAD_REQUEST, "{\"error\":\"malformed charge\"}");
        return;
      }
```

- `try (exchange)` closes the exchange at the end, which finishes the reply. A handler must always close it.
- The header is read with `IdempotencyKey.fromHeader(...)`, the same parser SeatWave uses, so the provider insists on the quoted form. If the method is not POST or there is no valid key, the reply is 400.
- `json.read(readBody(exchange), ChargeRequest.class)` parses the body. The odd layout of the `if (!(body instanceof Result.Success<...>(ChargeRequest request)))` is the formatter's: it means "if the result is not a success, answer 400". Note the pattern variable `request` is usable after the `if` because the `if` returns.

```java
      StandInMode current = mode.get();
      if (current == StandInMode.FAIL) {
        // Asked to fail: no capture, and never a 2xx.
        send(exchange, UNAVAILABLE, "{\"error\":\"provider failing on purpose\"}");
        return;
      }
      String newPaymentId = "pay_" + UUID.randomUUID();
      Charge charge =
          chargesByKey.computeIfAbsent(
              key.get().value(), unused -> new Charge(newPaymentId, request));
      if (!charge.request().equals(request)) {
        send(exchange, KEY_REUSED, "{\"error\":\"key reused with a different charge\"}");
        return;
      }
      if (current == StandInMode.CHARGE_THEN_TIMEOUT) {
        // The money has moved; the reply arrives after the caller stopped waiting.
        waitBeforeReplying(slowReply);
      }
      int status = charge.paymentId().equals(newPaymentId) ? CREATED : REPLAYED;
      send(exchange, status, json.write(new ChargeReply(charge.paymentId(), "captured")));
    }
  }
```

- `current = mode.get()` reads the mode once, so the whole call sees one value.
- `FAIL`: answer 503 and return before anything is recorded. "Asked to fail: no capture, and never a 2xx."
- `newPaymentId` is made fresh. `chargesByKey.computeIfAbsent(key, unused -> new Charge(newPaymentId, request))` is the capture. If the key is new, the lambda runs and the charge is stored. If the key is known, the map returns the old charge and the lambda is not run. The map does this as one atomic step, so two calls with one key capture once.
- `if (!charge.request().equals(request))`: the same key with a different charge (a different amount, say) is answered 422. A record's `equals` compares all three fields.
- `CHARGE_THEN_TIMEOUT`: `waitBeforeReplying(slowReply)` sleeps now. The charge is already in the ledger. "The money has moved; the reply arrives after the caller stopped waiting."
- `status = charge.paymentId().equals(newPaymentId) ? CREATED : REPLAYED`: if the stored charge carries the id just made, this call created it (201). Otherwise it is a replay (200). The body is a `ChargeReply` with the stored payment id, so a replay shows the first call's id.

#### `handleControl`

```java
  private void handleControl(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      if ("GET".equals(exchange.getRequestMethod()) && "/control/ledger".equals(path)) {
        Map<String, Long> byReference = new TreeMap<>();
        chargesByKey
            .values()
            .forEach(charge -> byReference.merge(charge.reference(), 1L, Long::sum));
        send(exchange, OK, json.write(ledgerOf(byReference)));
        return;
      }
      String query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");
      if ("POST".equals(exchange.getRequestMethod()) && "/control/mode".equals(path)) {
        for (StandInMode candidate : StandInMode.values()) {
          if (query.equals("mode=" + candidate.name().toLowerCase(Locale.ROOT))) {
            mode.set(candidate);
            send(exchange, OK, "{\"mode\":\"" + query.substring("mode=".length()) + "\"}");
            return;
          }
        }
      }
      send(exchange, NOT_FOUND, "{\"error\":\"unknown control request\"}");
    }
  }
```

- `GET /control/ledger` builds a map from reference to number of charges (`merge(reference, 1L, Long::sum)` adds one per charge) in a `TreeMap`, so the order is sorted, and sends it with `ledgerOf`.
- `POST /control/mode` is matched against each `StandInMode` value: the query must be exactly `mode=` plus the lower-case name. Only then does it set the mode and echo it. Anything else, including `mode=boom`, ends at the last line: 404 `unknown control request`. `Objects.requireNonNullElse(getQuery(), "")` turns a missing query into an empty text.
- These endpoints have no login. They are safe only because the server listens on the loopback address and the stand-in is a lab tool.

```java
  private Map<String, Object> ledgerOf(Map<String, Long> byReference) {
    // A LinkedHashMap keeps the field order, so the JSON reads the same on every run.
    Map<String, Object> ledger = new LinkedHashMap<>();
    ledger.put("charges", chargesByKey.size());
    ledger.put("byReference", byReference);
    return ledger;
  }
```

- `ledgerOf` builds the JSON shape `{"charges": n, "byReference": {...}}`. A `LinkedHashMap` keeps insertion order, so the fields come out in the same order every run (the comment).

```java
  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream bodyStream = exchange.getRequestBody()) {
      return new String(bodyStream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
    }
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream responseStream = exchange.getResponseBody()) {
      responseStream.write(bytes);
    }
  }
```

- `readBody` reads at most `MAX_BODY_BYTES` (1,024) bytes with `readNBytes`, as UTF-8 text. A body longer than that is cut, will not parse, and gets a 400. `send` sets `Content-Type: application/json`, sends the status and the exact byte length with `sendResponseHeaders(status, bytes.length)`, and writes the bytes.

```java
  private static void waitBeforeReplying(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException interrupted) {
      // The stand-in is closing: keep the flag and answer at once.
      Thread.currentThread().interrupt();
    }
  }

  private record Charge(String paymentId, ChargeRequest request) {
    String reference() {
      return request.reference();
    }
  }
}
```

- `waitBeforeReplying` sleeps for the `Duration`. If interrupted (the stand-in is closing), it restores the flag and returns so the reply is sent at once.
- `Charge` is a private record: the provider-side payment id and the request that made it. Its `reference()` method just reads the request's reference.

### `PaymentStandInCli.java`

```java
package io.plainround.seatwave.api.payment;

import io.plainround.seatwave.api.config.SettingsReader;
import io.plainround.seatwave.api.json.JsonCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Command-line entry point: runs the stand-in payment provider on 127.0.0.1 for a fixed time and
 * prints its ledger at the end. Exit status 2 means bad usage or settings; 1 means the port failed.
 */
public final class PaymentStandInCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_SERVE_SECONDS = 3_600;
  private static final int MAX_SLOW_REPLY_MILLIS = 60_000;
  private static final Pattern MODE = Pattern.compile("ok|charge_then_timeout|fail");

  private PaymentStandInCli() {}

```

- This is the entry point of `./gradlew -q paymentStandIn --args='config/payment-stand-in.properties'`. Exit status 2 means a bad command line or settings, 1 means the port failed.
- `MODE` is the pattern `ok|charge_then_timeout|fail`: the only three words the settings file may use for `standIn.mode`.
- The constants cap the settings: ports 1,024 to 65,535 (below 1,024 needs special rights), at most 3,600 seconds of serving, and a slow reply of at most 60,000 ms.
- `private PaymentStandInCli() {}` stops anyone from creating an object of a class that only has a `main`.

```java
  /**
   * Runs the stand-in described by a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: paymentStandIn <payment-stand-in.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int port = reader.intBetween("standIn.port", LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      String mode = reader.matching("standIn.mode", MODE, "ok, charge_then_timeout or fail");
      Duration slow = reader.millisBetween("standIn.slowReplyMillis", 1, MAX_SLOW_REPLY_MILLIS);
      int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
      if (!reader.problems().isEmpty()) {
        reader.problems().forEach(problem -> System.err.println("invalid: " + problem));
        System.exit(EXIT_BAD_USAGE);
      }
      StandInMode startMode = StandInMode.valueOf(mode.toUpperCase(Locale.ROOT));
```

- `main` must be given exactly one argument, the settings file. Otherwise it prints `usage: paymentStandIn <payment-stand-in.properties>` on standard error and exits with 2.
- `SettingsReader.load(Path.of(arguments[0]))` reads the file; the reader gives typed values: `standIn.port` (1,024 to 65,535), `standIn.mode` (matching `MODE`), `standIn.slowReplyMillis` (1 to 60,000 ms, as a `Duration`) and `serve.seconds` (1 to 3,600).
- If any key was missing or invalid, every problem line is printed as `invalid: ...` and the exit status is 2. Reading all four before checking shows every problem in one run.
- `StandInMode.valueOf(mode.toUpperCase(Locale.ROOT))` turns `charge_then_timeout` into the enum value. `Locale.ROOT` makes the case change independent of the machine's language (in some languages upper-casing `i` differs).

```java
      try (PaymentStandIn standIn = PaymentStandIn.start(port, startMode, slow, new JsonCodec())) {
        System.out.printf(
            Locale.ROOT,
            "payment stand-in on http://127.0.0.1:%d for %d s, mode %s; not a payment system%n",
            standIn.port(),
            seconds,
            mode);
        Thread.sleep(Duration.ofSeconds(seconds));
        System.out.printf(Locale.ROOT, "charges captured: %d%n", standIn.chargeCount());
      }
    } catch (IOException failed) {
      System.err.println("stand-in failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
}
```

- `try (PaymentStandIn standIn = PaymentStandIn.start(...))` starts the server and guarantees `close()`.
- `System.out.printf(...)` prints the first line: `payment stand-in on http://127.0.0.1:8088 for 600 s, mode ok; not a payment system`. `%d` is a whole number, `%s` text, `%n` a line break.
- `Thread.sleep(Duration.ofSeconds(seconds))` keeps the program alive while the server's own threads work. At the end it prints how many charges were captured. An `IOException` (the port is in use) prints `stand-in failed: ...` and exits with 1.

### `Database.java`

```java
package io.plainround.seatwave.api.store;

import io.plainround.seatwave.api.config.ApiSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The embedded H2 database: opened once, migrated once, then one short-lived connection per unit of
 * work. Opening a connection to an embedded H2 database that is already open costs microseconds, so
 * no pool is needed; the number of connections in use is bounded by the HTTP layer's limit on
 * requests in flight.
 *
 * <p>Thread-safe: {@link #connect()} may be called from any thread; each connection belongs to the
 * thread that opened it until it is closed.
 */
public final class Database implements AutoCloseable {

```

- `Database implements AutoCloseable`, so `try (Database db = Database.open(url)) { ... }` closes it.
- The Javadoc is a short design note: opened once, migrated once, then one short-lived connection per unit of work. A connection to an already-open embedded database costs microseconds, so no pool is needed. The number of connections in use is bounded by the HTTP layer's limit on requests in flight.
- "Thread-safe: `connect()` may be called from any thread; each connection belongs to the thread that opened it until it is closed." A JDBC connection is not safe to share between threads, so each unit of work opens its own.

```java
  // Applied in this order. A new migration is appended, never changed in what it creates after
  // release. H2 commits each DDL statement at once, so a script and its schema_history row are
  // separate commits: every statement is written to be safe to run again (IF NOT EXISTS), and a
  // crash between the two makes the next start run the script again harmlessly, then record it.
  private static final List<String> MIGRATIONS =
      List.of(
          "V1__module_1_schema.sql",
          "V2__idempotency_and_payment.sql",
          "V3__seat_order_for_cursors.sql",
          "V4__seats_by_hold.sql");

  private final String url;
  // An in-memory H2 database lives while one connection to it is open; this one keeps it alive.
  private final Connection anchor;

  private Database(String url, Connection anchor) {
    this.url = url;
    this.anchor = anchor;
  }
```

- `MIGRATIONS` is a fixed list of four file names, applied in this order. The comment is the rule: a new migration is appended, and a released one is never changed in what it creates. It also explains why every statement in the scripts is written to be run again harmlessly: H2 commits each schema statement at once, so a script and the row that records it are two separate commits, and a crash between them makes the next start run the script a second time. The list position is the version number: V1 is index 0.
- `url` is the H2 address. `anchor` is the connection that keeps an in-memory database alive (the comment).
- The constructor is `private`; use `open`.

```java
  /**
   * Opens the database and applies every migration it has not seen.
   *
   * @param url the H2 JDBC URL
   * @return the open database
   * @throws SQLException if the database cannot be opened or a migration fails
   */
  public static Database open(String url) throws SQLException {
    Objects.requireNonNull(url, "url");
    Connection anchor = DriverManager.getConnection(url);
    try {
      migrate(anchor);
      return new Database(url, anchor);
    } catch (SQLException migrationFailure) {
      anchor.close();
      throw migrationFailure;
    }
  }
```

- `DriverManager.getConnection(url)` asks JDBC for a connection. JDBC finds the H2 driver because the H2 jar registers itself, and H2 sees the `jdbc:h2:` prefix. For `jdbc:h2:file:...` it opens or creates the file; for `jdbc:h2:mem:...` it creates the database in memory.
- `migrate(anchor)` brings the schema up to date. If it fails, the anchor is closed and the exception is rethrown, so no half-open database is left behind.

```java
  /**
   * Opens a connection in auto-commit mode; the caller closes it.
   *
   * @return a new connection
   * @throws SQLException if the database is unreachable
   */
  public Connection connect() throws SQLException {
    return DriverManager.getConnection(url);
  }
```

- `connect()` opens a new connection each time. The comment says auto-commit mode: each statement is its own transaction until a caller turns it off. The caller must close it. All the services do so with `try (Connection ...)`.

```java
  /**
   * Fills an empty database with the demo events and seats; does nothing if any event exists.
   *
   * @param seed how much to create
   * @param clock the clock the event dates are counted from
   * @throws SQLException if the seed cannot be written
   */
  public void seedIfEmpty(ApiSettings.Seed seed, Clock clock) throws SQLException {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(clock, "clock");
    try (Connection connection = connect()) {
      if (!hasEvents(connection)) {
        DemoSeed.write(connection, seed, clock.instant());
      }
    }
  }
```

- `seedIfEmpty` opens a connection, asks `hasEvents`, and calls `DemoSeed.write` only if there is no event. So starting the server twice on a file database does not seed twice. `Clock` supplies "now" for the event dates.

```java
  @Override
  public void close() throws SQLException {
    anchor.close();
  }

  private static boolean hasEvents(Connection connection) throws SQLException {
    try (Statement query = connection.createStatement();
        ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM events")) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }
```

- `close()` closes the anchor. Once every other connection is closed too, an in-memory database disappears.
- `hasEvents` runs `SELECT COUNT(*) FROM events` and checks that the count is above 0. The statement and result set are both in the `try` so both close.

```java
  private static void migrate(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS schema_history (version INT PRIMARY KEY,"
              + " script VARCHAR(80) NOT NULL)");
    }
    for (int index = 0; index < MIGRATIONS.size(); index++) {
      int version = index + 1;
      if (isApplied(connection, version)) {
        continue;
      }
      try (Statement statement = connection.createStatement()) {
        // The script name is one of the constants above, never input, so this is not injectable.
        statement.execute("RUNSCRIPT FROM 'classpath:/db/migration/" + MIGRATIONS.get(index) + "'");
      }
      try (PreparedStatement record =
          connection.prepareStatement(
              "INSERT INTO schema_history (version, script) VALUES (?, ?)")) {
        record.setInt(1, version);
        record.setString(2, MIGRATIONS.get(index));
        record.executeUpdate();
      }
    }
  }
```

- `migrate` first makes the table that remembers which migrations ran: `CREATE TABLE IF NOT EXISTS schema_history (version INT PRIMARY KEY, script VARCHAR(80) NOT NULL)`. `IF NOT EXISTS` makes it safe to run on every start.
- The loop goes through the list. `isApplied(connection, version)` looks for the version in `schema_history`; if found, `continue` skips it.
- `statement.execute("RUNSCRIPT FROM 'classpath:/db/migration/" + MIGRATIONS.get(index) + "'")` is an H2 command that reads an SQL file and runs every statement in it. `classpath:` means "inside the program's own resources", so the migration files travel inside the program. The comment explains the string join is safe: the name is one of the constants above, never input.
- Then `INSERT INTO schema_history (version, script) VALUES (?, ?)` records the version. On the next start the migration is skipped.
- The migration runs and the record is written as two separate steps, on a connection in auto-commit mode. H2 commits a schema change as soon as it runs. If the process dies between the two, the schema has changed and the history does not say so. The next start runs the same script again. That is safe because every statement in the scripts is written to be run again (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS` and the like): the second run changes nothing, and then the row is written. `DatabaseTest` builds exactly that state, a migrated database with an empty history, and checks that it starts. The design chosen and the alternative rejected are in `ARCHITECTURE.md`.

```java
  private static boolean isApplied(Connection connection, int version) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?")) {
      query.setInt(1, version);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
}
```

- `isApplied` is a `SELECT 1 FROM schema_history WHERE version = ?`; the existence of a row means yes.

### `DemoSeed.java`

```java
package io.plainround.seatwave.api.store;

import io.plainround.seatwave.api.config.ApiSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/** Writes Module 1's demo event with its seats, then seatless events for the catalog's pages. */
final class DemoSeed {

  // Module 1's demo event: id 1, "Arena Night", 30 days after the seed.
  static final long DEMO_EVENT_ID = 1L;
  private static final String DEMO_EVENT_NAME = "Arena Night";
  private static final Duration DEMO_EVENT_LEAD = Duration.ofDays(30);
  // The further events start one day apart after the demo event, so the list has a stable order.
  private static final Duration EVENT_SPACING = Duration.ofDays(1);
  // Module 1 inserted seats in batches of 1,000: one round trip per batch instead of per seat.
  private static final int SEED_BATCH_SIZE = 1_000;

  private DemoSeed() {}
```

- The class is package-private and `final`; it cannot be instantiated (`private DemoSeed() {}`). Constants: the demo event has id 1 and the name "Arena Night", it starts 30 days after the seed, further events start one day apart (so the event list has a stable order), and seats are inserted in batches of 1,000.
- A **batch** sends many `INSERT` rows to the database in one round trip. With 20,000 seats that is 20 trips and not 20,000.

```java
  static void write(Connection connection, ApiSettings.Seed seed, Instant now) throws SQLException {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      insertEvents(connection, seed, now.plus(DEMO_EVENT_LEAD));
      insertSeats(connection, seed);
      // The events and their seats commit together, so a failed seed is retried whole.
      connection.commit();
    } catch (SQLException seedFailure) {
      connection.rollback();
      throw seedFailure;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }
```

- `write` turns auto-commit off on the connection it is given, remembering the old setting. It inserts the events and then the seats, then calls `commit()` once. The comment: the events and their seats commit together, so a failed seed is retried whole. On an `SQLException` it rolls back and rethrows. The `finally` block restores auto-commit whether or not an error happened.

```java
  private static void insertEvents(Connection connection, ApiSettings.Seed seed, Instant firstStart)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)")) {
      for (long eventId = DEMO_EVENT_ID; eventId <= seed.events(); eventId++) {
        insert.setLong(1, eventId);
        insert.setString(2, eventId == DEMO_EVENT_ID ? DEMO_EVENT_NAME : "Event " + eventId);
        insert.setLong(
            3, firstStart.plus(EVENT_SPACING.multipliedBy(eventId - DEMO_EVENT_ID)).toEpochMilli());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }
```

- `INSERT INTO events (event_id, name, starts_at) VALUES (?, ?, ?)` is prepared once and reused for every event. For event ids 1 to `seed.events()` (120 in `config/serve.properties`) the loop sets the three values: the id, the name (`Arena Night` for 1, otherwise `"Event " + eventId`), and the start time in epoch milliseconds: first start plus `eventId - 1` days.
- `insert.addBatch()` queues the row. `insert.executeBatch()` sends all queued rows at once.

```java
  private static void insertSeats(Connection connection, ApiSettings.Seed seed)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO seats (event_id, seat_label, section, seat_number, status)"
                + " VALUES (?, ?, ?, ?, 'AVAILABLE')")) {
      int seatsInBatch = 0;
      for (int sectionIndex = 0; sectionIndex < seed.sections(); sectionIndex++) {
        String section = String.valueOf((char) ('A' + sectionIndex));
        for (int seatNumber = 1; seatNumber <= seed.seatsPerSection(); seatNumber++) {
          insert.setLong(1, DEMO_EVENT_ID);
          insert.setString(2, section + "-" + seatNumber);
          insert.setString(3, section);
          insert.setInt(4, seatNumber);
          insert.addBatch();
          seatsInBatch++;
          if (seatsInBatch == SEED_BATCH_SIZE) {
            insert.executeBatch();
            seatsInBatch = 0;
          }
        }
      }
      insert.executeBatch();
    }
  }
}
```

- `INSERT INTO seats (event_id, seat_label, section, seat_number, status) VALUES (?, ?, ?, ?, 'AVAILABLE')` writes seats of event 1 only (the other events have none; they exist to give the event list pages).
- The outer loop is over sections. `(char) ('A' + sectionIndex)` turns 0 into `A`, 1 into `B`. With 20 sections the letters are A to T, the same range as the seat label rule. The inner loop goes over seat numbers 1 to `seatsPerSection` (1,000). The label is `section + "-" + seatNumber`, for example `A-7`.
- `seat_number` is the numeric column added by migration V3. It is written here so the cursor order (section, then number) works; the label alone would sort as text (`A-1`, `A-10`, `A-100`, `A-2`).
- After every 1,000 queued rows the batch is sent and the counter reset. The last `executeBatch()` sends the rest.
- With the default 20 sections of 1,000, this writes 20,000 seats.

## Try it yourself

All commands run in a scratch copy of the repository. Output differs in ids and timings.

**1. Run the stand-in alone and charge it.** Copy `config/payment-stand-in.properties` to a scratch folder, change the port to a free one (I used 28288) and `serve.seconds` to 40, then:

```text
./gradlew -q paymentStandIn --args='<path of your copy>'
```

```text
payment stand-in on http://127.0.0.1:28288 for 40 s, mode ok; not a payment system
```

In another terminal:

```text
curl -s -i -X POST http://127.0.0.1:28288/v1/charges -H 'Content-Type: application/json' -H 'Idempotency-Key: "seatwave-hold-aaaaaaaa-0000-4000-8000-000000000001"' -d '{"amount":"80.00","currency":"USD","reference":"aaaaaaaa-0000-4000-8000-000000000001"}'
```

```text
HTTP/1.1 201 Created
Content-type: application/json
Content-length: 76

{"paymentId":"pay_870e7071-276e-412d-953b-aed8f88f2b9d","status":"captured"}
```

Run the same command again: the body is identical but the status is `200`. Change the amount to `90.00` with the same key: `{"error":"key reused with a different charge"}` with `422`. Leave out the key: `{"error":"POST with an Idempotency-Key"}` with `400`. Send `{"amount":"80.00"}`: `{"error":"malformed charge"}` with `400`.

**2. Read the ledger, then make it fail.**

```text
curl -s http://127.0.0.1:28288/control/ledger
curl -s -X POST 'http://127.0.0.1:28288/control/mode?mode=fail'
```

```text
{"charges":1,"byReference":{"aaaaaaaa-0000-4000-8000-000000000001":1}}
{"mode":"fail"}
```

A charge in `fail` mode answers `{"error":"provider failing on purpose"}` with `503`, and the ledger still says 1 charge. `mode=boom` answers `404`.

**3. Charge, then time out.**

```text
curl -s -X POST 'http://127.0.0.1:28288/control/mode?mode=charge_then_timeout'
curl -s -m 1.5 -w ' [curl %{http_code} %{time_total}s]\n' -X POST http://127.0.0.1:28288/v1/charges -H 'Content-Type: application/json' -H 'Idempotency-Key: "k4"' -d '{"amount":"80.00","currency":"USD","reference":"z"}'
curl -s http://127.0.0.1:28288/control/ledger
```

`curl -m 1.5` gives up after 1.5 seconds and prints ` [curl 000 1.502215s]` with no reply. The ledger then shows `"z":1` in `byReference`: the charge happened and nobody was told. I stopped the stand-in with `pkill` afterwards (it also ends itself after `serve.seconds`).

**4. See the migrations run.** In a scratch folder, save this as `Mig.java`, compile the repository once (`./gradlew compileJava processResources`), and run it with the class folders and the H2 jar on the class path:

```text
import io.plainround.seatwave.api.store.Database;
import java.sql.*;

public class Mig {
  public static void main(String[] a) throws Exception {
    try (Database db = Database.open(a[0]);
        Connection c = db.connect();
        Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SELECT version, script FROM schema_history ORDER BY version");
      while (r.next()) System.out.println(r.getInt(1) + " " + r.getString(2));
    }
  }
}
```

```text
java -cp build/classes/java/main:build/resources/main:<path to h2-2.5.252.jar> Mig.java "jdbc:h2:file:/tmp/scratch-db/db"
```

```text
1 V1__module_1_schema.sql
2 V2__idempotency_and_payment.sql
3 V3__seat_order_for_cursors.sql
4 V4__seats_by_hold.sql
```

Run it a second time: the same four lines, and no migration runs again. If you leave out `build/resources/main` from the class path, `RUNSCRIPT` cannot find the SQL files and the program fails with `FileNotFoundException: resource /db/migration/V1__module_1_schema.sql`. That is how you see that the migrations travel as resources of the program.

**5. Run the payment tests.** In a scratch copy: `./gradlew test --tests '*PaymentStandInTest*' --tests '*ConfirmPaymentTest*' --tests '*DatabaseTest*'`. On my machine 19 test methods printed `PASSED` (6, 8 and 5) and the build ended with `BUILD SUCCESSFUL`. The README reports the full run (`./gradlew clean check`, 89 tests on 11 November 2026).

## Mistakes people make

- **Treating a timeout as a failure.** The charge may have happened. This repository keeps the seat `CONFIRMING`, ends only the lease, and retries with the same provider key.
- **Making the provider key from something the client controls.** A client could send two keys for one hold and be charged twice. Here the key is made from the hold id.
- **Letting a test double pretend.** A stand-in that answers 2xx for a failure hides bugs. This one never does.
- **Forgetting the port is local.** The stand-in has no authentication and its control endpoints change its behavior. It binds `127.0.0.1` only, and `ApiSettings.Payment` accepts only a `http://127.0.0.1:<port>` provider.
- **Sharing a connection between threads.** A JDBC connection is not thread-safe. Here every unit of work opens its own.
- **Editing a migration that already ran.** Existing databases would not run it again and would silently differ. Here a change is a new file added to the end of the list.
- **Opening a file database from two processes.** The second fails. The retry storm and page walk use in-memory databases so they never collide with a running server.

## Check yourself

1. Why does the provider key come from the hold and not from the client's `Idempotency-Key`? (So every attempt for one hold names one charge, whatever key the client sent and even after SeatWave forgot the client's key.)
2. In `CHARGE_THEN_TIMEOUT`, when is the charge recorded: before or after the wait? (Before. The ledger already has it when the wait begins. That is what makes the case realistic.)
3. What does a status of 200 from `POST /v1/charges` mean, against 201? (200: the key was known, this is the first charge replayed with its first payment id. 201: this call created the charge.)
4. Why does `Database` keep an "anchor" connection open? (An in-memory H2 database exists only while a connection to it is open; the anchor keeps it alive between units of work.)
5. Why is no pool of connections used? (Opening a connection to an already-open embedded database costs microseconds, about 13 in my measurement, and the in-flight limit bounds how many are open at once.)

## Further reading

Official documentation, for Java 25 and H2 2.5.252:

- `java.net.http.HttpClient` and the package overview: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- `java.net.http.HttpRequest`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpRequest.html
- `com.sun.net.httpserver` (the `jdk.httpserver` module): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- Virtual threads (JEP 444): https://openjdk.org/jeps/444
- `java.util.concurrent.ConcurrentHashMap.computeIfAbsent`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
- `java.sql.DriverManager`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/DriverManager.html
- H2 database features (in-memory and file databases, `RUNSCRIPT`, `LOCK_TIMEOUT`): https://h2database.com/html/features.html
- H2 `RUNSCRIPT` command: https://h2database.com/html/commands.html#runscript
- RFC 9110, HTTP Semantics (status codes 200, 201, 422, 503): https://www.rfc-editor.org/rfc/rfc9110
