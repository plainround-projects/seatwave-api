# The `idempotency` package, line by line (keys that make a retry safe)

## What it is

The `idempotency` package is the part of SeatWave that makes a repeated request harmless. It lives in `src/main/java/io/plainround/seatwave/api/idempotency/` and has six files.

**Idempotent** means: doing it twice has the same effect as doing it once. Setting a light switch to "on" is idempotent. Pressing a toggle button is not. A write such as "hold seat A-7" is a toggle by nature: the second request would try to take the seat again.

An **idempotency key** is a label the client invents for one intended action, for example `"fan-7-hold-1"`. The client sends it in the `Idempotency-Key` header. If the reply is lost on the way back, the client sends the same request again with the same key. The server sees the key, remembers that it already did the work, and sends the first answer again. It does not do the work twice.

Why would a client send twice? A mobile phone leaves a tunnel and the reply never arrived. A load balancer times out and retries. A person double-clicks. The client cannot tell "the server never got my request" from "the server did it but the reply was lost". The key lets it retry in both cases without fear.

## Why this repository uses it

This is the main lesson of Module 7. The retry test sends one hold 100 times at once with one key and shows one seat held, one key row and 100 identical replies. The package gives the five facts the lesson teaches:

1. A key and its effect are written in one database transaction, so a crash cannot leave one without the other.
2. The same key with a different body is refused (422), because the client made a mistake.
3. Two requests racing with one key are sorted out by the database's primary key: one does the work, the other waits and then replays.
4. A retry after a lost reply gets the stored reply.
5. A key is forgotten after 24 hours (`ContractLimits.KEY_RETENTION`), and an expired key is treated as new.

## Where it appears

| File | What it does |
|---|---|
| `IdempotencyKey.java` | The key as a checked type; reads the `Idempotency-Key` header. |
| `Fingerprint.java` | A SHA-256 digest of what a request means; used to spot "same key, different body". |
| `KeyClaim.java` | One request's claim: endpoint, key and fingerprint. |
| `StoredReply.java` | The reply kept under the key: status, location and body. |
| `ClaimResult.java` | The four answers of a claim: `Claimed`, `Resumed`, `Replay`, `BodyMismatch`. |
| `IdempotencyStore.java` | The SQL on the table `idempotency_keys`. It works only inside the caller's transaction. |

The table is created by `src/main/resources/db/migration/V2__idempotency_and_payment.sql` (see `h2-sql-and-migrations.md`). The callers are `HoldService` and `ConfirmService` (see `java-booking-walkthrough.md`).

## How it works underneath

### The row, and the two states

One row per `(endpoint, key)`. The endpoint is the text `POST /v1/holds` or `POST /v1/bookings`. Keys are scoped to the endpoint: the same key text used on the two endpoints gives two different rows. The primary key of the table is named `one_effect_per_key` and covers exactly those two columns. The database refuses a second row with the same pair. That refusal is the whole safety mechanism.

The row has a state:

- `IN_PROGRESS`: the key is claimed and no reply is stored yet. The `ConfirmService` leaves a committed `IN_PROGRESS` row between its step 1 and step 3.
- `COMPLETED`: the reply (status, location, body) is stored.

### The five paths of a claim

```text
claim(connection, claim, now)
  insert the row (state IN_PROGRESS) ........................ ok -> Claimed
  insert fails with "duplicate" ............................. read the existing row
      row gone (the other transaction rolled back) .......... insert once more
      row expired (expires_at <= now) ....................... take it over -> Claimed,
                                                               or read what the winner left
      row has another fingerprint ........................... BodyMismatch (422)
      row is COMPLETED ...................................... Replay (the stored reply)
      row is IN_PROGRESS .................................... Resumed
```

### What happens in the situations the lesson names

**The same key with a different body.** The first request stores its fingerprint. The second request has the same key but a different seat, so its fingerprint differs. `claim` compares the two and returns `BodyMismatch`. The service rolls back and returns the refusal `KeyReusedWithOtherBody`. The `http` layer answers 422 with the type `idempotency-key-reused`. I saw exactly this: the same key `demo-1` with seat `C-4` instead of `C-3` gave `422` and `"title":"This Idempotency-Key was used with another body"`. A reply is never stored for a mismatch, so the first request's answer stays unchanged.

