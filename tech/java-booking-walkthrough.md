# The `booking` package, line by line (holds, confirms, cancels)

## What it is

The `booking` package is the part of SeatWave that changes seats. It holds a seat for a fan, turns a hold into a paid booking, and releases a hold. It lives in `src/main/java/io/plainround/seatwave/api/booking/` and has thirteen files.

A **package** is a folder of Java files that belong together. A **hold** is a promise: "this seat is yours for 300 seconds, nobody else may take it". A **booking** is a paid seat. A **cancel** gives a hold back. The package knows nothing about HTTP, status codes or JSON. It takes plain Java objects in and gives plain Java objects out. The package `http` turns them into replies.

Four ideas appear in nearly every file, so here they are once.

- A **record** is a small class that only carries data. `record Hold(HoldId holdId, long eventId)` makes a class with those fields, a constructor, getters named after the fields (`hold.eventId()`), `equals`, `hashCode` and `toString`. A record cannot be changed after it is made.
- A **compact constructor** is the block `public Hold { ... }` that has no parentheses. It runs before the fields are stored. The code checks the fields there, so a bad record can never exist.
- A **sealed interface** lists every type that may implement it. `sealed interface HoldOutcome` with three records inside means "a hold attempt ends in exactly one of these three things". The compiler then checks that a `switch` handles all three.
- A **transaction** is a group of database changes that either all happen or none happen. The database promises that nobody sees half of it, and that a crash in the middle undoes all of it.

## Why this repository uses it

The lesson of Module 7 is that a client may send the same request twice (a lost reply, a nervous retry) and still must not get two holds or two charges. Every rule that guarantees this lives in this package: one conditional `UPDATE` makes sure only one request can take a seat, one transaction writes the seat change together with the key's stored reply, and a lease plus a provider key make sure a seat is charged once. The retry storm and the tests prove it.

## Where it appears

| File | What it does |
|---|---|
| `HoldState.java` | The five states a hold can be in, as the client sees them. |
| `HoldId.java` | The identifier of a hold: a lowercase UUID, checked when made. |
| `HoldCommand.java` | A validated request to hold one seat. |
| `Hold.java` | A hold as read from the database. |
| `HoldOutcome.java` | The three final results of a hold attempt. |
| `ConfirmCommand.java` | A validated request to confirm a hold. |
| `ConfirmOutcome.java` | The three final results of a confirm. |
| `Booking.java` | A paid seat. |
| `CancelOutcome.java` | The three results of a cancel. |
| `Refusal.java` | The three answers that are not final and are never stored under a key. |
| `KeyedReply.java` | A reply plus whether it was made now or replayed. |
| `SeatLedger.java` | All the SQL: every statement that changes or reads `seats` and `bookings`. Package-private. |
| `HoldService.java` | Hold, read and cancel, with the transaction around each hold. |
| `ConfirmService.java` | The three-step confirm that calls the payment provider. |

## How it works underneath

### The states of a seat

The `seats` table has a column `status`. It holds one of four words: `AVAILABLE`, `HELD`, `CONFIRMING`, `CONFIRMED`. The migration `V2__idempotency_and_payment.sql` makes the database refuse any other word (see `h2-sql-and-migrations.md`).

The client sees five states, the enum `HoldState`: `HELD`, `CONFIRMING`, `CONFIRMED`, `CANCELLED`, `EXPIRED`. Two of them are not stored. They are worked out when a row is read:

| Row in `seats` | Time | Hold state shown |
|---|---|---|
| `status = 'HELD'` | `hold_expires_at` is after now | `HELD` |
| `status = 'HELD'` | `hold_expires_at` is now or earlier | `EXPIRED` |
| `status = 'CONFIRMING'` | any | `CONFIRMING` |
| `status = 'CONFIRMED'` | any | `CONFIRMED` |
| `status = 'AVAILABLE'`, `hold_id` still set | any | `CANCELLED` |

So an expired hold needs no job that cleans it up: the clock decides. This is called **lazy expiry**. A seat whose hold expired is taken by the next hold attempt because the hold `UPDATE` accepts `HELD` rows whose time has passed.

The moves between states:

```text
AVAILABLE --hold--------------------> HELD
HELD (expired) --hold by anyone-----> HELD (new hold id)
HELD --cancel-----------------------> AVAILABLE (hold id kept, shown as CANCELLED)
HELD (live) --confirm step 1--------> CONFIRMING (with a lease)
CONFIRMING (lease over) --confirm---> CONFIRMING (new lease; another attempt took over)
CONFIRMING --confirm step 3---------> CONFIRMED (and a row in bookings)
```

### Why one conditional UPDATE is the whole trick

Two fans press "hold" for the same seat at the same moment. Two requests run two `UPDATE` statements. The statement says: change this row to `HELD`, but only where it is still `AVAILABLE`. The database locks the row while it changes it. The second statement waits for the lock, and when it gets the lock it checks the condition again. The row is now `HELD`, the condition is false, and the statement reports **0 rows changed**. The code reads that number: 1 means "you got it", 0 means "someone else did". No Java `if` can be raced, because the check and the change are one step inside the database.

I checked this with H2 2.5.252 in a scratch program (two connections, auto-commit off). The first connection ran the conditional update and printed 1. The second ran the same update at the same time and printed `y waiting: true`. After the first connection committed, the second one finished and printed 0.

One more fact from that experiment: the second statement, which changed nothing, still held the row lock until its own transaction ended. The first connection, trying to change the same row again, failed with the lock-timeout error `50200` after the configured `LOCK_TIMEOUT` of 1,000 ms. This is why the code in this package always ends a transaction at once, with `commit()` or `rollback()`, on every path.

### The transaction boundaries, in one place

JDBC is Java's standard way to talk to a database. A `Connection` starts in **auto-commit** mode: every statement is its own transaction. Calling `connection.setAutoCommit(false)` turns that off: the transaction starts at the next statement and lasts until `commit()` or `rollback()`. `Database.connect()` always opens a new connection with auto-commit on.

| Method | Transaction begins | Transaction ends |
|---|---|---|
| `HoldService.hold` | `setAutoCommit(false)` before the key claim | `commit()` after the key's reply is stored; `rollback()` on a replay, a body mismatch or any failure |
| `HoldService.find` | none (one `SELECT`, auto-commit) | the statement itself |
| `HoldService.cancel` | none (the `UPDATE` is its own transaction, the `SELECT` another) | each statement itself |
| `ConfirmService.start` (step 1) | `setAutoCommit(false)` before the key claim | `commit()` after the lease is taken or after a refusal is stored; `rollback()` otherwise |
| the payment call (step 2) | none: no transaction is open and no connection is held | not applicable |
| `ConfirmService.finish` (step 3) | `setAutoCommit(false)` before reading the hold | `commit()` after booking, seat and key reply are written; `rollback()` otherwise |
| `ConfirmService.replayStored` | `setAutoCommit(false)` | always `rollback()` (it only reads) |
| `ConfirmService.endLease` | none (one `UPDATE`, auto-commit) | the statement itself |

The idempotency key row is written inside the same transaction as the seat change. That is the subject of `java-idempotency-walkthrough.md`; here you will see only where `keys.claim` and `keys.complete` are called.

## Walkthrough of this repository's use

### `HoldState.java`

```java
package io.plainround.seatwave.api.booking;

/** Where a hold is in its life. The contract's Hold.state lists the same five, in lower case. */
public enum HoldState {
  /** The seat is held and the hold has not expired. */
  HELD,
  /** A confirm owns the seat while it waits for the payment provider. */
  CONFIRMING,
  /** The hold became a booking. */
  CONFIRMED,
  /** The client released the hold. */
  CANCELLED,
  /** The hold's 300 seconds passed without a confirm. */
  EXPIRED
}
```

- `package io.plainround.seatwave.api.booking;` (line 1 of every file) says which package the file belongs to. It must match the folder.
- `public enum HoldState` makes an **enum**: a type that has a fixed list of values. A variable of this type can only be one of the five names. `HELD` and the others are the values; the `/** ... */` lines above each value are Javadoc, documentation that tools turn into pages.
- `CONFIRMING` is the state of a seat while a confirm waits for the payment provider.
- `EXPIRED` is never stored: the doc comment says the 300 seconds passed. The code finds it out by comparing times (see `SeatLedger.stateOf` below).

### `HoldId.java`

