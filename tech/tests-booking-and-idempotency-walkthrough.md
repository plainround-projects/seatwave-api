# Walkthrough of the `booking` and `idempotency` test packages

## What it is

These are the tests that attack the heart of the lesson: a client times out and **retries**, and the API must not hold a seat twice, book it twice or charge twice. Six files in two packages (all under `src/test/java/io/plainround/seatwave/api/`):

| File | What it does |
| --- | --- |
| `booking/HoldRetryTest.java` | Nine attacks on `POST /v1/holds`: repeats, a different body, reordered fields, 100 racing retries, 100 fans, a lost reply, an expired key, a crash between the effect and the key write, a stored refusal. |
| `booking/ConfirmPaymentTest.java` | Eight attacks on `POST /v1/bookings`, the call that takes a payment: repeats, a provider timeout after capture, a provider failure, 50 racing confirms, a second key, an expired key, a dead process, bad holds. |
| `booking/FailingKeyWrite.java` | A helper: a database trigger that makes the key write fail, to simulate a crash. |
| `idempotency/FingerprintTest.java` | The fingerprint of a request: same request same value, different request different value. |
| `idempotency/IdempotencyKeyTest.java` | Reading the `Idempotency-Key` header: only the quoted string the contract defines. |
| `idempotency/IdempotencyStoreTest.java` | The key table directly: purge in batches, complete once, replay, and two requests with one expired key. |

## Why this repository uses it

The README promises "a retry test that proves no double booking and no double charge". The proof has a rule: **count from the database, never from the replies**. A reply can say anything, so a test that reads only replies could pass while the database holds two bookings. These tests read the database with `SELECT COUNT(*)` queries and the stand-in provider's ledger with `chargesFor(holdId)`. This is what `TestApi.count` is for (`tests-other-packages-walkthrough.md`).

## Where it appears

The tests run in `./gradlew test` and in `check`. They use the helpers of the root test package: `TestApi` (an API plus a stand-in provider plus a movable clock), `AllAtOnce` (many tasks released at one moment), `HttpStatus` (status names) and `MutableClock`.

## How it works underneath

The ideas that the tests rely on, in the order the service uses them:

1. **An idempotency key.** The client picks a key for one intended action and sends it in the `Idempotency-Key` header, as a quoted string such as `"abc-1"`. A retry sends the same key. The server must act once per key.
2. **The key table.** `idempotency_keys` has the primary key `(endpoint, idempotency_key)`, named `one_effect_per_key`. A primary key refuses a second row with the same pair. So when 100 retries each try to insert the pair, the database lets exactly one in. The others wait for it and then read its outcome.
3. **A fingerprint.** For each request the server computes a SHA-256 digest of the endpoint and the body in a canonical form (re-written from the parsed record, so spacing and field order do not matter). The digest is stored with the key. A retry with the same key and a different digest is a client error: the key is being reused for something else.
4. **One transaction.** A **transaction** is a group of database changes that either all happen or none does. A hold claims the key, changes the seat and stores the reply in one transaction, so a crash in the middle leaves neither a held seat nor a key.
5. **Replay.** After the first request completes, the stored reply is sent again to every retry with that key until the key expires (24 hours).
6. **Confirm is harder** because it calls a remote payment provider and no database transaction may stay open across that call. It works in three steps: claim the key and **lease** the seat (status `CONFIRMING`, with a time until which this attempt owns it); call the provider with a key made from the hold; then write the booking and the reply in a second transaction. A lease that runs out can be taken over.
7. **A clock the test moves.** Expiry (a hold lasts 300 seconds, a key 24 hours, a lease 2 seconds in the tests) is tested by advancing a `MutableClock`, not by waiting.

The seat's status is one of `AVAILABLE`, `HELD`, `CONFIRMING`, `CONFIRMED` (a `CHECK` rule of the database refuses any other word).

## Walkthrough of this repository's use

### `booking/FailingKeyWrite.java`

```java
package io.plainround.seatwave.api.booking;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/**
 * An H2 trigger that fails every write of a response into the key table: the crash "between the
 * database write and the key write". H2 creates the trigger itself from its class name, so the test
 * switches it on and off with CREATE and DROP TRIGGER instead of passing anything in.
 */
public final class FailingKeyWrite implements Trigger {

  /** The SQL that installs it. */
  public static final String INSTALL =
      "CREATE TRIGGER crash_before_key_write BEFORE UPDATE ON idempotency_keys FOR EACH ROW"
          + " CALL \"io.plainround.seatwave.api.booking.FailingKeyWrite\"";

  /** The SQL that removes it. */
  public static final String REMOVE = "DROP TRIGGER crash_before_key_write";

  @Override
  public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
    // Thrown after the seat UPDATE ran in the same transaction: the process "dies" here.
    throw new SQLException("simulated crash between the effect and the key write", "08006");
  }
}
```

- `org.h2.api.Trigger` is H2's interface for a **trigger**: code that the database runs automatically before or after a row changes. `FailingKeyWrite implements Trigger` and overrides the one method that matters, `fire`.
- The Javadoc explains the trick: H2 creates the trigger **itself from its class name**, so the test turns it on and off with SQL, passing nothing in.
- `INSTALL` is that SQL: `CREATE TRIGGER crash_before_key_write BEFORE UPDATE ON idempotency_keys FOR EACH ROW CALL "io.plainround.seatwave.api.booking.FailingKeyWrite"`. Read it as: before any `UPDATE` of a row in `idempotency_keys`, call this class. The `+` joins two string pieces so the line stays short. `REMOVE` drops the trigger by name.
- Why an `UPDATE`? A hold first **inserts** the key row as `IN_PROGRESS`, then changes the seat, then **updates** the key row to `COMPLETED` with the stored reply. The trigger fires at the last step.
- `fire` throws `new SQLException("simulated crash between the effect and the key write", "08006")`. The second argument is the SQLSTATE, a five-character code for the kind of failure; class `08` is "connection exception", the kind of failure a lost database connection raises. The test expects the server to answer `503` to it. The comment on line 24 says it all: "Thrown after the seat UPDATE ran in the same transaction: the process 'dies' here."
- The exception makes the transaction roll back, so the claim and the seat change vanish. That is what the test checks.