**Two requests racing with one key.** Both do `INSERT` of the same `(endpoint, key)`. The database lets the first one in and holds that row's lock until the first transaction ends. The second `INSERT` waits (up to the lock timeout, 2,000 ms in `config/serve.properties`). When the first commits, the second fails with the duplicate error. The code catches that, reads the row, sees the same fingerprint and `COMPLETED`, and returns `Replay`. If the first rolls back instead, the second `INSERT` succeeds and the second request becomes the owner. I confirmed both halves in a scratch program on H2 2.5.252: the second insert printed `racer waiting: true`, and after the commit its result was SQLState `23505`.

**A retry after a lost reply.** There are two cases.
- The transaction committed and only the reply was lost on the way. The row is `COMPLETED`. The retry gets `Replay` and the stored reply, byte for byte. The README shows four retries of one hold returning the identical text.
- The process died before the commit. The database rolled back both the seat change and the key row. The retry finds no row, becomes the owner, and does the work once. This is why the key row and the effect must be in the same transaction. If they were in two transactions, a crash between them would leave either a seat held with no key (the retry would try to hold again and get "seat taken") or a key with no seat (the retry would be told it worked when it did not).

**An expired key.** Every row has `expires_at`: its creation time plus 24 hours. When a claim finds a duplicate whose `expires_at` is now or earlier, it takes the row over with one conditional `UPDATE` that writes the new fingerprint, `IN_PROGRESS` and a new expiry. The request is new, "whatever the old one did". A job also deletes expired rows in batches (`purgeExpired`, run once a minute by `SeatWaveApi`), so the table does not grow without limit; the index `idempotency_keys_by_expiry` makes that fast.