```java
package io.plainround.seatwave.api.booking;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identifier of a hold: a random UUID, lower case. It works as a capability (whoever knows it
 * can confirm or cancel the hold), so it is never shown to anyone but the client that made it.
 *
 * @param value the UUID in its canonical lowercase text form
 */
public record HoldId(String value) {

  /** The contract's Uuid schema. */
  public static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Checks the value. */
  public HoldId {
    if (!UUID_TEXT.matcher(Objects.requireNonNull(value, "value")).matches()) {
      throw new IllegalArgumentException("a hold id is a lowercase UUID");
    }
  }

```

- Lines 3 to 5 import three JDK classes: `Objects` (null checks), `UUID` (random identifiers) and `Pattern` (regular expressions). An **import** lets the file use a short class name.
- The Javadoc says a hold id works as a **capability**: whoever knows it can confirm or cancel the hold. Anyone who guesses it could cancel someone else's hold, so it must be unguessable. That is why a random UUID is used.
- `public record HoldId(String value)` is a record with one field. It wraps a plain text value in its own type, so a method that needs a hold id cannot be given a customer name by mistake.
- `UUID_TEXT` is a **regular expression**: a pattern for text. `[0-9a-f]{8}` means "exactly eight characters, each a digit or a letter from a to f". The five groups joined by hyphens are the shape of a UUID such as `66647fd5-c8e1-4bcd-aea6-807de6fb550a`. Only lowercase is allowed, the same as the contract's `Uuid` schema.
- The compact constructor (`public HoldId { ... }`) runs `UUID_TEXT.matcher(value).matches()`. `matches()` is true only when the whole text fits the pattern. `Objects.requireNonNull(value, "value")` throws a `NullPointerException` with the message `value` when the argument is null. If the text does not fit, it throws `IllegalArgumentException`, Java's standard "you passed a bad argument" error. So a `HoldId` that exists is always well formed.

```java
  /**
   * Makes a new identifier.
   *
   * @return a random identifier; {@link UUID#randomUUID()} draws from a cryptographically strong
   *     generator, so it cannot be guessed
   */
  public static HoldId random() {
    return new HoldId(UUID.randomUUID().toString());
  }
}
```

- `HoldId.random()` is a **static method**: you call it on the class, not on an object. `UUID.randomUUID()` draws 122 random bits from the JDK's secure random generator, so the id cannot be predicted. `.toString()` gives the lowercase text with hyphens, which passes the check above.

### `HoldCommand.java`

```java
package io.plainround.seatwave.api.booking;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated request to hold one seat. It cannot exist with a bad field, so code that receives one
 * never checks again.
 *
 * @param eventId the event, 1 or more
 * @param seatLabel the seat, a section letter A to T, a hyphen and a number, for example A-7
 * @param customer who holds it, 1 to 64 of A-Z a-z 0-9 _ -
 */
public record HoldCommand(long eventId, String seatLabel, String customer) {

  /** The seat label rule of the contract's SeatLabel schema. */
  public static final Pattern SEAT_LABEL = Pattern.compile("[A-T]-[1-9][0-9]{0,3}");

  /** The customer rule of the contract's HoldRequest schema. */
  public static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

```

- A **command** is a request to do something, already checked. The Javadoc states the rule: "it cannot exist with a bad field, so code that receives one never checks again". The checks happen once, at the edge.
- `public record HoldCommand(long eventId, String seatLabel, String customer)` has three fields. `long` is a 64-bit whole number.
- `SEAT_LABEL` is `[A-T]-[1-9][0-9]{0,3}`: one section letter from A to T, a hyphen, a number that does not start with 0, and up to three more digits. So `A-7` and `T-1000` pass; `A-0`, `U-1` and `a-7` do not. It mirrors the `SeatLabel` schema in `openapi/seatwave-api.yaml`.
- `CUSTOMER` is `[A-Za-z0-9_-]{1,64}`: one to 64 letters, digits, underscores or hyphens. It mirrors the `customer` property of the `HoldRequest` schema.
- `public static final` means one shared constant that cannot be reassigned. A `Pattern` is compiled once here, not on every request.

```java
  /** Checks the fields. */
  public HoldCommand {
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more");
    }
    if (!SEAT_LABEL.matcher(Objects.requireNonNull(seatLabel, "seatLabel")).matches()) {
      throw new IllegalArgumentException("seat must look like A-7");
    }
    if (!CUSTOMER.matcher(Objects.requireNonNull(customer, "customer")).matches()) {
      throw new IllegalArgumentException("customer must be 1 to 64 of A-Z a-z 0-9 _ -");
    }
  }
}
```

- The first check: `eventId < 1` throws. Event ids start at 1.
- The second check runs the seat pattern. `Objects.requireNonNull(seatLabel, "seatLabel")` returns its argument when it is not null, so it can sit inside the call. A null seat label gives a `NullPointerException`; a bad shape gives `IllegalArgumentException("seat must look like A-7")`.
- The third check does the same for the customer.
- These messages never reach the client. The `http` package checks the request body against the same rules first and builds the 400 reply with the list of bad fields. The constructor is the last line of defense: it makes sure the rule still holds if another caller forgets.

### `Hold.java`

```java
package io.plainround.seatwave.api.booking;

import java.time.Instant;
import java.util.Objects;

/**
 * A hold as the database holds it now.
 *
 * @param holdId its identifier
 * @param eventId the event
 * @param seatLabel the seat
 * @param state its state at the time it was read
 * @param expiresAt when the hold ends unless it is confirmed
 */
public record Hold(
    HoldId holdId, long eventId, String seatLabel, HoldState state, Instant expiresAt) {

  /** Checks the fields. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
```

- `Hold` is what the database says about a hold right now: its id, event, seat, state and end time. `Instant` is the JDK's type for a point in time (UTC, with nanosecond precision).
- The compact constructor refuses nulls for the four object fields. `eventId` is a `long`, a primitive, which can never be null, so it has no check.
- The Javadoc says `state` is "its state at the time it was read". A `Hold` is a snapshot. Reading it again a minute later may give `EXPIRED`.

### `HoldOutcome.java`

```java
package io.plainround.seatwave.api.booking;

/** The business result of one hold attempt. Each is final, so each is stored under the key. */
public sealed interface HoldOutcome {

  /**
   * The seat is now held by this request.
   *
   * @param hold the new hold
   */
  record Held(Hold hold) implements HoldOutcome {}

  /** The seat exists but someone else holds it or bought it. */
  record SeatTaken() implements HoldOutcome {}

  /** The event or the seat does not exist. */
  record SeatNotFound() implements HoldOutcome {}
}
```

- `sealed interface HoldOutcome` is the type of every possible result of one hold attempt. Its three records are nested inside it and each says `implements HoldOutcome`.
- `Held(Hold hold)` carries the new hold.
- `SeatTaken()` has no fields. It means: the seat row exists but someone else holds or bought it. The contract answers 409.
- `SeatNotFound()` has no fields. It means: no such event or seat. The contract answers 404.
- The comment on the interface says "each is final, so each is stored under the key". All three outcomes are final answers: repeating the request gives the same answer, so the idempotency key stores the rendered reply for all three, including the two failures.

### `ConfirmCommand.java`

```java
package io.plainround.seatwave.api.booking;

import java.util.Objects;

/**
 * A validated request to confirm a hold.
 *
 * @param holdId the hold to pay for
 */
public record ConfirmCommand(HoldId holdId) {

  /** Checks the field. */
  public ConfirmCommand {
    Objects.requireNonNull(holdId, "holdId");
  }
}
```

- The only field is the hold to pay for. The compact constructor refuses null. The id is already checked because `HoldId` checks itself.

### `ConfirmOutcome.java`

```java
package io.plainround.seatwave.api.booking;

/** The business result of one confirm. Each is final, so each is stored under the key. */
public sealed interface ConfirmOutcome {

  /**
   * The seat is paid for and booked.
   *
   * @param booking the booking
   */
  record Booked(Booking booking) implements ConfirmOutcome {}

  /** No hold has this identifier. */
  record HoldNotFound() implements ConfirmOutcome {}

  /**
   * The hold can no longer be confirmed.
   *
   * @param state why: CANCELLED, EXPIRED or CONFIRMED (by another request)
   */
  record HoldNotActive(HoldState state) implements ConfirmOutcome {}
}
```