### `booking/HoldRetryTest.java`

#### Header, constants, lifecycle

```java
package io.plainround.seatwave.api.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.AllAtOnce;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retry test of Module 7: it attacks the hold's idempotency for real, over HTTP, and proves "no
 * double booking" with counts read from the database, never from the replies.
 */
class HoldRetryTest {

  private static final Pattern HOLD_ID = Pattern.compile("\"holdId\":\"([0-9a-f-]{36})\"");
  private static final int CONCURRENT_RETRIES = 100;
  private static final int SEQUENTIAL_REPEATS = 10;
  private static final Duration SERVER_FINISHES_WITHIN = Duration.ofSeconds(5);
  private static final String LIVE_HOLDS_OF_SEAT =
      "SELECT COUNT(*) FROM seats WHERE event_id = 1 AND seat_label = ? AND status = 'HELD'"
          + " AND hold_expires_at > ?";

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

- The imports: `AllAtOnce` runs many tasks together; `ContractLimits` has the 300 seconds and the 24 hours; `Awaitility.await` waits (`awaitility.md`); `Socket`, `InetAddress` and `OutputStream` are used by the raw-socket helper at the end; `Pattern` and `Matcher` read the hold id out of a body.
- The Javadoc: "it attacks the hold's idempotency for real, over HTTP, and proves 'no double booking' with counts read from the database, never from the replies."
- `HOLD_ID` is a regular expression for `"holdId":"` followed by a lower-case UUID (36 characters of hex digits and hyphens), captured in a group.
- `CONCURRENT_RETRIES = 100` is how many retries race. `SEQUENTIAL_REPEATS = 10` is how many times the first test repeats. `SERVER_FINISHES_WITHIN` is the limit of the wait in the lost-reply test.
- `LIVE_HOLDS_OF_SEAT` is a SQL query with two `?` slots: how many rows in `seats` are for event 1, the given seat label, status `HELD`, and a hold that has not yet expired (`hold_expires_at > ?`, where the second slot is the current time in milliseconds). It counts **live** holds of one seat.
- `@BeforeEach` starts a fresh `TestApi` and `@AfterEach` closes it (`junit.md`).

#### Attack 1: repeat the same request ten times

```java
  @Test
  void repeatsTheSameHoldWithoutActingAgain() throws Exception {
    Set<String> holdIds = new HashSet<>();

    for (int attempt = 0; attempt < SEQUENTIAL_REPEATS; attempt++) {
      TestApi.Response reply = api.hold("A-1", "same-request");
      assertThat(reply.status()).isEqualTo(HttpStatus.CREATED);
      holdIds.add(holdIdIn(reply.body()));
    }

    assertThat(holdIds).hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE hold_id = ?", holdIds.iterator().next()))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }
```

- Ten times in a row, the same seat `A-1` with the same key `same-request`.
- Each reply must be `201 Created`, and `holdIdIn(reply.body())` collects the hold id into a `Set`. A set keeps one copy of each value, so `hasSize(1)` means all ten replies named the same hold.
- The **database** proof: exactly one row in `seats` has that `hold_id` (`SELECT COUNT(*) FROM seats WHERE hold_id = ?`), and exactly one row is in `idempotency_keys`. Ten requests, one effect, one key.

#### Attack 2: the same key with a different body

```java
  @Test
  void rejectsTheSameKeyWithADifferentBody() throws Exception {
    api.hold("A-1", "one-key");

    TestApi.Response reply = api.hold("A-2", "one-key");

    assertThat(reply.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(reply.body()).contains("/problems/idempotency-key-reused");
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-2", TestApi.START.toEpochMilli())).isZero();
  }
```

- The first hold uses `A-1` and the key `one-key`. The second uses **`A-2`** with the same key. That is not a retry of the first request; it is a different request that reuses the key.
- The server must answer `422 Unprocessable Content` with the problem type `/problems/idempotency-key-reused`.
- The proof that nothing happened: no live hold on `A-2` (`isZero()`). The time argument is `TestApi.START.toEpochMilli()`, the clock's start, because no time has passed.

#### Attack 3: the same body in a different spelling

```java
  @Test
  void treatsReorderedFieldsAsTheSameBody() throws Exception {
    String first = api.hold("A-1", "reordered").body();

    TestApi.Response retry =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("reordered"),
            "{ \"customer\": \"fan_1\", \"seat\": \"A-1\", \"eventId\": 1 }");

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(holdIdIn(retry.body())).isEqualTo(holdIdIn(first));
  }
```

The retry sends the same fields in a different order and with extra spaces: `{ "customer": "fan_1", "seat": "A-1", "eventId": 1 }`. A client library that reorders fields must not be punished. Because the fingerprint is made from the parsed record, not from the raw text, both spellings have the same fingerprint, so the server replays: status `201` and the same hold id as the first reply.

#### Attack 4: 100 retries of one request at the same instant

```java
  @Test
  void holdsOnceWhenOneHundredRetriesWithOneKeyRaceEachOther() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.hold("A-1", "racing-retries"));

    assertThat(replies)
        .allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED));
    assertThat(replies.stream().map(reply -> holdIdIn(reply.body())).distinct()).hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }
```