**Two requests with the same expired key at the same moment.** Both read the old expired row. Both send the take-over `UPDATE`, whose condition `expires_at <= now` is checked again by the database under the row's lock. The first takes the lock and changes the row. The second waits for that lock. When the first commits, the database checks the second one's condition again on the new row, which is no longer expired, so the second `UPDATE` changes nothing. The second request then reads the row the first one left and is treated like any other retry: the same body and `COMPLETED` gives `Replay`, a different body gives `BodyMismatch`. If the first rolls back instead, the row is expired again and the second takes it over. Exactly one request does the work. (An earlier version deleted the expired row and inserted a new one in two steps; the second request then got `Resumed` with nothing to complete, and a hold ended in an internal error. `IdempotencyStoreTest` forces that interleaving and checks the second request now gets the first one's reply.)

## Walkthrough of this repository's use

### `IdempotencyKey.java`

```java
package io.plainround.seatwave.api.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client-chosen key that names one intended write. Valid by construction: 1 to 64 letters,
 * digits, hyphens or underscores.
 *
 * @param value the key, without the quotes of the header syntax
 */
public record IdempotencyKey(String value) {

```

- A key is a record with one text field, `value`, the key without its quotes. The Javadoc says "valid by construction": a key that exists is always well formed.

```java
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  // The IETF draft makes the header an RFC 9651 String: the key inside double quotes. Only the
  // characters of KEY are allowed inside, so no escape sequence can appear.
  private static final Pattern HEADER = Pattern.compile("\"([A-Za-z0-9_-]{1,64})\"");

  /** Checks the value. */
  public IdempotencyKey {
    if (!KEY.matcher(Objects.requireNonNull(value, "value")).matches()) {
      throw new IllegalArgumentException("an idempotency key is 1 to 64 of A-Z a-z 0-9 _ -");
    }
  }
```

- `KEY` is `[A-Za-z0-9_-]{1,64}`: one to 64 letters, digits, underscores or hyphens. The limit of 64 keeps the table column `idempotency_key VARCHAR(64)` and the contract's pattern in step.
- `HEADER` is the same set inside double quotes: `"([A-Za-z0-9_-]{1,64})"`. The parentheses make a **capturing group**, so the text between the quotes can be taken out as `group(1)`. The comment explains why quotes: the IETF draft for this header makes it a **String** in RFC 9651 syntax, which is written between double quotes. Only the characters of `KEY` are allowed inside, so no escape sequence (a backslash) can appear and no unquoting logic is needed.
- The compact constructor throws `IllegalArgumentException` for a value that does not fit `KEY`.

```java
  /**
   * Reads the Idempotency-Key header.
   *
   * @param headerValue the header as received, for example {@code "8e03978e-..."} with quotes
   * @return the key, or empty if the header does not match the contract's syntax
   */
  public static Optional<IdempotencyKey> fromHeader(String headerValue) {
    if (headerValue == null) {
      return Optional.empty();
    }
    Matcher matcher = HEADER.matcher(headerValue.strip());
    return matcher.matches() ? Optional.of(new IdempotencyKey(matcher.group(1))) : Optional.empty();
  }
}
```

- `fromHeader` is a **static factory**: a method that makes an object from outside text. It returns an `Optional<IdempotencyKey>`: empty when the header is missing or does not fit.
- `headerValue.strip()` removes spaces at both ends. `matcher.matches()` is true only when the whole text fits `HEADER`. Then `matcher.group(1)` is the text inside the quotes.
- A header such as `fan-7-hold-1` without quotes, or with a space inside, gives an empty result. The `http` layer then answers 400 with the pointer `Idempotency-Key`, as in the README's session.

### `Fingerprint.java`

```java
package io.plainround.seatwave.api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A SHA-256 digest of what a request means: the endpoint and the request body re-written in one
 * canonical form. Two bodies that differ only in spacing or field order have the same fingerprint,
 * so a client library that reorders fields is not punished; a different seat or customer is a
 * different fingerprint.
 *
 * @param hex the digest, 64 lowercase hexadecimal characters
 */
public record Fingerprint(String hex) {

  private static final Pattern HEX_SHA_256 = Pattern.compile("[0-9a-f]{64}");
```

- A **fingerprint** is a short value computed from a longer one, so that different inputs give different fingerprints. Here it is a **SHA-256** digest: a function that turns any bytes into 32 bytes, written as 64 hexadecimal characters. A change of one input character changes the digest completely, and nobody can find two inputs with the same digest in practice.
- The Javadoc explains what is hashed: the endpoint and the request body re-written in one **canonical** form (one fixed way of writing). Two bodies that differ only in spacing or field order have the same fingerprint, so a client library that reorders fields is not punished. A different seat or customer is a different fingerprint.
- `HEX_SHA_256` is `[0-9a-f]{64}`: 64 lowercase hexadecimal characters. The table column is `fingerprint CHAR(64)`.

```java
  /** Checks the value. */
  public Fingerprint {
    if (!HEX_SHA_256.matcher(Objects.requireNonNull(hex, "hex")).matches()) {
      throw new IllegalArgumentException("a fingerprint is 64 lowercase hexadecimal characters");
    }
  }

```

- The compact constructor refuses a value that is not 64 lowercase hex characters, so a `Fingerprint` can be compared with `equals` on text without surprises.

```java
  /**
   * Computes the fingerprint of one request.
   *
   * @param endpoint the operation, for example {@code POST /v1/holds}
   * @param canonicalBody the body, already re-serialized from the bound record
   * @return the fingerprint
   */
  public static Fingerprint of(String endpoint, String canonicalBody) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(canonicalBody, "canonicalBody");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(endpoint.getBytes(StandardCharsets.UTF_8));
      // A separator that cannot occur in an endpoint keeps "a" + "bc" apart from "ab" + "c".
      digest.update((byte) 0);
      digest.update(canonicalBody.getBytes(StandardCharsets.UTF_8));
      return new Fingerprint(HexFormat.of().formatHex(digest.digest()));
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not an input.
      throw new IllegalStateException("SHA-256 is not available", missingAlgorithm);
    }
  }
```

- `of(endpoint, canonicalBody)` is the only way to compute one. The caller passes a body that was already re-serialized from the bound record, so the bytes depend on the meaning and not on how the client typed it.
- `MessageDigest.getInstance("SHA-256")` asks the JDK for a digest engine. `digest.update(bytes)` feeds it bytes; you may call it several times.
- `endpoint.getBytes(StandardCharsets.UTF_8)` turns text into bytes with a fixed encoding. Never rely on the machine's default encoding for a hash.
- `digest.update((byte) 0)` feeds one zero byte between the endpoint and the body. The comment says why: a separator that cannot occur in an endpoint keeps `"a" + "bc"` apart from `"ab" + "c"`. Without it, two different pairs could give the same bytes.
- `HexFormat.of().formatHex(digest.digest())` finishes the hash and writes its 32 bytes as 64 lowercase hex characters.
- `NoSuchAlgorithmException` is a checked exception that the compiler forces you to handle. Every Java platform must provide SHA-256, so it can only mean a broken runtime. The code wraps it in `IllegalStateException`, which signals a bug, not bad input.

### `KeyClaim.java`

```java
package io.plainround.seatwave.api.idempotency;

import java.util.Objects;

/**
 * One request's claim on a key: which endpoint, which key, and what the request means.
 *
 * @param endpoint the operation, for example {@code POST /v1/holds}; keys are scoped to it
 * @param key the client's key
 * @param fingerprint the digest of the request
 */
public record KeyClaim(String endpoint, IdempotencyKey key, Fingerprint fingerprint) {

  /** Checks the fields. */
  public KeyClaim {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(fingerprint, "fingerprint");
  }
}
```

- One request's claim has three parts: the endpoint (keys are scoped to it), the key, and the fingerprint. The three checks refuse null. A claim is built by the `http` layer once it has parsed the header and the body.

### `StoredReply.java`

```java
package io.plainround.seatwave.api.idempotency;

import java.util.Objects;

/**
 * The response a completed request produced, kept under its key so a retry gets it again.
 *
 * @param status the HTTP status code
 * @param location the Location header, or an empty string when the response has none
 * @param body the JSON body
 */
public record StoredReply(int status, String location, String body) {

```

- The reply a finished request produced, kept so a retry gets it again: the HTTP status code, the `Location` header (an empty string when there is none) and the JSON body as text. The package keeps the reply as plain text and does not know JSON.

```java
  // HTTP status codes are three digits (RFC 9110, section 15).
  private static final int LOWEST_STATUS = 100;
  private static final int HIGHEST_STATUS = 599;

  /** Checks the fields. */
  public StoredReply {
    if (status < LOWEST_STATUS || status > HIGHEST_STATUS) {
      throw new IllegalArgumentException("not an HTTP status: " + status);
    }
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(body, "body");
  }
}
```

- HTTP status codes have three digits, so `LOWEST_STATUS` is 100 and `HIGHEST_STATUS` is 599. The constructor refuses anything outside them. The location and body must not be null (an empty string stands for "none").
- The table has `response_body VARCHAR(2048)`, so a body longer than 2,048 characters could not be stored. The replies of this API are small (a hold is about 130 characters).

### `ClaimResult.java`

```java
package io.plainround.seatwave.api.idempotency;

/** What the key store found when a request claimed its key. */
public sealed interface ClaimResult {

  /** The key was new: this request owns it and must do the work. */
  record Claimed() implements ClaimResult {}

  /**
   * The key belongs to an earlier attempt of this same request that did not finish (its process
   * stopped, or its payment call failed). This request may carry the work on; the state of the
   * resource decides whether it can.
   */
  record Resumed() implements ClaimResult {}

  /**
   * The same request already completed: send its response again and do nothing.
   *
   * @param reply the first response
   */
  record Replay(StoredReply reply) implements ClaimResult {}

  /** The key was used before with a different request: refuse (422 in the contract). */
  record BodyMismatch() implements ClaimResult {}
}
```

- A **sealed interface** with four records, one for each answer of a claim.
- `Claimed`: the key was new. This request owns it and must do the work.
- `Resumed`: the key belongs to an earlier attempt of the same request that did not finish. The earlier attempt's process stopped, or its payment call failed. This request may carry the work on; the state of the resource (the seat) decides whether it can.
- `Replay(StoredReply reply)`: the same request already completed. Send the stored reply and do nothing.
- `BodyMismatch`: the key was used before with a different request. Refuse with 422.
- The callers use a `switch` over these four with record patterns, so the compiler forces them to handle every case.

### `IdempotencyStore.java`

```java
package io.plainround.seatwave.api.idempotency;

import io.plainround.seatwave.api.ContractLimits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * The idempotency-key table. Every method works inside the caller's transaction, on the caller's
 * connection: the point of the design is that the key and the effect it records commit or roll back
 * together, so no crash can leave one without the other.
 *
 * <p>Thread-safe: it holds no state; concurrency is decided by the table's primary key, {@code
 * one_effect_per_key}, which lets exactly one transaction insert a given (endpoint, key).
 */
public final class IdempotencyStore {

```

- The class comment states the design: every method works inside the caller's transaction on the caller's connection. The store never calls `commit` or `rollback`. (The purge is the one method that expects an auto-commit connection, so each delete commits by itself.) The key and the effect commit or roll back together.
- "Thread-safe: it holds no state": the class has no fields. Concurrency is decided by the table's primary key, which lets exactly one transaction insert a given `(endpoint, key)`.

```java
  // SQLSTATE class 23 is an integrity violation; 23505 is a unique or primary key violation.
  private static final String UNIQUE_VIOLATION = "23505";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String COMPLETED = "COMPLETED";

```

- `23505` is an **SQLSTATE**: a five-character code, standard across databases, that names the kind of error. Class `23` is an integrity violation (a rule of the table broke). `23505` is a unique or primary key violation.
- `IN_PROGRESS` and `COMPLETED` are the two state texts the table accepts (the check constraint `known_key_state`).

#### `claim`

```java
  /**
   * Claims a key for one request, or reports what an earlier request with the same key left.
   *
   * <p>If another transaction holds an uncommitted claim on the same key, the insert waits for it
   * (up to the database's lock timeout) and then sees its outcome: that is how 100 racing retries
   * resolve into one owner and 99 replays.
   *
   * @param connection the caller's connection, with auto-commit off
   * @param claim the request's claim
   * @param now the current time
   * @return what the request must do next
   * @throws SQLException if the database fails, including a lock timeout
   */
  public ClaimResult claim(Connection connection, KeyClaim claim, Instant now) throws SQLException {
    Objects.requireNonNull(claim, "claim");
    if (tryInsert(connection, claim, now)) {
      return new ClaimResult.Claimed();
    }
```

- The Javadoc describes the race. If another transaction holds an uncommitted claim on the same key, the insert waits for it, up to the database's lock timeout, and then sees its outcome. That is how 100 racing retries resolve into one owner and 99 replays.
- `Objects.requireNonNull(claim, "claim")` guards the argument.
- `tryInsert` (below) returns true if the insert worked. Then this request is the owner: `Claimed`.

```java
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT fingerprint, state, response_status, response_location, response_body,"
                + " expires_at FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?")) {
      query.setString(1, claim.endpoint());
      query.setString(2, claim.key().value());
      try (ResultSet row = query.executeQuery()) {
```

- The insert failed because the row exists, so the code reads it. The query selects the six columns it needs from `idempotency_keys` for this `(endpoint, key)`. The two `?` are filled with the endpoint and the key text.

```java
        if (!row.next()) {
          // The other claim was rolled back between our insert and our read: try once more.
          return tryInsert(connection, claim, now)
              ? new ClaimResult.Claimed()
              : new ClaimResult.Resumed();
        }
```

- If the row is not there, the other claim was rolled back between our insert and our read (the row existed at the insert, then disappeared). Try the insert once more. If it still fails, someone else claimed in between: return `Resumed` so the caller carries on and the state of the resource decides.

```java
        if (row.getLong("expires_at") <= now.toEpochMilli()) {
          // An expired key is forgotten: this request is new, whatever the old one did. The take
          // over is one conditional UPDATE, so of two requests with the same expired key exactly
          // one wins; the other waits for its row lock and then finds the key alive again.
          return takeOverExpired(connection, claim, now)
              ? new ClaimResult.Claimed()
              : resultOfLiveKey(connection, claim);
        }
```

- Expiry check. `row.getLong("expires_at") <= now.toEpochMilli()` compares two numbers: epoch milliseconds. If the key has expired, `takeOverExpired` tries to take the row over. If it did, the result is `Claimed`. If it did not, another request took it over first, and `resultOfLiveKey` reads what that request left. The comment says it plainly: an expired key is forgotten, this request is new whatever the old one did, and of two requests with the same expired key exactly one wins.
- The expiry check comes before the fingerprint check on purpose. A body that differs from an expired key's body is not a mistake.

```java
        return resultOf(row, claim);
      }
    }
  }
```

- A key that has not expired is judged by `resultOf`, shown with the helpers below.

#### `complete`

```java
  /**
   * Records the response of a claimed request; it is sent again to every retry until the key
   * expires. Called in the same transaction as the effect.
   *
   * @param connection the caller's connection, with auto-commit off
   * @param claim the request's claim
   * @param reply the response
   * @return true if this transaction recorded the response; false if another attempt with the same
   *     key completed it first (its response is then the one to send)
   * @throws SQLException if the database fails
   */
  public boolean complete(Connection connection, KeyClaim claim, StoredReply reply)
      throws SQLException {
    Objects.requireNonNull(reply, "reply");
```

- `complete` stores the reply of a request that holds the claim. The Javadoc: it is sent again to every retry until the key expires, and it must be called in the same transaction as the effect. It returns true if this transaction recorded the response, and false if another attempt completed the key first.

```java
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE idempotency_keys SET state = ?, response_status = ?, response_location = ?,"
                + " response_body = ? WHERE endpoint = ? AND idempotency_key = ?"
                + " AND fingerprint = ? AND state = ?")) {
      update.setString(1, COMPLETED);
      update.setInt(2, reply.status());
      update.setString(3, reply.location());
      update.setString(4, reply.body());
      update.setString(5, claim.endpoint());
      update.setString(6, claim.key().value());
      update.setString(7, claim.fingerprint().hex());
      update.setString(8, IN_PROGRESS);
      // The state condition makes completion first-wins, like the seat's conditional UPDATE.
      return update.executeUpdate() == 1;
    }
  }
```

- `UPDATE idempotency_keys SET state = ?, response_status = ?, response_location = ?, response_body = ? WHERE endpoint = ? AND idempotency_key = ? AND fingerprint = ? AND state = ?` writes the reply.
- The last condition is `AND state = ?` with `IN_PROGRESS`. Only a row that is still in progress can be completed. The comment says this makes completion **first-wins**, like the seat's conditional `UPDATE`: the second attempt matches 0 rows and gets false.
- The condition on the fingerprint makes sure an attempt cannot complete a key that another body owns.
- The database also checks the table rule `completed_key_has_response`: a `COMPLETED` row must have a status and a body. The update writes both, so it passes.

#### `purgeExpired`

```java
  /**
   * Deletes keys whose retention has passed, at most a batch at a time, so one purge never holds
   * locks on the whole table.
   *
   * @param connection a connection in auto-commit mode
   * @param now the current time
   * @param batchLimit the most keys to delete
   * @return how many keys were deleted
   * @throws SQLException if the database fails
   */
  public int purgeExpired(Connection connection, Instant now, int batchLimit) throws SQLException {
    try (PreparedStatement delete =
        connection.prepareStatement("DELETE FROM idempotency_keys WHERE expires_at <= ? LIMIT ?")) {
      delete.setLong(1, now.toEpochMilli());
      delete.setInt(2, batchLimit);
      return delete.executeUpdate();
    }
  }
```

- `DELETE FROM idempotency_keys WHERE expires_at <= ? LIMIT ?` removes expired rows, at most a batch at a time. `LIMIT` caps the number of rows. The Javadoc says why: one purge never holds locks on the whole table. `SeatWaveApi` runs it once a minute with a batch of 10,000. It returns how many rows were deleted, which the caller logs (`purged {} expired idempotency keys`).
- It takes a connection in auto-commit mode, so each delete commits by itself.

#### `count`

```java
  /**
   * Counts the keys held, for the store-size metric.
   *
   * @param connection any connection
   * @return the number of rows
   * @throws SQLException if the database fails
   */
  public long count(Connection connection) throws SQLException {
    try (PreparedStatement query =
            connection.prepareStatement("SELECT COUNT(*) FROM idempotency_keys");
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
```

- `SELECT COUNT(*) FROM idempotency_keys` gives the number of stored keys. It feeds the metric `seatwave_idempotency_keys_stored`. Note the `try` with two resources separated by a semicolon: both the statement and the result set are closed. `rows.next()` moves to the single result row and `rows.getLong(1)` reads its first column.

#### `tryInsert`

```java
  private static boolean tryInsert(Connection connection, KeyClaim claim, Instant now)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO idempotency_keys (endpoint, idempotency_key, fingerprint, state,"
                + " created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, claim.endpoint());
      insert.setString(2, claim.key().value());
      insert.setString(3, claim.fingerprint().hex());
      insert.setString(4, IN_PROGRESS);
      insert.setLong(5, now.toEpochMilli());
      insert.setLong(6, now.plus(ContractLimits.KEY_RETENTION).toEpochMilli());
      insert.executeUpdate();
      return true;
```

- `INSERT INTO idempotency_keys (endpoint, idempotency_key, fingerprint, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)` writes the claim: state `IN_PROGRESS`, created now, expires at now plus 24 hours (`ContractLimits.KEY_RETENTION`). The three response columns stay `NULL`.
- If `executeUpdate()` does not throw, the method returns true.

```java
    } catch (SQLException insertFailure) {
      // H2 undoes only the failed statement and keeps the transaction usable; PostgreSQL would
      // abort the whole transaction, so there this insert would run inside a savepoint.
      if (UNIQUE_VIOLATION.equals(insertFailure.getSQLState())) {
        return false;
      }
      throw insertFailure;
    }
  }
```

- The `catch` turns exactly one error into a return value: SQLSTATE `23505`, "the row already exists", becomes `false`. Any other `SQLException` (a lock timeout, a full disk) is rethrown. This is how the code uses the database's refusal as its decision.
- The comment is a warning for other databases. H2 undoes only the failed statement and keeps the transaction usable. PostgreSQL aborts the whole transaction after a failed statement, so there this insert would have to run inside a savepoint (a partial rollback point).

#### `takeOverExpired`, `resultOfLiveKey` and `resultOf`

```java
  private static boolean takeOverExpired(Connection connection, KeyClaim claim, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE idempotency_keys SET fingerprint = ?, state = ?, response_status = NULL,"
                + " response_location = NULL, response_body = NULL, created_at = ?, expires_at = ?"
                + " WHERE endpoint = ? AND idempotency_key = ? AND expires_at <= ?")) {
      update.setString(1, claim.fingerprint().hex());
      update.setString(2, IN_PROGRESS);
      update.setLong(3, now.toEpochMilli());
      update.setLong(4, now.plus(ContractLimits.KEY_RETENTION).toEpochMilli());
      update.setString(5, claim.endpoint());
      update.setString(6, claim.key().value());
      // The expiry is checked again here, under the row lock, not only in the read before it.
      update.setLong(7, now.toEpochMilli());
      return update.executeUpdate() == 1;
    }
  }
```

- `takeOverExpired` rewrites the expired row for this request in one statement: the new fingerprint, the state `IN_PROGRESS`, the three response columns emptied with `NULL`, a new creation time and a new expiry 24 hours on. The `WHERE` names the row by its primary key and adds `expires_at <= ?`. The comment says why that last condition matters: the database checks it again after it has the row's lock, so a request that waited for another's take over sees the row as it is now, alive, and changes nothing. `executeUpdate() == 1` is true when this request won.

```java
  private static ClaimResult resultOfLiveKey(Connection connection, KeyClaim claim)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT fingerprint, state, response_status, response_location, response_body"
                + " FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?")) {
      query.setString(1, claim.endpoint());
      query.setString(2, claim.key().value());
      try (ResultSet row = query.executeQuery()) {
        // The winner of the take over committed its row; a purge cannot remove a live key.
        if (!row.next()) {
          throw new IllegalStateException("a key taken over by another request disappeared");
        }
        return resultOf(row, claim);
      }
    }
  }
```

- `resultOfLiveKey` reads the row again after a lost take over. The row must be there: the winner committed it with a fresh expiry, and the purge removes only expired rows, so a missing row would be a bug and is thrown as one (the comment says so). Otherwise it hands the row to `resultOf`.

```java
  private static ClaimResult resultOf(ResultSet row, KeyClaim claim) throws SQLException {
    if (!row.getString("fingerprint").equals(claim.fingerprint().hex())) {
      return new ClaimResult.BodyMismatch();
    }
    if (COMPLETED.equals(row.getString("state"))) {
      return new ClaimResult.Replay(
          new StoredReply(
              row.getInt("response_status"),
              row.getString("response_location"),
              row.getString("response_body")));
    }
    return new ClaimResult.Resumed();
  }
}
```

- `resultOf` judges a live row. `row.getString("fingerprint").equals(claim.fingerprint().hex())`: if the stored fingerprint differs from this request's, the answer is `BodyMismatch`. Nothing is written.
- If the stored state is `COMPLETED`, the code builds a `StoredReply` from the three response columns and returns `Replay`. `COMPLETED.equals(...)` is written with the constant first, which cannot fail on a null.
- Anything else is a row that is `IN_PROGRESS` with the same fingerprint: `Resumed`.

## Try it yourself

All commands run in a scratch copy of the repository. Timings and ids differ on every run.

**1. Watch a retry replay.** With the API and the stand-in provider running (see `java-booking-walkthrough.md`), send the same hold twice:

```text
curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "demo-1"' -d '{"eventId":1,"seat":"C-3","customer":"fan_1"}'
```

Both calls print the same text, with the same `holdId` and the same `expiresAt`. In my run the second call took the `replayed` path: `curl -s http://127.0.0.1:8087/metrics | grep outcomes` showed `outcome="first"` and `outcome="replayed"` counters for `POST /v1/holds`.

**2. Reuse the key with another seat.**

```text
curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "demo-1"' -d '{"eventId":1,"seat":"C-4","customer":"fan_1"}'
```

```text
{"type":"https://seatwave.example/problems/idempotency-key-reused","title":"This Idempotency-Key was used with another body","status":422,"detail":"use a new key for a new request","instance":"urn:uuid:987cc119-a481-4952-9774-113a32909bc3"}
```

The `instance` changes on every call. The counter `outcome="key_reused"` goes up by one.

**3. Send a confirm that cannot be answered, then retry the same key.** Set the stand-in to `fail` (`curl -s -X POST 'http://127.0.0.1:8088/control/mode?mode=fail'`), confirm a hold, and read `/metrics`: `outcome="payment_unavailable"` rises, and `seatwave_idempotency_keys_stored` counts the key row that step 1 committed as `IN_PROGRESS`. Switch the mode to `ok` and send the same confirm: it succeeds with `201`. The key is not forgotten, and nothing was stored as a reply for the 503.

**4. Race two inserts by hand in H2.** This small program (save it as `Race.java` in a scratch folder) shows the primary key doing the work. It makes a table whose primary key is `(endpoint, k)`, starts one transaction that inserts a row without committing, and then tries the same insert from a second connection:

```text
import java.sql.*;
import java.util.concurrent.*;

public class Race {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:h2:mem:race";
    Connection setup = DriverManager.getConnection(url);
    setup.createStatement().execute(
        "CREATE TABLE keys (endpoint VARCHAR(32), k VARCHAR(64), PRIMARY KEY (endpoint, k))");
    Connection first = DriverManager.getConnection(url);
    Connection second = DriverManager.getConnection(url);
    first.setAutoCommit(false);
    second.setAutoCommit(false);
    String insert = "INSERT INTO keys VALUES ('POST /v1/holds', 'fan-7-hold-1')";
    first.createStatement().execute(insert);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<String> racer = pool.submit(() -> {
      try {
        second.createStatement().execute(insert);
        return "inserted";
      } catch (SQLException refused) {
        return "refused, SQLState " + refused.getSQLState();
      }
    });
    Thread.sleep(300);
    System.out.println("racer still waiting: " + !racer.isDone());
    first.commit();
    System.out.println("racer after the first commit: " + racer.get());
    pool.shutdown();
  }
}
```

Run it with the H2 jar from the Gradle cache on the class path, `java -cp <path to h2-2.5.252.jar> Race.java`. Output on my machine:

```text
racer still waiting: true
racer after the first commit: refused, SQLState 23505
```

The second insert stood still until the first transaction ended. Then it was refused with the duplicate code that `IdempotencyStore.tryInsert` turns into `false`. If you change `first.commit()` to `first.rollback()`, the racer is let in and prints `inserted`.

**5. Run the tests that prove the guarantees.** In a scratch copy of the repository:

```text
./gradlew test --tests '*IdempotencyStoreTest*' --tests '*HoldRetryTest*'
```

The tail of the output on my machine was `BUILD SUCCESSFUL in 3s`, with each test marked `PASSED`. Gradle prints the test names, so you can see which behavior each one checks.

## Mistakes people make

- **Making the key row in a different transaction from the effect.** A crash in between loses the reply or lies about it. Here one `commit()` covers both.
- **Checking "does the key exist" with a `SELECT`, then inserting.** Two requests both see "no" and both insert. Here the insert comes first and the database's primary key decides who wins.
- **Hashing the raw body bytes.** A client that reorders JSON fields would be refused with 422. Here the bound record is written back out before hashing, so only the meaning counts.
- **Storing every outcome.** A 503 is not a final answer. If it were stored, a retry could never succeed. Here refusals (`PaymentUnavailable`, `ConfirmInProgress`, `KeyReusedWithOtherBody`) are never stored; the final outcomes (created, not found, seat taken) are.
- **Keeping keys forever.** The table would grow without limit. Here every key expires after 24 hours and a purge deletes them in batches.
- **Scoping keys only by the key text.** The same key on two endpoints would collide. Here the endpoint is part of the primary key. (A real service also scopes by the authenticated caller; the README lists this as simplified for teaching.)

## Check yourself

1. Why does `claim` insert first and read second? (The insert is atomic. If it works, this request is the owner. If it fails with a duplicate, the row exists and the code reads it. Reading first would let two requests both see "no row".)
2. What does the client get for the same key and the same body after the first request finished? (The stored reply: the same status, location and body. `Source.REPLAY`.)
3. What does the client get for the same key and a different seat? (422 with the type `idempotency-key-reused`. Nothing is stored or changed.)
4. Why is `complete` first-wins? (Its `WHERE` includes `state = 'IN_PROGRESS'`. Only one transaction can move a row out of that state; the other matches 0 rows and returns false.)
5. What happens to a key 25 hours after its first use? (Its `expires_at` has passed. A request that presents it is treated as new: the old row is deleted, a new claim is inserted. A background purge also deletes such rows.)

## Further reading

- The IETF draft that defines the `Idempotency-Key` header (draft-ietf-httpapi-idempotency-key-header): https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- RFC 9651, Structured Field Values for HTTP (the String syntax the header uses): https://www.rfc-editor.org/rfc/rfc9651
- RFC 9110, HTTP Semantics (idempotent methods, section 9.2.2; status codes, section 15): https://www.rfc-editor.org/rfc/rfc9110
- `java.security.MessageDigest` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html
- `java.util.HexFormat` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/HexFormat.html
- `java.sql.SQLException` and `getSQLState` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/SQLException.html
- H2 2.5.252 error codes (23505 and 50200): https://h2database.com/javadoc/org/h2/api/ErrorCode.html