- `Booked(Booking booking)` is the success: the seat is paid and booked (the contract answers 201).
- `HoldNotFound()` means no hold has this id (404).
- `HoldNotActive(HoldState state)` means the hold cannot be confirmed any more. The `state` says why: `CANCELLED`, `EXPIRED`, or `CONFIRMED` by another request (409). The message in the reply is built from this state, as in the real answer `"the hold is cancelled"`.
- Like `HoldOutcome`, each of these is final and is stored under the key.

### `Booking.java`

```java
package io.plainround.seatwave.api.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A paid seat.
 *
 * @param bookingId its identifier, a lowercase UUID
 * @param holdId the hold it came from
 * @param eventId the event
 * @param seatLabel the seat
 * @param paymentId the provider's identifier of the one charge
 * @param amount what was charged, with two decimals
 * @param currency the ISO 4217 code
 * @param confirmedAt when the booking was written
 */
public record Booking(
    String bookingId,
    HoldId holdId,
    long eventId,
    String seatLabel,
    String paymentId,
    BigDecimal amount,
    String currency,
    Instant confirmedAt) {

```

- The Javadoc lists each field with `@param`. The record has eight fields: the booking id (a UUID string), the hold it came from, the event, the seat label, the provider's payment id, the amount, the currency, and the time of confirming.
- `BigDecimal amount` is an exact decimal number. Money is never kept as `double`, because floating-point numbers cannot store 0.1 exactly. The database column is `DECIMAL(12, 2)` for the same reason.

```java
  /** Checks the fields. */
  public Booking {
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
  }
}
```

- The compact constructor refuses a null in every object field. `eventId` is a primitive and needs no check.

### `CancelOutcome.java`

```java
package io.plainround.seatwave.api.booking;

/** The result of releasing a hold. Cancel is idempotent by design, so it needs no key. */
public sealed interface CancelOutcome {

  /** The hold is released, now or by an earlier request; the seat is available. */
  record Released() implements CancelOutcome {}

  /** No hold has this identifier. */
  record HoldNotFound() implements CancelOutcome {}

  /**
   * The hold cannot be released any more.
   *
   * @param state CONFIRMING (a payment is in flight) or CONFIRMED
   */
  record TooLate(HoldState state) implements CancelOutcome {}
}
```

- A cancel has no idempotency key. The comment says why: "Cancel is idempotent by design". Repeating a cancel leaves the seat in the same state and gives the same answer, so there is nothing to protect.
- `Released()`: the hold is released, now or by an earlier request. The contract answers 204 in both cases.
- `HoldNotFound()`: no hold has this id (404).
- `TooLate(HoldState state)`: the hold is `CONFIRMING` (a payment is in flight) or `CONFIRMED`. The contract answers 409 with the type `hold-not-cancellable`.

### `Refusal.java`

```java
package io.plainround.seatwave.api.booking;

/**
 * Why a keyed write was refused before or without a final outcome. None is stored under the key:
 * the client may correct the request (a new key) or retry it (the same key).
 */
public sealed interface Refusal {

  /** The key was used with a different body (422 in the contract). */
  record KeyReusedWithOtherBody() implements Refusal {}

  /** Another attempt is paying for this hold right now (409): retry after a moment. */
  record ConfirmInProgress() implements Refusal {}

  /** The payment provider did not answer in time or failed (503): retry with the same key. */
  record PaymentUnavailable() implements Refusal {}
}
```

- A **refusal** is not a final outcome. It is returned in the error side of a `Result` (see `result4j.md`) and nothing is stored under the key, so the client may fix the request or retry.
- `KeyReusedWithOtherBody()`: the same key came with a different body. The contract answers 422. The client must use a new key.
- `ConfirmInProgress()`: another attempt holds a live lease and is paying right now. The contract answers 409. The client retries after a moment.
- `PaymentUnavailable()`: the provider did not answer in time or failed. The contract answers 503. The client retries with the same key.

### `KeyedReply.java`

```java
package io.plainround.seatwave.api.booking;

import io.plainround.seatwave.api.idempotency.StoredReply;
import java.util.Objects;

/**
 * The response to a keyed write, and whether it was produced now or replayed from the key store.
 *
 * @param reply the response
 * @param source where it came from
 */
public record KeyedReply(StoredReply reply, Source source) {

  /** Checks the fields. */
  public KeyedReply {
    Objects.requireNonNull(reply, "reply");
    Objects.requireNonNull(source, "source");
  }

  /** Where a keyed response came from. */
  public enum Source {
    /** This request did the work. */
    FIRST,
    /** An earlier request with the same key did the work; this is its stored response. */
    REPLAY
  }
}
```

- `KeyedReply` is the successful return of `hold` and `confirm`. `StoredReply` (from the `idempotency` package) is the status, location and body to send.
- The nested enum `Source` has two values. `FIRST`: this request did the work. `REPLAY`: an earlier request with the same key did it, and this is its stored answer. The `http` layer uses the source to count metrics (`first`, `replayed`).
- The compact constructor refuses nulls for both fields.

### `SeatLedger.java`

`SeatLedger` is the only class in the repository that writes SQL for seats and bookings. It is `final class SeatLedger` without `public`: **package-private**, so only classes in `booking` can use it. It keeps no state, and every method takes the caller's `Connection`, so the caller decides which transaction the statement belongs to.

```java
package io.plainround.seatwave.api.booking;

import io.plainround.seatwave.api.ContractLimits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * The SQL of holds, confirms and cancels, on the {@code seats} and {@code bookings} tables. Every
 * state change is one conditional {@code UPDATE} whose {@code WHERE} clause states the only states
 * it may start from, so two racing requests cannot both see "1 row updated" (Module 1's rule).
 *
 * <p>Thread-safe: it holds no state. Every method runs in the caller's transaction.
 */
final class SeatLedger {
```

- Lines 3 to 9 import the JDBC types. `Connection` is one conversation with the database. `PreparedStatement` is an SQL statement with `?` holes that are filled in later. `ResultSet` is the table of rows a `SELECT` returns. `SQLException` is the checked exception for any database error.
- The class comment states the rule of the whole class: every state change is one conditional `UPDATE` whose `WHERE` clause lists the only states it may start from. Two racing requests cannot both see "1 row updated".

```java
  /**
   * The state of the seat row that carries a hold.
   *
   * @param eventId the event
   * @param seatLabel the seat
   * @param customer who made the hold
   * @param state the hold's state at {@code now}
   * @param expiresAt when the hold ends
   */
  record HoldRow(
      long eventId, String seatLabel, String customer, HoldState state, Instant expiresAt) {}
```

- `HoldRow` is a small record nested in the class. It carries what a query about one hold returns: event, seat, customer, state and end time. The `state` is already worked out for `now`.

#### `holdSeat`

```java
  boolean holdSeat(Connection connection, HoldCommand command, HoldId holdId, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE seats
            SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?,
                confirm_lease_until = NULL
            WHERE event_id = ? AND seat_label = ?
              AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
            """)) {
      update.setString(1, holdId.value());
      update.setString(2, command.customer());
      update.setLong(3, now.plus(ContractLimits.HOLD_DURATION).toEpochMilli());
      update.setLong(4, command.eventId());
      update.setString(5, command.seatLabel());
      update.setLong(6, now.toEpochMilli());
      // The row lock makes the check and the write one step: a racer waits, then matches 0 rows.
      return update.executeUpdate() == 1;
    }
  }
```

- `prepareStatement` with a text block (the `"""` lines) sends the SQL to the database once with `?` holes. A **text block** is a Java string that spans lines. The leading spaces common to all lines are removed.
- `try (PreparedStatement update = ...)` is **try-with-resources**: the statement is closed automatically when the block ends, even after an error.
- `UPDATE seats SET status = 'HELD', hold_id = ?, hold_customer = ?, hold_expires_at = ?, confirm_lease_until = NULL`: the new values. The seat becomes `HELD`, gets the new hold id, the customer and the end time, and any old confirm lease is cleared (`NULL` means "no value").
- `WHERE event_id = ? AND seat_label = ?`: which seat. These two columns together are the table's primary key, so at most one row matches.
- `AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))`: the only states the seat may start from. It is free, or it is held but the hold has run out. A `CONFIRMING` or `CONFIRMED` row, or a live hold, matches nothing, so the statement changes 0 rows.
- The `set...` calls fill the holes by position. `setString(1, ...)` fills the first `?`. Position 1 is the hold id, 2 the customer, 3 the end time, 4 the event, 5 the seat, 6 the current time. Times are stored as **epoch milliseconds**: the number of milliseconds since 1 January 1970 UTC. `now.plus(ContractLimits.HOLD_DURATION).toEpochMilli()` is "now plus 300 seconds" as such a number.
- `executeUpdate()` runs the statement and returns how many rows changed. `== 1` is true for the winner. The comment above it says what makes it safe: the row lock makes the check and the write one step; a racer waits, then matches 0 rows.