- `AllAtOnce.run(100, attempt -> () -> api.hold("A-1", "racing-retries"))` starts 100 threads that each wait at a gate, then opens the gate so all 100 requests leave together (`tests-other-packages-walkthrough.md` explains `AllAtOnce`). The lambda `attempt -> () -> ...` takes the index and returns the work for that index; all 100 use the same key.
- Expected outcome: every reply is `201` (`allSatisfy`); the 100 hold ids collapse to one (`distinct()` then `hasSize(1)`).
- **The database says**: exactly one seat is `HELD` (`SELECT COUNT(*) FROM seats WHERE status = 'HELD'`), and exactly one key row exists. That is how the primary key does its work: one transaction inserts the key, the other 99 wait on it and then read its result. This is "100 retries, one owner and 99 replays".

#### Attack 5: 100 different fans, one seat

```java
  @Test
  void letsOneOfOneHundredFansWithTheirOwnKeysHoldTheSeat() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, fan -> () -> api.hold("A-1", "fan-" + fan));

    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CREATED)).hasSize(1);
    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CONFLICT))
        .hasSize(CONCURRENT_RETRIES - 1);
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-1", TestApi.START.toEpochMilli())).isEqualTo(1);
  }
```

- This time each of the 100 requests has **its own key** (`"fan-" + fan`). They are 100 different people who all want `A-1`.
- Exactly one gets `201`; the other 99 get `409 Conflict`. `HttpStatus.CONFLICT` is the status for "the state of the seat does not allow this".
- The database check: exactly one live hold of `A-1`. This is the plain "no double booking" case, with no retries involved. It shows that keys do not replace the seat's own protection (a conditional `UPDATE` of the seat).

#### Attack 6: a reply that is lost

```java
  @Test
  void returnsTheFirstHoldToARetryAfterALostReply() throws Exception {
    sendAndHangUp(TestApi.holdBody("A-1", "fan_1"), "lost-reply");
    // The server finishes the hold although nobody reads the reply.
    await()
        .atMost(SERVER_FINISHES_WITHIN)
        .until(() ->
            api.count(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE state = 'COMPLETED'"
                        + " AND idempotency_key = ?",
                    "lost-reply")
                == 1);

    TestApi.Response retry = api.hold("A-1", "lost-reply");

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE hold_id = ?", holdIdIn(retry.body())))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
  }
```

- `sendAndHangUp(...)` (below) sends a complete request and closes the connection **without reading the reply**, like a phone that loses its signal.
- The server does not know the client left. It carries on and finishes the hold. The test waits (with Awaitility, `awaitility.md`) until the key row for `lost-reply` is in state `COMPLETED`.
- Then the retry with the same key must answer `201`, name a hold id that appears on exactly one seat row, and leave exactly one `HELD` seat in the whole database. The retry gets the first answer, which the client never saw.

#### Attack 7: a retry that arrives after the key was forgotten

```java
  @Test
  void treatsARetryAfterTheKeyExpiredAsANewRequest() throws Exception {
    String firstHold = holdIdIn(api.hold("A-1", "late-retry").body());
    api.clock().advance(ContractLimits.KEY_RETENTION.plusSeconds(1));

    TestApi.Response lateRetry = api.hold("A-1", "late-retry");

    // The first hold expired after 300 s, so the seat was free again: the late retry is a new hold.
    assertThat(lateRetry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(holdIdIn(lateRetry.body())).isNotEqualTo(firstHold);
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-1", api.clock().instant().toEpochMilli()))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }
```

- `api.clock().advance(ContractLimits.KEY_RETENTION.plusSeconds(1))` moves the test clock 24 hours and a second ahead. The key has expired and the 300-second hold has expired too.
- The same key now means a new request (the comment on line 144: "The first hold expired after 300 s, so the seat was free again: the late retry is a new hold."). The status is `201`, the hold id is **different** from the first, there is one live hold, and there is one key row: the old row was forgotten and replaced.
- This documents a limit: a key is a promise for 24 hours, not forever. The contract says so in plain words.

#### Attack 8: the process dies between the effect and the key write

```java
  @Test
  void leavesNeitherSeatNorKeyWhenTheProcessDiesBetweenEffectAndKeyWrite() throws Exception {
    api.execute(FailingKeyWrite.INSTALL);

    TestApi.Response crashed = api.hold("A-1", "crash");

    assertThat(crashed.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isZero();
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isZero();

    api.execute(FailingKeyWrite.REMOVE);
    List<TestApi.Response> retries =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.hold("A-1", "crash"));

    assertThat(retries)
        .allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED));
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }
```

- `api.execute(FailingKeyWrite.INSTALL)` installs the trigger. Now the final step of a hold (updating the key to `COMPLETED`) always fails.
- The hold request must answer `503 Service Unavailable`. Then the **database** must hold **nothing**: no `HELD` seat and no key row. The seat change and the key were in one transaction, so the failure rolled both back.
- `api.execute(FailingKeyWrite.REMOVE)` drops the trigger. Then 100 retries with the same key race. All answer `201`, one seat is `HELD`, one key exists. The retry finds no trace of the crash and does the work once.

#### Attack 9: a stored refusal

```java
  @Test
  void replaysAStoredRefusalEvenAfterTheSeatIsFreeAgain() throws Exception {
    String otherFansHold = holdIdIn(api.hold("A-1", "other-fan").body());
    assertThat(api.hold("A-1", "refused").status()).isEqualTo(HttpStatus.CONFLICT);
    api.send("DELETE", "/v1/holds/" + otherFansHold, Map.of(), "");

    TestApi.Response sameKey = api.hold("A-1", "refused");
    TestApi.Response newKey = api.hold("A-1", "refused-then-new");

    assertThat(sameKey.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(newKey.status()).isEqualTo(HttpStatus.CREATED);
  }
```

- Another fan holds `A-1`. A request with the key `refused` is refused with `409`. That refusal is stored under the key.
- The other fan's hold is cancelled with a `DELETE` (so the seat is free again).
- The same key `refused` again: still `409`. A key means "this action", and the answer to that action was a refusal; it is replayed even though the seat is free now. With a **new** key (`refused-then-new`) the seat is holdable and the answer is `201`.

#### The raw-socket helper

```java
  private void sendAndHangUp(String body, String key) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String head =
        "POST /v1/holds HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
            + "Idempotency-Key: \""
            + key
            + "\"\r\nContent-Length: "
            + bytes.length
            + "\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), api.port());
        OutputStream out = socket.getOutputStream()) {
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(bytes);
      out.flush();
      // Close without reading: the reply is lost, as when a phone loses its signal.
    }
  }
```

- Why not use the JDK's `HttpClient`? Because it would wait for the reply. This helper writes the HTTP text itself.
- `body.getBytes(StandardCharsets.UTF_8)` turns the JSON into bytes, so `bytes.length` is the exact `Content-Length`.
- `head` is the **request head** in the HTTP/1.1 text format: the request line `POST /v1/holds HTTP/1.1`, then header lines each ending in `\r\n` (carriage return and line feed), a `Host` header, the content type, the quoted key, the length, `Connection: close`, and an empty line (`\r\n\r\n`) that ends the head.
- `new Socket(InetAddress.getLoopbackAddress(), api.port())` opens a TCP connection to this machine only. The `try ( ... )` closes the socket and the stream at the end of the block, whatever happens.
- `out.write(head...)`, `out.write(bytes)` and `out.flush()` send the whole request. Falling off the end of the `try` block closes the socket without reading: the comment on line 199 says "Close without reading: the reply is lost, as when a phone loses its signal."

#### `holdIdIn`

```java
  static String holdIdIn(String body) {
    Matcher matcher = HOLD_ID.matcher(body);
    assertThat(matcher.find()).as("a holdId in %s", body).isTrue();
    return matcher.group(1);
  }
}
```

`holdIdIn` is package-private and `static` because `ConfirmPaymentTest` calls it too. `matcher.find()` must succeed, with the body in the failure message (`as("a holdId in %s", body)`). `matcher.group(1)` is the UUID in the round brackets of the pattern.

### `booking/ConfirmPaymentTest.java`

#### Header and the starting state

```java
package io.plainround.seatwave.api.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.AllAtOnce;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.payment.StandInMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The interview question as tests: a client times out and retries a payment request; the API must
 * not charge twice. Charges are counted in the provider's ledger and bookings in the database.
 */
class ConfirmPaymentTest {

  private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\":\"([0-9a-f-]{36})\"");
  private static final int CONCURRENT_RETRIES = 50;
  // The lease a process that died mid-payment left behind.
  private static final Duration DEAD_PROCESS_LEASE = Duration.ofSeconds(2);

  private TestApi api;
  private String holdId;

  @BeforeEach
  void startApiAndHold() throws Exception {
    api = TestApi.start();
    holdId = HoldRetryTest.holdIdIn(api.hold("A-1", "hold-for-confirm").body());
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }
```

- The Javadoc: "The interview question as tests: a client times out and retries a payment request; the API must not charge twice. Charges are counted in the provider's ledger and bookings in the database."
- `BOOKING_ID` reads the booking id out of a body, like `HOLD_ID`. `CONCURRENT_RETRIES = 50`. `DEAD_PROCESS_LEASE` is the 2-second lease that a process "left behind" in test 7; the API's lease in the tests is also 2 seconds (`TestApi.CONFIRM_LEASE`).
- `startApiAndHold()` is `@BeforeEach`: it starts the API and **holds seat A-1** with the key `hold-for-confirm`, keeping the hold id in a field. Every test begins with a held seat to confirm. `HoldRetryTest.holdIdIn` reads the id.

#### Test 1: the same confirm twice

```java
  @Test
  void confirmsOnceAndChargesOnceWhenTheConfirmIsRepeated() throws Exception {
    String first = bookingIdIn(api.confirm(holdId, "pay-once").body());

    TestApi.Response repeat = api.confirm(holdId, "pay-once");

    assertThat(repeat.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(bookingIdIn(repeat.body())).isEqualTo(first);
    assertThat(repeat.header("Location")).isEqualTo("/v1/bookings/" + first);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }
```

The second `confirm` with the key `pay-once` must return `201` with the **same** booking id and the same `Location` header, `/v1/bookings/<id>`. Then the two proofs: the provider's ledger holds one charge for this hold, and the `bookings` table has one row.

#### Test 2: the provider captures the money, then the answer is late

```java
  @Test
  void chargesOnceWhenTheProviderTimesOutAfterCapturingAndTheClientRetries() throws Exception {
    api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT);

    TestApi.Response timedOut = api.confirm(holdId, "timeout-then-retry");

    assertThat(timedOut.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(timedOut.body()).contains("/problems/payment-unavailable");
    assertThat(timedOut.header("Retry-After")).isEqualTo("1");
    // The money moved, but nothing is booked and the seat is never resold blindly.
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isZero();
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'CONFIRMING'")).isEqualTo(1);

    api.provider().setMode(StandInMode.OK);
    TestApi.Response retried = api.confirm(holdId, "timeout-then-retry");

    assertThat(retried.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }
```