#### `seatExists`

```java
  boolean seatExists(Connection connection, long eventId, String seatLabel) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM seats WHERE event_id = ? AND seat_label = ?")) {
      query.setLong(1, eventId);
      query.setString(2, seatLabel);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
```

- `SELECT 1 FROM seats WHERE event_id = ? AND seat_label = ?` asks only whether the row exists. `rows.next()` moves to the first row and returns true if there is one. This second query runs only after a failed hold, to tell "taken" (409) from "no such seat" (404).

#### `findHold`

```java
  Optional<HoldRow> findHold(Connection connection, HoldId holdId, Instant now)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT event_id, seat_label, hold_customer, status, hold_expires_at,"
                + " confirm_lease_until FROM seats WHERE hold_id = ?")) {
      query.setString(1, holdId.value());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochMilli(row.getLong("hold_expires_at"));
        return Optional.of(
            new HoldRow(
                row.getLong("event_id"),
                row.getString("seat_label"),
                row.getString("hold_customer"),
                stateOf(row.getString("status"), expiresAt, now),
                expiresAt));
      }
    }
  }
```

- The query finds the seat row that carries this hold id. `hold_id` is not a primary key, but each hold id was made random, so it matches at most one row. (It has no index; with 20,000 seats the scan is small. A larger table would need an index.)
- The string is split in two with `+` because it is long. The `confirm_lease_until` column is selected but not read: only `HoldRow`'s five fields are used.
- `if (!row.next()) return Optional.empty();` means "no such hold". `Optional` is the JDK's box that holds a value or nothing.
- `Instant.ofEpochMilli(row.getLong("hold_expires_at"))` turns the stored number back into a time.
- The state is worked out by `stateOf(status, expiresAt, now)` at the bottom of the file.

#### `leaseForConfirm`

```java
  boolean leaseForConfirm(Connection connection, HoldId holdId, Instant now, Instant leaseUntil)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE seats SET status = 'CONFIRMING', confirm_lease_until = ?
            WHERE hold_id = ?
              AND ((status = 'HELD' AND hold_expires_at > ?)
                OR (status = 'CONFIRMING' AND confirm_lease_until <= ?))
            """)) {
      update.setLong(1, leaseUntil.toEpochMilli());
      update.setString(2, holdId.value());
      update.setLong(3, now.toEpochMilli());
      // An attempt whose lease ran out (its process died, or its payment call failed) is taken
      // over; the provider's key, made from the hold, keeps the takeover from charging twice.
      update.setLong(4, now.toEpochMilli());
      return update.executeUpdate() == 1;
    }
  }
```

- This is the first step of a confirm: move the seat from `HELD` to `CONFIRMING` and write a **lease**. A lease is a time limit on an ownership: "this attempt owns the seat until this moment".
- `SET status = 'CONFIRMING', confirm_lease_until = ?` stores the lease end (position 1).
- `WHERE hold_id = ?` finds the hold (position 2).
- `AND ((status = 'HELD' AND hold_expires_at > ?) OR (status = 'CONFIRMING' AND confirm_lease_until <= ?))` has two ways to win. A live, unexpired `HELD` seat is taken. A `CONFIRMING` seat whose lease has run out is taken over. A `CONFIRMING` seat with a live lease matches nothing, so a second attempt cannot pay for the seat while the first one is still paying. Positions 3 and 4 are both "now".
- The comment explains the takeover: an attempt whose process died, or whose payment call failed, left a lease behind. Another attempt may continue. The provider's key, made from the hold, keeps the takeover from charging twice.

#### `markConfirmed`

```java
  boolean markConfirmed(Connection connection, HoldId holdId, String bookingId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE seats SET status = 'CONFIRMED', booking_id = ?, confirm_lease_until = NULL"
                + " WHERE hold_id = ? AND status = 'CONFIRMING'")) {
      update.setString(1, bookingId);
      update.setString(2, holdId.value());
      return update.executeUpdate() == 1;
    }
  }
```

- Step 3 of a confirm. `SET status = 'CONFIRMED', booking_id = ?, confirm_lease_until = NULL` finishes the seat. `WHERE hold_id = ? AND status = 'CONFIRMING'` allows it only from `CONFIRMING`. If a cancel or another attempt changed the seat, this matches 0 rows and the method returns false.

#### `insertBooking`

```java
  void insertBooking(Connection connection, Booking booking, String customer) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO bookings (booking_id, event_id, seat_label, customer, confirmed_at,"
                + " hold_id, payment_id, amount, currency) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, booking.bookingId());
      insert.setLong(2, booking.eventId());
      insert.setString(3, booking.seatLabel());
      insert.setString(4, customer);
      insert.setLong(5, booking.confirmedAt().toEpochMilli());
      insert.setString(6, booking.holdId().value());
      insert.setString(7, booking.paymentId());
      insert.setBigDecimal(8, booking.amount());
      insert.setString(9, booking.currency());
      // one_booking_per_seat refuses a second booking even if a bug reached this line twice.
      insert.executeUpdate();
    }
  }
```

- `INSERT INTO bookings (...) VALUES (?, ?, ...)` adds one row. There are nine columns and nine `?`. The nine `set...` calls fill them in the same order: booking id, event, seat, customer, time, hold id, payment id, amount (`setBigDecimal`, exact), currency.
- The comment says why a plain insert is enough. The unique constraint `one_booking_per_seat` on `(event_id, seat_label)` makes the database refuse a second booking for a seat, even if a bug reached this line twice. The failure would be an `SQLException`.

#### `releaseLease`

```java
  void releaseLease(Connection connection, HoldId holdId, Instant now) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE seats SET confirm_lease_until = ? WHERE hold_id = ? AND status = 'CONFIRMING'")) {
      // The seat stays CONFIRMING: the charge may have happened, so it is never resold blindly.
      // Ending the lease now lets the client's retry carry on at once.
      update.setLong(1, now.toEpochMilli());
      update.setString(2, holdId.value());
      update.executeUpdate();
    }
  }
```

- Used when the provider did not confirm. `SET confirm_lease_until = ?` with `now` ends the lease at once. The seat stays `CONFIRMING` because the statement does not touch `status`.
- The first comment gives the reason: the charge may have happened, so the seat is never put back on sale blindly. The second comment: with the lease over, the client's retry can take over immediately. `executeUpdate()` is called without using its result; zero rows is acceptable.

#### `cancel`

```java
  boolean cancel(Connection connection, HoldId holdId, Instant now) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE seats SET status = 'AVAILABLE', hold_expires_at = ?"
                + " WHERE hold_id = ? AND status = 'HELD'")) {
      // hold_id stays on the row, so a repeated cancel finds it and answers the same way.
      update.setLong(1, now.toEpochMilli());
      update.setString(2, holdId.value());
      return update.executeUpdate() == 1;
    }
  }
```

- `SET status = 'AVAILABLE', hold_expires_at = ?` frees the seat and sets the end time to now. `WHERE hold_id = ? AND status = 'HELD'` allows it only from `HELD`. This releases every `HELD` row, expired or not, which is why `HoldService.cancel` can treat `HELD` and `EXPIRED` after a failed cancel as impossible.
- The comment: `hold_id` stays on the row. A repeated cancel finds the row (`status` is now `AVAILABLE`, `hold_id` is still set) and `stateOf` reports `CANCELLED`, so the second cancel answers the same way as the first.

#### `findBooking`

```java
  Optional<Booking> findBooking(Connection connection, String bookingId) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT booking_id, hold_id, event_id, seat_label, payment_id, amount, currency,"
                + " confirmed_at FROM bookings WHERE booking_id = ?")) {
      query.setString(1, bookingId);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Booking(
                row.getString("booking_id"),
                new HoldId(row.getString("hold_id")),
                row.getLong("event_id"),
                row.getString("seat_label"),
                row.getString("payment_id"),
                row.getBigDecimal("amount"),
                row.getString("currency"),
                Instant.ofEpochMilli(row.getLong("confirmed_at"))));
      }
    }
  }
```

- `SELECT ... FROM bookings WHERE booking_id = ?` reads one booking by its primary key.
- `row.getString("booking_id")` reads a column by name. `getBigDecimal("amount")` reads the exact decimal. `new HoldId(row.getString("hold_id"))` rebuilds the checked wrapper type. `Instant.ofEpochMilli(...)` rebuilds the time.
- Rows created before migration V2 would have `NULL` in `hold_id`, `payment_id`, `amount` and `currency` and would fail the record's null checks. No row like that can exist here, because every booking is written by `insertBooking`.

#### `stateOf`

```java
  private static HoldState stateOf(String status, Instant expiresAt, Instant now) {
    return switch (status) {
      case "CONFIRMED" -> HoldState.CONFIRMED;
      case "CONFIRMING" -> HoldState.CONFIRMING;
      // Expiry is lazy, as in Module 1: a HELD row past its time is an expired hold.
      case "HELD" -> expiresAt.isAfter(now) ? HoldState.HELD : HoldState.EXPIRED;
      // An AVAILABLE row that still carries this hold id was released by a cancel.
      case "AVAILABLE" -> HoldState.CANCELLED;
      default -> throw new IllegalStateException("the seats table has an unknown status");
    };
  }
}
```

- A **switch expression** picks a value by the text of the column. Each `case "X" -> value` gives the result for that text.
- `HELD` becomes `HELD` while `expiresAt.isAfter(now)`, otherwise `EXPIRED`. This is the lazy expiry from the table above.
- `AVAILABLE` with a hold id is `CANCELLED`: `findHold` only runs for a known hold id, so an `AVAILABLE` row can only be one that a cancel released.
- Any other text throws `IllegalStateException`. The database check constraint makes this impossible; the exception would signal a bug or a broken migration.

### `HoldService.java`

```java
package io.plainround.seatwave.api.booking;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.idempotency.ClaimResult;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

```

- The imports show what the service uses: `Result` from the result4j library, `ContractLimits`, the idempotency types, `Database`, and JDBC and `java.time` types. `Function` is the JDK's type for "a function from one value to another".

```java
/**
 * Holds, reads and releases seats.
 *
 * <p>A hold is one transaction that claims the key, holds the seat and stores the response. If the
 * process dies anywhere inside it, the database rolls all three back, so a retry finds neither a
 * held seat nor a key and simply does the work once.
 *
 * <p>Thread-safe: it holds no mutable state; every call uses its own connection.
 */
public final class HoldService {

  private final Database database;
  private final IdempotencyStore keys;
  private final SeatLedger ledger = new SeatLedger();
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param database the database
   * @param keys the idempotency-key store
   * @param clock the clock every expiry is measured on
   */
  public HoldService(Database database, IdempotencyStore keys, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

```

- The class comment states the guarantee. A hold is one transaction that claims the key, holds the seat and stores the response. If the process dies anywhere inside it, the database rolls all three back. A retry then finds neither a held seat nor a key and does the work once.
- "Thread-safe: it holds no mutable state". All four fields are `final`. Every call opens its own connection. Many request threads can call the same service at once.
- `private final SeatLedger ledger = new SeatLedger();` creates the SQL helper once.
- The constructor stores its three arguments. `Objects.requireNonNull(x, "name")` fails fast with a clear message if the caller passes null. The `Clock` is passed in so tests can move time.

#### `hold`

```java
  /**
   * Holds a seat at most once per key.
   *
   * @param command the validated request
   * @param claim the request's key and fingerprint
   * @param render turns the outcome into the response that is stored under the key
   * @return the response (new or replayed), or the refusal when the key was used with another body
   * @throws SQLException if the database fails; nothing is held and nothing is stored then
   */
  public Result<KeyedReply, Refusal> hold(
      HoldCommand command, KeyClaim claim, Function<HoldOutcome, StoredReply> render)
      throws SQLException {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(render, "render");
    Instant now = clock.instant();
    try (Connection connection = database.connect()) {
```

- The signature: `Result<KeyedReply, Refusal> hold(HoldCommand command, KeyClaim claim, Function<HoldOutcome, StoredReply> render) throws SQLException`. The result is one of two things: a success with a `KeyedReply`, or an error with a `Refusal`. `throws SQLException` means a database failure escapes as an exception (the caller maps it to 503).
- `render` is a function the caller supplies. It turns a `HoldOutcome` into the `StoredReply` that is written under the key. The `booking` package does not know JSON or HTTP, so the HTTP layer hands in the code that does.
- `Instant now = clock.instant();` reads the time once. Every decision in this call uses the same `now`.
- `try (Connection connection = database.connect())` opens a fresh connection and guarantees it is closed at the end of the block.

```java
      connection.setAutoCommit(false);
      try {
        switch (keys.claim(connection, claim, now)) {
          case ClaimResult.Replay(StoredReply reply) -> {
            connection.rollback();
            return Result.success(new KeyedReply(reply, KeyedReply.Source.REPLAY));
          }
          case ClaimResult.BodyMismatch() -> {
            connection.rollback();
            return Result.error(new Refusal.KeyReusedWithOtherBody());
          }
          case ClaimResult.Claimed(), ClaimResult.Resumed() -> {
            // This request owns the key: do the work below.
          }
        }
```

- `connection.setAutoCommit(false);` begins the transaction (everything from here until `commit` or `rollback` is one unit).
- `keys.claim(connection, claim, now)` is the first statement of the transaction. It tries to insert the key row. The `switch` handles the four answers with **record patterns**: `case ClaimResult.Replay(StoredReply reply)` matches a `Replay` and puts its field in a variable named `reply`.
- `Replay`: a finished request with the same key and body exists. `connection.rollback()` ends the transaction at once (nothing to keep), and the method returns a success with `Source.REPLAY`.
- `BodyMismatch`: the key was used with another body. Rollback, and return the error `KeyReusedWithOtherBody`.
- `Claimed()` or `Resumed()` (written as two patterns with a comma): this request owns the key. The block holds only a comment, so the code falls out of the `switch` and goes on. For a hold, `Resumed` should not happen, because a hold claims and completes its key in one transaction and so never leaves an unfinished row behind.

```java
        StoredReply reply = render.apply(holdOnce(connection, command, now));
        if (!keys.complete(connection, claim, reply)) {
          // A hold claims and completes its key in one transaction, so no one else can.
          throw new IllegalStateException("a hold's key was completed by another transaction");
        }
        // One commit for the seat and the key: there is no moment when one exists without the
        // other.
        connection.commit();
        return Result.success(new KeyedReply(reply, KeyedReply.Source.FIRST));
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
```

- `holdOnce(connection, command, now)` does the seat work (below) and returns a `HoldOutcome`. `render.apply(...)` turns it into the `StoredReply`.
- `keys.complete(connection, claim, reply)` stores the reply under the key, in the same transaction as the seat change. It returns false only if another transaction completed the key first. For a hold that cannot happen, because the key row is locked by this transaction, so the code throws `IllegalStateException`, which rolls everything back in the `catch` below.
- `connection.commit();` is the single commit for the seat and the key. The comment states the point: there is no moment when one exists without the other.
- `catch (SQLException | RuntimeException failure)` catches both kinds of failure. `connection.rollback()` undoes the key row and the seat change, and `throw failure` passes the original error on. The `try (Connection ...)` then closes the connection.

#### `find`

```java
  /**
   * Reads a hold.
   *
   * @param holdId the hold
   * @return the hold, or empty if no seat carries this hold id any more
   * @throws SQLException if the database fails
   */
  public Optional<Hold> find(HoldId holdId) throws SQLException {
    Objects.requireNonNull(holdId, "holdId");
    try (Connection connection = database.connect()) {
      return ledger
          .findHold(connection, holdId, clock.instant())
          .map(row ->
              new Hold(holdId, row.eventId(), row.seatLabel(), row.state(), row.expiresAt()));
    }
  }
```

- `find` reads a hold by id and returns an `Optional<Hold>`. It runs on an auto-commit connection: one `SELECT`, nothing to undo.
- `.map(row -> new Hold(holdId, row.eventId(), ...))` runs only if a row was found. The arrow `row -> ...` is a **lambda**: a small function written inline.
- It passes `clock.instant()` so the state (`HELD` or `EXPIRED`) is correct at this moment.