This is the dangerous case. The stand-in provider is told `CHARGE_THEN_TIMEOUT`: it takes the money and then answers after the API's timeout.
- The API cannot know whether the money moved. It answers `503` with the problem `/problems/payment-unavailable` and the header `Retry-After: 1` (the stand-in's retry hint is one second).
- The comment on line 67 states the rule: "The money moved, but nothing is booked and the seat is never resold blindly." The counts prove it: the ledger has one charge, `bookings` has zero rows, and one seat is `CONFIRMING` (leased).
- Then the provider is switched to `OK` and the client retries with the **same** key. It must get `201`. The charge count must **still be 1**: the API calls the provider with a key made from the hold, so the provider replays its first charge instead of taking the money again. One booking exists.

#### Test 3: the provider fails without capturing

```java
  @Test
  void chargesNothingWhenTheProviderFailsAndBooksOnTheRetry() throws Exception {
    api.provider().setMode(StandInMode.FAIL);

    assertThat(api.confirm(holdId, "fail-then-retry").status())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(api.provider().chargeCount()).isZero();

    api.provider().setMode(StandInMode.OK);
    assertThat(api.confirm(holdId, "fail-then-retry").status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }
```

Mode `FAIL`: the provider refuses and takes nothing. The confirm answers `503`, and `chargeCount()` (all charges, any hold) is zero. After switching to `OK`, the same key books it: `201` and one charge for this hold. A failure that took no money leaves the retry free to proceed.

#### Test 4: 50 confirms of one hold at once

```java
  @Test
  void booksOnceWhenFiftyConfirmsWithOneKeyRaceEachOther() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.confirm(holdId, "racing-confirms"));

    // Each racer either gets the booking or is told the first attempt is still paying
    // (409).
    assertThat(replies)
        .allSatisfy(
            reply -> assertThat(reply.status()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
    assertThat(
            replies.stream()
                .filter(reply -> reply.status() == HttpStatus.CREATED)
                .map(reply -> bookingIdIn(reply.body()))
                .distinct())
        .hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }
```

- 50 racing requests, one key. Each racer gets `201` (the booking) or `409` (the first attempt is still paying). `isIn(CREATED, CONFLICT)` says exactly that.
- Among the `201` replies, the booking ids collapse to one (`distinct()`, `hasSize(1)`).
- The database and the ledger agree: one booking row, one charge.

#### Test 5: a second key for a hold that is already booked

```java
  @Test
  void refusesASecondKeyForAHoldThatIsAlreadyBooked() throws Exception {
    api.confirm(holdId, "first-key");

    TestApi.Response secondKey = api.confirm(holdId, "second-key");

    assertThat(secondKey.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(secondKey.body()).contains("/problems/hold-not-active").contains("confirmed");
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }
```

A different key means a different intended action. The hold is already `confirmed`, so the answer is `409` with `/problems/hold-not-active` and the word `confirmed`. The charge count stays at 1.

#### Test 6: a retry after the key expired

```java
  @Test
  void doesNotChargeAgainWhenARetryArrivesAfterTheKeyExpired() throws Exception {
    api.confirm(holdId, "expired-key");
    api.clock().advance(ContractLimits.KEY_RETENTION.plusSeconds(1));

    TestApi.Response late = api.confirm(holdId, "expired-key");

    // The key is forgotten, so this is a new request; the hold is booked, so it is refused.
    assertThat(late.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }
```

After a confirm, the clock moves 24 hours and a second. The key is forgotten, so the same key is a new request. The hold is booked, so it is refused (`409`). The charge count is still 1 and the booking count is still 1: forgetting the key does not let money move again, because the hold's state protects it.

#### Test 7: taking over from a process that died while paying

```java
  @Test
  void takesOverAConfirmWhoseProcessDiedAfterLeasingTheSeat() throws Exception {
    // What a process leaves when it dies after step 1: the seat leased, the key in progress.
    long leaseUntil = TestApi.START.plus(DEAD_PROCESS_LEASE).toEpochMilli();
    api.execute(
        "UPDATE seats SET status = 'CONFIRMING', confirm_lease_until = ? WHERE hold_id = ?",
        leaseUntil,
        holdId);
    String fingerprint =
        Fingerprint.of("POST /v1/bookings", "{\"holdId\":\"" + holdId + "\"}").hex();
    api.execute(
        "INSERT INTO idempotency_keys (endpoint, idempotency_key, fingerprint, state, created_at,"
            + " expires_at) VALUES ('POST /v1/bookings', 'died-mid-payment', ?, 'IN_PROGRESS', ?, ?)",
        fingerprint,
        TestApi.START.toEpochMilli(),
        TestApi.START.plus(ContractLimits.KEY_RETENTION).toEpochMilli());

    assertThat(api.confirm(holdId, "died-mid-payment").status()).isEqualTo(HttpStatus.CONFLICT);
    api.clock().advance(DEAD_PROCESS_LEASE.plusSeconds(1));
    TestApi.Response takeover = api.confirm(holdId, "died-mid-payment");

    assertThat(takeover.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
  }
```

The test builds by hand what a dead process leaves behind, with two SQL statements through `api.execute`:
- **The seat leased:** `UPDATE seats SET status = 'CONFIRMING', confirm_lease_until = ? WHERE hold_id = ?`, the lease ending 2 seconds after the clock's start.
- **The key in progress:** an `INSERT INTO idempotency_keys ... 'IN_PROGRESS'` row for the key `died-mid-payment`. Its `fingerprint` must match what the real request will compute, so the test calls the real `Fingerprint.of("POST /v1/bookings", "{\"holdId\":\"" + holdId + "\"}").hex()` on the canonical body. The `created_at` and `expires_at` columns are the start and the start plus 24 hours.
- A confirm with that key now gets `409`: the lease is still live, another attempt is presumably paying.
- `api.clock().advance(DEAD_PROCESS_LEASE.plusSeconds(1))` makes the lease run out. The same confirm now takes over and answers `201`, with one charge for the hold. A lease that cannot expire would leave the seat stuck forever; this test proves it expires.

#### Test 8: holds that cannot be confirmed

```java
  @Test
  void refusesToConfirmAnExpiredACancelledOrAnUnknownHold() throws Exception {
    String cancelled = HoldRetryTest.holdIdIn(api.hold("A-2", "to-cancel").body());
    api.send("DELETE", "/v1/holds/" + cancelled, Map.of(), "");
    api.clock().advance(ContractLimits.HOLD_DURATION.plusSeconds(1));

    TestApi.Response expired = api.confirm(holdId, "too-late");
    TestApi.Response wasCancelled = api.confirm(cancelled, "after-cancel");
    TestApi.Response unknown = api.confirm("00000000-0000-4000-8000-000000000000", "unknown");

    assertThat(expired.body()).contains("/problems/hold-not-active").contains("expired");
    assertThat(wasCancelled.body()).contains("/problems/hold-not-active").contains("cancelled");
    assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.provider().chargeCount()).isZero();
  }
```

- A second hold (`A-2`) is cancelled with `DELETE`. Then the clock moves 301 seconds, so the original hold on `A-1` has expired.
- Confirming the expired hold gives `hold-not-active` with the word `expired`. Confirming the cancelled hold gives `hold-not-active` with `cancelled`. Confirming a UUID that exists nowhere gives `404`.
- The ledger shows no charge at all: `chargeCount()` is zero. A request that cannot succeed must not reach the provider.

#### `bookingIdIn`

```java
  private static String bookingIdIn(String body) {
    Matcher matcher = BOOKING_ID.matcher(body);
    assertThat(matcher.find()).as("a bookingId in %s", body).isTrue();
    return matcher.group(1);
  }
}
```

The private twin of `holdIdIn` for booking ids.

### `idempotency/FingerprintTest.java`

```java
package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FingerprintTest {

  // SHA-256 is 32 bytes, two hexadecimal characters each.
  private static final int SHA_256_HEX_LENGTH = 64;

  @Test
  void isTheSameForTheSameRequestAndDifferentOtherwise() {
    Fingerprint first = Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}");

    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}")).isEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-2\"}")).isNotEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/bookings", "{\"seat\":\"A-1\"}")).isNotEqualTo(first);
    assertThat(first.hex()).hasSize(SHA_256_HEX_LENGTH);
  }

  @Test
  void keepsTheEndpointAndTheBodyApart() {
    assertThat(Fingerprint.of("ab", "c")).isNotEqualTo(Fingerprint.of("a", "bc"));
  }

  @Test
  void cannotHoldSomethingThatIsNotADigest() {
    assertThatThrownBy(() -> new Fingerprint("abc")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- `SHA_256_HEX_LENGTH = 64`: SHA-256 is 32 bytes, and each byte is two hexadecimal characters.
- Test 1: the same endpoint and body give an **equal** `Fingerprint` (it is a record, so `equals` compares `hex`). A different seat gives a different one; a different endpoint with the same body gives a different one. `first.hex()` has 64 characters.
- Test 2: `Fingerprint.of("ab", "c")` must differ from `Fingerprint.of("a", "bc")`. If the endpoint and body were just glued together, both would be `abc`. The code puts a zero byte between them, which this test protects.
- Test 3: `new Fingerprint("abc")` throws `IllegalArgumentException`, because a fingerprint must be 64 lower-case hexadecimal characters.

### `idempotency/IdempotencyKeyTest.java`

```java
package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

  private static final int LONGEST_KEY = 64;

  @Test
  void readsTheQuotedStringTheDraftRequires() {
    assertThat(IdempotencyKey.fromHeader("\"8e03978e-40d5-43e8-bc93-6894a57f9324\""))
        .contains(new IdempotencyKey("8e03978e-40d5-43e8-bc93-6894a57f9324"));
  }

  @Test
  void refusesAnUnquotedEmptyTooLongOrEscapedKey() {
    assertThat(IdempotencyKey.fromHeader("8e03978e")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"" + "k".repeat(LONGEST_KEY + 1) + "\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"a\\\"b\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader(null)).isEmpty();
    assertThatThrownBy(() -> new IdempotencyKey("has space"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- Test 1: the header `"8e03978e-40d5-43e8-bc93-6894a57f9324"` (with its quotes) is read into an `IdempotencyKey` holding the text without quotes. `.contains(...)` on an `Optional` checks the content (`assertj.md`).
- Test 2 lists five refused headers, each giving an empty `Optional`: an unquoted key; an empty quoted string; 65 characters; a key with an escaped quote (`"a\"b"`); and `null`, a missing header. The last line proves that building a key by hand from `has space` throws `IllegalArgumentException`: the type cannot hold a bad value.

### `idempotency/IdempotencyStoreTest.java`

#### Setting up

```java
package io.plainround.seatwave.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

  private static final Instant NOW = TestApi.START;
  private static final int OLD_KEYS = 3;
  private static final int BATCH = 2;
  // Generous for a wait that takes milliseconds, so only a request that never blocks or never
  // finishes fails the test.
  private static final Duration LOCK_WAIT_LIMIT = Duration.ofSeconds(5);
  private final IdempotencyStore store = new IdempotencyStore();

```

- The imports add `await` (Awaitility), the JDBC types `PreparedStatement`, `ResultSet` and `SQLException`, `Duration`, and `CompletableFuture` with `TimeUnit` for the test that runs two requests on two threads.
- `NOW` is the test clock's start. `OLD_KEYS = 3` and `BATCH = 2` shape the purge test. `LOCK_WAIT_LIMIT` (5 seconds) bounds every wait of the two-request test.
- `store` is the real `IdempotencyStore`; it has no state, so one instance is enough.

#### Purge: only expired keys, and at most a batch

```java
  @Test
  void purgesOnlyExpiredKeysAndAtMostABatch() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection connection = database.connect()) {
      for (int index = 0; index < OLD_KEYS; index++) {
        connection.setAutoCommit(false);
        store.claim(connection, claim("old-" + index), NOW);
        connection.commit();
      }
      connection.setAutoCommit(false);
      store.claim(connection, claim("young"), NOW.plus(ContractLimits.KEY_RETENTION));
      connection.commit();
      connection.setAutoCommit(true);

      Instant later = NOW.plus(ContractLimits.KEY_RETENTION);
      assertThat(store.purgeExpired(connection, later, BATCH)).isEqualTo(BATCH);
      assertThat(store.purgeExpired(connection, later, BATCH)).isEqualTo(OLD_KEYS - BATCH);
      assertThat(store.count(connection)).isEqualTo(1);
    }
  }
```

- `Database.open("jdbc:h2:mem:keys-" + System.nanoTime())` opens a new in-memory database with a unique name and applies the migrations. The `try ( ... )` closes the database and the connection.
- Three keys (`old-0`, `old-1`, `old-2`) are claimed at `NOW`. Each claim is its own transaction: `setAutoCommit(false)` starts one, `claim(...)` inserts the row, `commit()` ends it. A fourth key, `young`, is claimed a full retention period later, so it expires a day after the others.
- `connection.setAutoCommit(true)` switches back, because `purgeExpired` is documented to run in auto-commit mode.
- At `later = NOW + retention`, the three old keys have expired and `young` has not. `purgeExpired(connection, later, 2)` deletes at most 2 and returns 2. The next call deletes the remaining old key and returns `OLD_KEYS - BATCH`, which is 1. `count` is 1: only `young` is left. A purge in batches never locks the whole table.

#### Complete once, replay, and a mismatched body

```java
  @Test
  void completesAKeyOnceAndReplaysWhatWasStored() throws Exception {
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      KeyClaim claim = claim("k");
      store.claim(connection, claim, NOW);
      StoredReply reply = new StoredReply(HttpStatus.CREATED, "/v1/holds/x", "{}");

      assertThat(store.complete(connection, claim, reply)).isTrue();
      assertThat(store.complete(connection, claim, reply)).isFalse();
      connection.commit();
      assertThat(store.claim(connection, claim, NOW)).isEqualTo(new ClaimResult.Replay(reply));
      assertThat(
              store.claim(
                  connection,
                  new KeyClaim("POST /v1/holds", claim.key(), Fingerprint.of("x", "y")),
                  NOW))
          .isEqualTo(new ClaimResult.BodyMismatch());
    }
  }
```

- `claim("k")` creates a claim: endpoint `POST /v1/holds`, the key, and a fingerprint made from the endpoint and the key text (the helper is at the end of the class). `store.claim(connection, claim, NOW)` inserts it in state `IN_PROGRESS`.
- `new StoredReply(HttpStatus.CREATED, "/v1/holds/x", "{}")` is the reply to store: status, location and body.
- `store.complete(connection, claim, reply)` returns **true** the first time. The second call returns **false**: the update has the condition `state = 'IN_PROGRESS'`, so only the first completion wins.
- After `commit()`, a second `claim` of the same key and fingerprint returns `new ClaimResult.Replay(reply)`: the stored reply comes back. `isEqualTo` works because `ClaimResult.Replay` and `StoredReply` are records.
- A claim of the same key with a different fingerprint (`Fingerprint.of("x", "y")`) returns `new ClaimResult.BodyMismatch()`: the key is being reused for another request.

#### Two requests with one expired key, forced to interleave

```java
  @Test
  void letsOneOfTwoRequestsWithOneExpiredKeyWorkAndReplaysItsReplyToTheOther() throws Exception {
    // The first connection is declared last so it closes first: a failed assertion then ends its
    // transaction and frees the second request instead of leaving it waiting on the row lock.
    try (Database database = Database.open("jdbc:h2:mem:keys-" + System.nanoTime());
        Connection watcher = database.connect();
        Connection second = database.connect();
        Connection first = database.connect()) {
      KeyClaim claim = claim("expired");
      first.setAutoCommit(false);
      store.claim(first, claim, NOW);
      store.complete(first, claim, new StoredReply(HttpStatus.CREATED, "/v1/holds/old", "{}"));
      first.commit();
      Instant later = NOW.plus(ContractLimits.KEY_RETENTION);
      StoredReply newReply = new StoredReply(HttpStatus.CREATED, "/v1/holds/new", "{\"new\":1}");

      // The first request takes the expired key over and keeps its transaction open.
      assertThat(store.claim(first, claim, later)).isEqualTo(new ClaimResult.Claimed());
      // The second reads the same expired row now, before the first commits: the interleaving
      // that used to leave it with an IN_PROGRESS key and nothing to complete.
      second.setAutoCommit(false);
      CompletableFuture<ClaimResult> secondResult = new CompletableFuture<>();
      Thread.ofPlatform()
          .start(() -> {
            try {
              secondResult.complete(store.claim(second, claim, later));
            } catch (SQLException failure) {
              secondResult.completeExceptionally(failure);
            }
          });
      // H2 spins rather than parks while it waits for a row lock, so the wait is read from H2.
      await().atMost(LOCK_WAIT_LIMIT).until(() -> secondResult.isDone() || isBlocked(watcher));
      store.complete(first, claim, newReply);
      first.commit();

      assertThat(secondResult.get(LOCK_WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(new ClaimResult.Replay(newReply));
      second.rollback();
      assertThat(store.count(first)).isEqualTo(1);
    }
  }
```

This test plays two requests that present the same expired key at the same moment, in a fixed order, with no luck involved.

- Three connections stand for three actors: `first` and `second` are the two requests, `watcher` only looks. The comment explains the order of the declarations: try-with-resources closes the last one first, so if an assertion fails, `first` ends its transaction and lets `second` go, instead of leaving it waiting forever.
- The setup claims the key at `NOW`, completes it with an old reply and commits. At `later = NOW + KEY_RETENTION` that key has expired.
- `store.claim(first, claim, later)` is the first request. It finds the expired row, takes it over and gets `Claimed`. Its transaction stays open, so it holds the row's lock.
- The second request runs on its own thread: `Thread.ofPlatform().start(...)` starts an ordinary operating-system thread. Its result goes into a `CompletableFuture`, a box that a thread fills once (`complete`, or `completeExceptionally` for a failure) and another thread reads with `get`. The second request reads the same expired row (the first has not committed) and then waits for the row lock.
- `await().atMost(LOCK_WAIT_LIMIT).until(...)` is Awaitility: it checks the condition again and again for up to 5 seconds. The comment says why it does not look at the thread's state: while H2 waits for a row lock, the thread keeps running and checking, so the JDK reports it as `RUNNABLE`. The test asks H2 instead (`isBlocked`, below). The condition is also true if the second request has already finished, so a version of the code that does not wait at all reaches the assertion and fails there, not in the wait.
- Only then does the first request complete its key with `newReply` and commit. That is the interleaving that once left the second request with `Resumed` and nothing to complete.
- `secondResult.get(5, SECONDS)` waits for the second request's answer. It must be `Replay(newReply)`: the second request does not act, it gets the first one's reply. `store.count(first)` is 1: still one key row. `LOCK_WAIT_LIMIT` is far longer than the milliseconds this takes, as its comment says, so only a request that never blocks or never finishes fails the test.

#### The helpers

```java
  private static boolean isBlocked(Connection watcher) throws SQLException {
    try (PreparedStatement query =
            watcher.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL");
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }

  private static KeyClaim claim(String key) {
    return new KeyClaim(
        "POST /v1/holds", new IdempotencyKey(key), Fingerprint.of("POST /v1/holds", key));
  }
}
```

- `isBlocked` asks H2 whether any session is waiting for another. `INFORMATION_SCHEMA.SESSIONS` is a view H2 keeps with one row per open connection; `BLOCKER_ID` is the id of the session it waits for, or `NULL`. A count above zero means someone is blocked.
- `claim(key)` builds the claim every test uses: the endpoint `POST /v1/holds`, the key, and a fingerprint made from the endpoint and the key text.

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the three booking and idempotency test classes:

```
./gradlew --offline cleanTest test --tests '*booking*' --tests '*idempotency*'
```

   Output: 25 `PASSED` lines: `HoldRetryTest` 9, `ConfirmPaymentTest` 8, `FingerprintTest` 3, `IdempotencyKeyTest` 2 and `IdempotencyStoreTest` 3.

2. Run one attack alone:

```
./gradlew --offline cleanTest test --tests '*HoldRetryTest.holdsOnceWhenOneHundredRetriesWithOneKeyRaceEachOther'
```

   Output: one `PASSED` line.

3. Count the proofs in the tests that read the database:

```
grep -c "api.count(" src/test/java/io/plainround/seatwave/api/booking/HoldRetryTest.java src/test/java/io/plainround/seatwave/api/booking/ConfirmPaymentTest.java
```

   Output: `HoldRetryTest.java:15` and `ConfirmPaymentTest.java:6`, each with its folder in front (the `count(` calls that read the database).

4. Turn the proof off, in a scratch copy: in `HoldRetryTest.holdsOnceWhenOneHundredRetriesWithOneKeyRaceEachOther`, change `isEqualTo(1)` of the `HELD` count (line 101) to `isEqualTo(2)` and run that test. It fails at `HoldRetryTest.java:101`, and the result file `build/test-results/test/TEST-io.plainround.seatwave.api.booking.HoldRetryTest.xml` holds `expected: 2L` and ` but was: 1L`: the database holds one held seat. Put the line back afterwards.

## Mistakes people make

- **Proving "no double booking" from replies.** A reply can lie. These tests count rows.
- **Sleeping to wait for a race.** `AllAtOnce` uses a gate so the requests really leave together, and Awaitility waits for a condition.
- **Testing only the happy retry.** The attacks include a different body, a lost reply, an expired key, a crash and a dead process.
- **Letting the retry change the first answer.** Every replay must return the first reply, including a refusal.
- **Holding a transaction open across a payment call.** The confirm uses a lease and two short transactions instead, and the tests check the in-between state (`CONFIRMING`).
- **Generating the fingerprint by a different rule in a test.** Test 7 of the confirm class calls the real `Fingerprint.of`, so the hand-made row matches what the code computes.

## Check yourself

1. *How does the test prove that 100 racing retries held one seat?* It reads the database: one row with status `HELD` and one row in `idempotency_keys`.
2. *What does the server answer when a key is reused with a different body?* `422` with the problem `/problems/idempotency-key-reused`; nothing is held.
3. *How does `FailingKeyWrite` simulate a crash?* It is a trigger that throws before the key row is updated, so the transaction rolls back the seat change with it.
4. *Why does the charge count stay at 1 after a provider timeout and a retry?* The API calls the provider with a key made from the hold, so the provider replays the first charge.
5. *What happens to a key after 24 hours?* It expires and is forgotten; the same key then starts a new request.

## Further reading

- The Idempotency-Key HTTP header (IETF draft): <https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/>
- HTTP Semantics, RFC 9110 (status codes 409, 422, 503): <https://www.rfc-editor.org/rfc/rfc9110>
- H2 triggers (`CREATE TRIGGER`): <https://h2database.com/html/commands.html#create_trigger>
- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
- SHA-256 in FIPS 180-4: <https://csrc.nist.gov/pubs/fips/180-4/upd1/final>