#### `cancel`

```java
  /**
   * Releases a hold. Repeating it changes nothing and answers the same, so it needs no key.
   *
   * @param holdId the hold
   * @return released, not found, or too late (a payment in flight, or booked)
   * @throws SQLException if the database fails
   */
  public CancelOutcome cancel(HoldId holdId) throws SQLException {
    Objects.requireNonNull(holdId, "holdId");
    Instant now = clock.instant();
    try (Connection connection = database.connect()) {
      if (ledger.cancel(connection, holdId, now)) {
        return new CancelOutcome.Released();
      }
      Optional<SeatLedger.HoldRow> row = ledger.findHold(connection, holdId, now);
      if (row.isEmpty()) {
        return new CancelOutcome.HoldNotFound();
      }
      return switch (row.get().state()) {
        case CANCELLED -> new CancelOutcome.Released();
        case CONFIRMING, CONFIRMED -> new CancelOutcome.TooLate(row.get().state());
        // The cancel UPDATE releases every HELD row, expired or not, so these cannot be left.
        case HELD, EXPIRED -> throw new IllegalStateException("a held seat was not released");
      };
    }
  }
```

- `ledger.cancel(connection, holdId, now)` runs the conditional `UPDATE` from `HELD` to `AVAILABLE`. If it changed one row, the answer is `Released`.
- Otherwise `findHold` looks at the row. No row: `HoldNotFound`.
- The `switch` over `row.get().state()` handles every `HoldState`. `CANCELLED`: an earlier cancel did it, so answer `Released` again (this is what makes cancel idempotent). `CONFIRMING` or `CONFIRMED`: `TooLate` with the state. `HELD` or `EXPIRED` cannot be left after the cancel `UPDATE`, because it releases every `HELD` row; if it happens, the code throws `IllegalStateException`. The `switch` has no `default` because it covers every enum value, and the compiler checks that.
- Each of the two statements is its own transaction because the connection is in auto-commit mode.

#### `holdOnce`

```java
  private HoldOutcome holdOnce(Connection connection, HoldCommand command, Instant now)
      throws SQLException {
    HoldId holdId = HoldId.random();
    if (ledger.holdSeat(connection, command, holdId, now)) {
      return new HoldOutcome.Held(
          new Hold(
              holdId,
              command.eventId(),
              command.seatLabel(),
              HoldState.HELD,
              now.plus(ContractLimits.HOLD_DURATION)));
    }
    // Only a refusal pays for this second query, to tell "taken" (409) from "no such seat" (404).
    return ledger.seatExists(connection, command.eventId(), command.seatLabel())
        ? new HoldOutcome.SeatTaken()
        : new HoldOutcome.SeatNotFound();
  }
}
```

- `HoldId.random()` makes the new id before the attempt. If `ledger.holdSeat` returns true, the code builds the `Hold`: state `HELD` and end time `now.plus(ContractLimits.HOLD_DURATION)`, which is the same value the `UPDATE` stored.
- If the update changed 0 rows, one more query, `seatExists`, tells the two reasons apart: the seat exists, so someone else has it (`SeatTaken`), or it does not exist (`SeatNotFound`). The comment says only a refusal pays for this second query.
- The `?:` form is the **conditional expression**: `condition ? valueIfTrue : valueIfFalse`.

### `ConfirmService.java`

```java
package io.plainround.seatwave.api.booking;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.idempotency.ClaimResult;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.payment.PaymentClient;
import io.plainround.seatwave.api.payment.PaymentOutcome;
import io.plainround.seatwave.api.store.Database;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

```

- The imports add the payment types, `UUID`, `BigDecimal`, `Duration`, and the SLF4J logger (`Logger`, `LoggerFactory`), which writes log lines.

```java
/**
 * Turns a hold into a paid booking, at most once per key and at most one charge per hold.
 *
 * <p>The payment call is remote, so no transaction stays open across it. The work runs in three
 * steps:
 *
 * <ol>
 *   <li>One transaction claims the key and moves the seat from HELD to CONFIRMING with a lease. A
 *       refusal (no such hold, expired, already booked) is stored under the key in the same
 *       transaction.
 *   <li>The provider is called with a key made from the hold, so every attempt for this hold, by
 *       any client key or process, names the same charge.
 *   <li>One transaction writes the booking, marks the seat CONFIRMED and stores the response under
 *       the key. If the provider did not confirm, the lease is ended instead and nothing is stored:
 *       the client's retry with the same key carries on from step 1.
 * </ol>
 *
 * <p>Thread-safe: it holds no mutable state; every call uses its own connections.
 */
public final class ConfirmService {

  private static final Logger LOG = LoggerFactory.getLogger(ConfirmService.class);

  private final Database database;
  private final IdempotencyStore keys;
  private final PaymentClient payments;
  private final Price price;
  private final Duration lease;
  private final Clock clock;
  private final SeatLedger ledger = new SeatLedger();

```

- The class comment describes the three steps. The payment call is remote and can take seconds, so no transaction stays open across it. An open transaction holds row locks, and other requests would wait.
  1. One transaction claims the key and moves the seat from `HELD` to `CONFIRMING` with a lease. If the hold cannot be confirmed (no such hold, expired, already booked), the refusal is stored under the key in the same transaction.
  2. The provider is called with a key made from the hold, so every attempt for this hold, by any client key or process, names the same charge.
  3. One transaction writes the booking, marks the seat `CONFIRMED` and stores the response under the key. If the provider did not confirm, the lease is ended instead and nothing is stored, so the client's retry with the same key carries on from step 1.
- `LOG` is the logger of this class. The fields are the database, the key store, the payment client, the price, the lease length and the clock; all are `final`.

```java
  /**
   * Creates the service.
   *
   * @param database the database
   * @param keys the idempotency-key store
   * @param payments the payment provider's client
   * @param price what one seat costs
   * @param lease how long one attempt owns a seat while it pays
   * @param clock the clock
   */
  public ConfirmService(
      Database database,
      IdempotencyStore keys,
      PaymentClient payments,
      Price price,
      Duration lease,
      Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.payments = Objects.requireNonNull(payments, "payments");
    this.price = Objects.requireNonNull(price, "price");
    this.lease = Objects.requireNonNull(lease, "lease");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

```

- The constructor takes six arguments and checks each for null. `lease` is a `Duration` (a length of time). In `config/serve.properties` it is 5,000 ms (`payment.leaseMillis`), which must exceed the connect timeout plus the request timeout (250 + 1,500 ms); `ApiSettings.Payment` enforces that.

#### `confirm`

```java
  /**
   * Confirms a hold.
   *
   * @param command the validated request
   * @param claim the request's key and fingerprint
   * @param render turns a final outcome into the response stored under the key
   * @return the response (new or replayed), or a refusal that stores nothing
   * @throws SQLException if the database fails
   */
  public Result<KeyedReply, Refusal> confirm(
      ConfirmCommand command, KeyClaim claim, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(render, "render");
    HoldId holdId = command.holdId();
    Result<Optional<KeyedReply>, Refusal> started = start(holdId, claim, render);
    if (started instanceof Result.Error<Optional<KeyedReply>, Refusal>(Refusal refusal)) {
      return Result.error(refusal);
    }
    if (started instanceof Result.Success<Optional<KeyedReply>, Refusal>(Optional<KeyedReply> done)
        && done.isPresent()) {
      return Result.success(done.get());
    }
```

- `confirm` returns a `Result<KeyedReply, Refusal>`, like `hold`. The `render` function turns a `ConfirmOutcome` into the stored reply.
- `Result<Optional<KeyedReply>, Refusal> started = start(holdId, claim, render);` runs step 1. The inner `Optional` has two meanings: **empty** means "this attempt now owns the seat and must pay"; **present** means "the request is already finished" (a replay or a stored refusal).
- `started instanceof Result.Error<...>(Refusal refusal)` is a **pattern test**: if `started` is an error, the variable `refusal` is set and the code returns that error. Nothing is stored for a refusal.
- The next `if` tests for a success whose optional is present: the work is done (a replay, or a final refusal such as "hold not found"), so return it.
- If neither returned, the optional was empty: go on to step 2.

```java
    // Step 2, outside any transaction: the provider deduplicates by the hold's key.
    PaymentOutcome payment = payments.charge(holdId.value(), price.amount(), price.currency());
    return switch (payment) {
      case PaymentOutcome.Captured(String paymentId) -> finish(holdId, paymentId, claim, render);
      case PaymentOutcome.Unavailable(PaymentOutcome.Reason reason) -> {
        LOG.warn("payment not confirmed ({}); lease ended, key kept for the retry", reason);
        endLease(holdId);
        yield Result.error(new Refusal.PaymentUnavailable());
      }
    };
  }
```

- Step 2: `payments.charge(holdId.value(), price.amount(), price.currency())`. No connection and no transaction is open here. The provider key is made from the hold id inside `PaymentClient` (see `java-payment-and-store-walkthrough.md`).
- The `switch` over `PaymentOutcome` has two cases. `Captured(String paymentId)` goes to step 3, `finish`.
- `Unavailable(Reason reason)` logs a warning, ends the lease, and returns the error `PaymentUnavailable`. `yield` is how a `case` block with braces gives its value in a switch expression. Nothing is stored under the key, so the client may retry with the same key. The real log line I saw was `payment not confirmed (PROVIDER_ERROR); lease ended, key kept for the retry`.

#### `find`

```java
  /**
   * Reads a booking.
   *
   * @param bookingId the booking, a lowercase UUID
   * @return the booking, or empty if there is none
   * @throws SQLException if the database fails
   */
  public Optional<Booking> find(String bookingId) throws SQLException {
    Objects.requireNonNull(bookingId, "bookingId");
    try (Connection connection = database.connect()) {
      return ledger.findBooking(connection, bookingId);
    }
  }

```

- Reads a booking by id on an auto-commit connection and returns `Optional<Booking>`.

#### `start` (step 1)

```java
  /**
   * Step 1. Returns an empty optional when this attempt now owns the seat and must pay; a reply
   * when the request is finished (replayed or refused for good); or a refusal.
   */
  private Result<Optional<KeyedReply>, Refusal> start(
      HoldId holdId, KeyClaim claim, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
    Instant now = clock.instant();
    try (Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      try {
```

- The comment gives the three possible returns: an empty optional (must pay), a reply (finished), or a refusal.
- `connection.setAutoCommit(false)` begins the step-1 transaction.

```java
        switch (keys.claim(connection, claim, now)) {
          case ClaimResult.Replay(StoredReply reply) -> {
            connection.rollback();
            return Result.success(Optional.of(new KeyedReply(reply, KeyedReply.Source.REPLAY)));
          }
          case ClaimResult.BodyMismatch() -> {
            connection.rollback();
            return Result.error(new Refusal.KeyReusedWithOtherBody());
          }
          case ClaimResult.Claimed(), ClaimResult.Resumed() -> {
            // Owner of the key, or the retry of an attempt that did not finish: go on.
          }
        }
```

- The same key claim as in `hold`. `Replay` rolls back and returns the stored reply wrapped in `Optional.of`. `BodyMismatch` rolls back and returns the 422 refusal. `Claimed` and `Resumed` both continue. Here `Resumed` is normal: it is the retry of an attempt whose payment failed, for example. The state of the seat decides whether it can go on.

```java
        if (ledger.leaseForConfirm(connection, holdId, now, now.plus(lease))) {
          connection.commit();
          return Result.success(Optional.empty());
        }
```

- `ledger.leaseForConfirm(connection, holdId, now, now.plus(lease))` tries to take the seat. If it changed one row, this attempt owns the seat. `connection.commit()` ends the transaction: the key row (state `IN_PROGRESS`) and the `CONFIRMING` seat with its lease become visible together. The method returns a success with an empty optional: go and pay.

```java
        Optional<ConfirmOutcome> refused = refusalFor(connection, holdId, now);
        if (refused.isEmpty()) {
          // Another attempt holds a live lease and is paying now. Store nothing: retry later.
          connection.rollback();
          return Result.error(new Refusal.ConfirmInProgress());
        }
        StoredReply reply = render.apply(refused.get());
        if (!keys.complete(connection, claim, reply)) {
          // A racing attempt with this key finished between our claim and now: send its answer.
          connection.rollback();
          return replayStored(claim).map(Optional::of);
        }
        connection.commit();
        return Result.success(Optional.of(new KeyedReply(reply, KeyedReply.Source.FIRST)));
```

- If the lease update changed 0 rows, `refusalFor` looks at the seat and says why. An empty answer means the seat is `CONFIRMING` with a live lease: another attempt is paying now. The code rolls back and returns `ConfirmInProgress` (409). Nothing is stored, so the client retries later.
- Otherwise it is a final refusal (`HoldNotFound` or `HoldNotActive`). `render.apply(...)` makes the reply and `keys.complete` stores it under the key. If `complete` returns false, a racing attempt with this key finished first: roll back and send its stored answer with `replayStored`.
- On success: `commit()` and return the new reply with `Source.FIRST`. A refused confirm is stored, so repeating the request replays the same 409 or 404.

```java
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

```

- Any failure rolls back and rethrows, the same pattern as in `HoldService`.

#### `finish` (step 3)

```java
  /** Step 3: the booking, the seat and the key's response, in one transaction. */
  private Result<KeyedReply, Refusal> finish(
      HoldId holdId, String paymentId, KeyClaim claim, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
    Instant now = clock.instant();
    try (Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      try {
        Optional<SeatLedger.HoldRow> row = ledger.findHold(connection, holdId, now);
        String bookingId = UUID.randomUUID().toString();
        if (row.isEmpty() || !ledger.markConfirmed(connection, holdId, bookingId)) {
          // Our lease ran out and another attempt finished first, with the same charge: whatever
          // it stored under this key is the answer.
          connection.rollback();
          return replayAfterLostRace(claim, holdId, render);
```

- It runs only after the provider confirmed a capture. It begins a new transaction and reads the hold with `findHold`.
- `UUID.randomUUID().toString()` makes the booking id.
- `row.isEmpty() || !ledger.markConfirmed(...)`: if the hold is gone, or the seat could not move from `CONFIRMING` to `CONFIRMED`, then this attempt lost its lease and another attempt finished first with the same charge. The comment says whatever it stored under this key is the answer. The code rolls back and calls `replayAfterLostRace`.

```java
        Booking booking =
            new Booking(
                bookingId,
                holdId,
                row.get().eventId(),
                row.get().seatLabel(),
                paymentId,
                price.amount(),
                price.currency(),
                now);
        ledger.insertBooking(connection, booking, row.get().customer());
        StoredReply reply = render.apply(new ConfirmOutcome.Booked(booking));
        if (!keys.complete(connection, claim, reply)) {
          connection.rollback();
          return replayStored(claim);
        }
        connection.commit();
        return Result.success(new KeyedReply(reply, KeyedReply.Source.FIRST));
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
```

- A `Booking` is built from the hold row, the payment id from the provider, and the price from the settings. `ledger.insertBooking` writes it.
- `render.apply(new ConfirmOutcome.Booked(booking))` makes the 201 reply, and `keys.complete` stores it under the key. If the key was completed by someone else meanwhile, roll back and send that stored reply.
- `connection.commit()` is the one commit of step 3: the booking row, the `CONFIRMED` seat and the key's reply appear together or not at all.

#### The helper methods

```java
  private Result<KeyedReply, Refusal> replayAfterLostRace(
      KeyClaim claim, HoldId holdId, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
    // Running step 1 again finds the stored reply, or stores "already booked" for this key.
    Result<Optional<KeyedReply>, Refusal> again = start(holdId, claim, render);
    return switch (again) {
      case Result.Success<Optional<KeyedReply>, Refusal>(Optional<KeyedReply> reply) ->
          reply
              .<Result<KeyedReply, Refusal>>map(Result::success)
              .orElseGet(() -> Result.error(new Refusal.ConfirmInProgress()));
      case Result.Error<Optional<KeyedReply>, Refusal>(Refusal refusal) -> Result.error(refusal);
    };
  }
```

- `replayAfterLostRace` runs step 1 again. Because the other attempt finished and stored its reply, `claim` returns `Replay` and the stored reply comes back. If this key never completed (for example because the winner used another client key), step 1 finds a `CONFIRMED` seat and stores "hold not active" under this key.
- The `switch` over the `Result` handles both sides. `reply.<Result<KeyedReply, Refusal>>map(Result::success)` turns a present optional into a success; `.orElseGet(() -> Result.error(new Refusal.ConfirmInProgress()))` answers 409 if the optional was empty. `Result::success` is a **method reference**: a short form of the lambda `value -> Result.success(value)`. The `<Result<KeyedReply, Refusal>>` before `map` tells the compiler the result type.

```java
  private Result<KeyedReply, Refusal> replayStored(KeyClaim claim) throws SQLException {
    try (Connection connection = database.connect()) {
      connection.setAutoCommit(false);
      try {
        ClaimResult found = keys.claim(connection, claim, clock.instant());
        connection.rollback();
        return found instanceof ClaimResult.Replay(StoredReply reply)
            ? Result.success(new KeyedReply(reply, KeyedReply.Source.REPLAY))
            : Result.error(new Refusal.ConfirmInProgress());
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
```

- `replayStored` opens a transaction only to read: `keys.claim` either finds a completed row (`Replay`) or not. It always rolls back, because it must never leave a new claim behind. If the result is a `Replay`, the stored reply is returned with `Source.REPLAY`. Otherwise the answer is `ConfirmInProgress`.

```java
  private Optional<ConfirmOutcome> refusalFor(Connection connection, HoldId holdId, Instant now)
      throws SQLException {
    Optional<SeatLedger.HoldRow> row = ledger.findHold(connection, holdId, now);
    if (row.isEmpty()) {
      return Optional.of(new ConfirmOutcome.HoldNotFound());
    }
    return switch (row.get().state()) {
      case CONFIRMING -> Optional.empty();
      case CONFIRMED, CANCELLED, EXPIRED ->
          Optional.of(new ConfirmOutcome.HoldNotActive(row.get().state()));
      // The lease UPDATE takes every live HELD row, so a HELD row here means a clock problem.
      case HELD -> throw new IllegalStateException("a live hold was not leased");
    };
  }
```

- `refusalFor` turns the state of the seat into a `ConfirmOutcome`. No row: `HoldNotFound`. `CONFIRMING`: empty (a live lease, someone is paying). `CONFIRMED`, `CANCELLED` or `EXPIRED`: `HoldNotActive` with the state. `HELD` cannot be left, because the lease update takes every live `HELD` row; seeing one means a clock problem, so it throws.

```java
  private void endLease(HoldId holdId) throws SQLException {
    try (Connection connection = database.connect()) {
      ledger.releaseLease(connection, holdId, clock.instant());
    }
  }

```

- `endLease` opens an auto-commit connection and calls `releaseLease` with the current time.

```java
  /**
   * The price of one seat.
   *
   * @param amount the amount, two decimals
   * @param currency the ISO 4217 code
   */
  public record Price(BigDecimal amount, String currency) {

    /** Checks the fields. */
    public Price {
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(currency, "currency");
    }
  }
}
```

- `Price` is a nested public record: an `amount` (`BigDecimal`) and a `currency` code. The settings supply it: 80.00 and `USD` in `config/serve.properties`.

## Try it yourself

All commands run in a copy of the repository, never in a shared folder. Timings differ on every run.

**1. Run the retry storm.** It starts the API and the stand-in provider in one process and fires 2,000 requests:

```text
./gradlew -q retryStorm --args='config/retry-storm.properties'
```

I ran it in a scratch copy with the ports changed. The output (shortened to the lines that matter) was:

```text
scenario 1: one hold sent 100 times at once with one key
  final replies by status: {201=100}
  distinct hold ids in the replies: 1; seats the database shows held by them: 1
scenario 2: 500 holds and 500 confirms, each sent twice at once with its own key (2000 requests)
  database: 500 bookings, 0 seats booked twice; provider: 500 charges, 0 holds charged twice
scenario 3: one seat wanted by 100 fans with 100 keys
  final replies by status: {201=1, 409=99}
  database: 1 holder(s) of the seat
```

The counts of the 503 retries and the latencies change with every run (my run showed 554 retries; the README shows 620). The five proof lines do not change.

**2. Walk one hold through its life with `curl`.** Start the stand-in provider and the API in two terminals (`./gradlew -q paymentStandIn --args='config/payment-stand-in.properties'` and `./gradlew -q serve --args='config/serve.properties'`), then in a third:

```text
curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "demo-1"' -d '{"eventId":1,"seat":"C-3","customer":"fan_1"}'
```

```text
{"holdId":"66647fd5-c8e1-4bcd-aea6-807de6fb550a","eventId":1,"seat":"C-3","state":"held","expiresAt":"2026-11-11T01:44:01.312616Z"}
```

The same command again returns the identical text (a replay). Another fan with a new key for the same seat gets `409` with the type `seat-unavailable`. Cancelling twice (`curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://127.0.0.1:8087/v1/holds/<holdId>`) prints `204` twice. Reading the hold afterwards shows `"state":"cancelled"`, and confirming it gives `409` with `hold-not-active` and the detail `the hold is cancelled`.

**3. Make the provider time out after charging.** With a new hold (`D-5` in my run):

```text
curl -s -X POST 'http://127.0.0.1:8088/control/mode?mode=charge_then_timeout'
curl -s -w '[%{http_code} %{time_total}s]\n' -X POST http://127.0.0.1:8087/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "demo-6"' -d '{"holdId":"<holdId>"}'
```

```text
{"mode":"charge_then_timeout"}
{"type":"https://seatwave.example/problems/payment-unavailable", ... "status":503,"detail":"nothing was booked; the same key will not charge twice", ...}[503 1.517485s]
```

The provider's ledger (`curl -s http://127.0.0.1:8088/control/ledger`) already shows `{"charges":1,...}`, and the hold reads `"state":"confirming"`. Then switch the mode back to `ok` and send the same confirm again: it returns `201` with a booking, a second identical call returns the same booking, and the ledger still says one charge. I stopped both servers afterwards.

## Mistakes people make

- **Check, then update, in two statements.** Reading `status`, deciding in Java, then writing lets two requests both pass the check. Here the condition is part of the `UPDATE`, and the code reads the count of changed rows.
- **Keeping a transaction open across the network call.** The payment call may take seconds. An open transaction holds row locks and blocks other requests, and a crash would leave the transaction in an unknown state. Here the confirm is three steps, with no transaction around step 2.
- **Forgetting to end a transaction on an error path.** An unfinished transaction keeps its row locks (I saw a statement that changed nothing still hold a lock). Every method here has `commit()` or `rollback()` on every path, and the `catch` rolls back before rethrowing.
- **Putting the key row and the effect in different transactions.** A crash between them loses the reply or tells a lie. Here one `commit()` covers both.
- **Reselling a seat after an unknown payment result.** After a timeout the money may have moved. The seat stays `CONFIRMING`; only the lease ends.
- **Trusting a stored status to tell time.** A job that sets `EXPIRED` can lag. Here the clock and `hold_expires_at` decide on every read.

## Check yourself

1. What does `update.executeUpdate() == 1` mean in `SeatLedger.holdSeat`? (This request changed the seat row, so it won the seat. 0 means another request already holds it, or it does not exist.)
2. Why does the hold `UPDATE` accept a `HELD` row whose `hold_expires_at` has passed? (Expiry is lazy: nothing sets `EXPIRED`. A hold whose time has passed no longer protects the seat.)
3. Which three things become visible at the same instant when `HoldService.hold` calls `commit()`? (The seat change, the key row, and the stored reply.)
4. Why is there no transaction around `payments.charge`? (The call is remote and slow. A transaction would hold locks for seconds, and the seat would be unavailable to everyone else.)
5. After a provider timeout, why does the seat stay `CONFIRMING` and not go back to `HELD`? (The charge may have succeeded. Reselling the seat could sell it twice. The retry with the same key resumes at step 1 and the provider key prevents a second charge.)

## Further reading

Official documentation, for Java 25 and H2 2.5.252:

- Java records (language specification, chapter 8.10): https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.10
- Sealed classes and interfaces (language specification, chapter 8.1.6): https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6
- `switch` expressions and patterns (chapter 15.28): https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.28
- JDBC `Connection` (auto-commit, commit, rollback): https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html
- JDBC `PreparedStatement`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/PreparedStatement.html
- `java.time.Instant`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Instant.html
- H2 database, transaction isolation and row locks (the "Advanced" page, section "Multi-Version Concurrency Control"): https://h2database.com/html/advanced.html
- H2 SQL grammar for `UPDATE`: https://h2database.com/html/commands.html#update
