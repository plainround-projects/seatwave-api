# H2, SQL and migrations (the database under SeatWave)

## What it is

A **relational database** is a program that stores data in **tables** and answers questions about it in a language called **SQL**. A table is a grid. Each **column** has a name and a type (a whole number, text, a date). Each **row** is one record. The table `seats` has one row per seat and one column for the seat's label, its status, who holds it, and when the hold ends.

**SQL** (Structured Query Language) is the language you use to talk to such a database. You write statements. `SELECT` reads rows. `INSERT` adds a row. `UPDATE` changes rows. `DELETE` removes rows. `CREATE TABLE` and `ALTER TABLE` change the shape of the tables themselves.

**H2** is a relational database written in Java. This repository pins version 2.5.252 (`gradle/libs.versions.toml`). It is **embedded**: it runs inside the same Java program as the server, as a library, not as a separate server you install. You give it an address, called a JDBC URL, and it either keeps the data in memory (gone when the program stops) or in a file (kept).

A **migration** is an SQL file that changes the database from one shape to the next, and is run exactly once. This repository has three, under `src/main/resources/db/migration/`. A fourth file in the same folder tree, `src/main/resources/logback.xml`, is not SQL; it configures log output and is explained at the end of this file.

## Why this repository uses it

The lesson of Module 7 depends on a database that can say "no" reliably. The rules that stop a double booking are not in the Java code alone. A primary key refuses a second row. A unique constraint refuses a second booking for one seat. A conditional `UPDATE` changes a row only if it is still in the state the request expects. A transaction makes the key row and the seat change appear together. H2 gives all of this with nothing to install, so a learner can run the retry storm on a laptop. The code treats the database as the final judge and reads its answers (a count of changed rows, an error code).

## Where it appears

| File | What it does with SQL or H2 |
|---|---|
| `src/main/resources/db/migration/V1__module_1_schema.sql` | Module 1's three tables and one index. |
| `src/main/resources/db/migration/V2__idempotency_and_payment.sql` | The key table, the lease column, the status check, the payment columns of `bookings`. |
| `src/main/resources/db/migration/V3__seat_order_for_cursors.sql` | The numeric seat order and two indexes for cursor paging. |
| `src/main/resources/db/migration/V4__seats_by_hold.sql` | The index that finds a seat by its hold id. |
| `src/main/resources/logback.xml` | Not SQL: the log format. |
| `store/Database.java` | Opens H2 and runs the migrations at start-up. |
| `booking/SeatLedger.java`, `idempotency/IdempotencyStore.java`, `catalog/CatalogService.java` | The statements the program sends. |
| `config/serve.properties` and the other settings files | The `database.url` key. |

## How it works underneath

### Tables, types and NULL

Every column has a type. The ones used here:

| Type | Meaning in this repository |
|---|---|
| `BIGINT` | A 64-bit whole number. Ids, and every time (epoch milliseconds). |
| `INT` | A 32-bit whole number. Seat number, an HTTP status. |
| `VARCHAR(n)` | Text of at most n characters. |
| `CHAR(n)` | Text of exactly n characters. The fingerprint (64), the currency (3). |
| `DECIMAL(12, 2)` | An exact decimal number with up to 12 digits, 2 after the point. Money. |

**NULL** means "no value". A column declared `NOT NULL` must always have one. A column without it may be NULL, which is how a seat with no hold has `hold_id` NULL.

Times are stored as **epoch milliseconds** in a `BIGINT`: the number of milliseconds since 1 January 1970 UTC. This is the same form Java's `Instant.toEpochMilli()` gives, and it compares with plain `<` and `>`.

H2 writes the names of unquoted tables and columns in upper case internally (`SEATS`, `EVENT_ID`). SQL statements are not case sensitive for these names, so `seats` and `SEATS` are the same table.

### Keys, constraints and indexes

- A **primary key** names the column or columns that identify a row. No two rows may have the same value, and none may be NULL. `events.event_id` is the primary key of `events`. `seats` has a **composite** primary key of two columns, `(event_id, seat_label)`: seat `A-7` of event 1 and seat `A-7` of event 2 are different seats.
- A **unique constraint** says no two rows may have the same value in some columns. It is a primary key without the "identifies the row" role.
- A **check constraint** says every row must satisfy an expression, for example `state IN ('IN_PROGRESS', 'COMPLETED')`. The database refuses any insert or update that would break it.
- A **constraint name** (`CONSTRAINT one_booking_per_seat ...`) shows up in the error message, so a failure is easy to read.
- An **index** is a sorted copy of some columns that lets the database find rows without reading the whole table. A primary key and a unique constraint each make an index. Without a suitable index, a `WHERE` that names other columns makes the database scan every row.

The database reports a broken rule with an **SQLSTATE**: a five-character code. `23505` is a unique or primary key violation. `23513` is a check constraint violation. I ran the first three migrations on H2 2.5.252 and tried to break each rule; the database answered:

```text
same key twice: refused, SQLState 23505, Unique index or primary key violation: PUBLIC.ONE_EFFECT_PER_KEY
bad key state: refused, SQLState 23513, Check constraint violation: KNOWN_KEY_STATE:
completed with no reply: refused, SQLState 23513, Check constraint violation: COMPLETED_KEY_HAS_RESPONSE:
bad seat status: refused, SQLState 23513, Check constraint violation: KNOWN_SEAT_STATUS:
same seat twice: refused, SQLState 23505, Unique index or primary key violation: PUBLIC.CONSTRAINT_4
second booking of one seat: refused, SQLState 23505, Unique index or primary key violation: PUBLIC.ONE_BOOKING_PER_SEAT
```

### Statements the program sends

| Statement | Reads as |
|---|---|
| `SELECT a, b FROM t WHERE c = ?` | Give me columns a and b of the rows of t where c equals the value. |
| `SELECT COUNT(*) FROM t` | How many rows does t have? |
| `INSERT INTO t (a, b) VALUES (?, ?)` | Add one row. |
| `UPDATE t SET a = ? WHERE c = ?` | In every row where c matches, set a. It reports how many rows it changed. |
| `DELETE FROM t WHERE c = ? LIMIT ?` | Remove the matching rows, at most this many. |

The `?` is a **parameter**: a hole that the program fills with a value when it runs the statement. Using parameters, and never gluing user text into SQL, is what stops **SQL injection** (a person writing SQL into a form field). Every statement in `SeatLedger`, `IdempotencyStore` and `Database.isApplied` uses parameters. The one place SQL is built from a string, `Database.migrate`, joins a file name from a fixed list, not input.

### A transaction

A **transaction** is a group of statements that the database treats as one. The classic promise is called **ACID**:

- **Atomic**: all of it happens, or none. A crash in the middle undoes it.
- **Consistent**: the constraints hold before and after.
- **Isolated**: other transactions do not see half of it.
- **Durable**: once `COMMIT` returns, the data survives a crash (for a file database).

`COMMIT` makes the transaction permanent. `ROLLBACK` throws its changes away. In JDBC a connection starts in **auto-commit** mode, where every statement is its own transaction. `setAutoCommit(false)` starts a transaction that lasts until `commit()` or `rollback()`. I checked on H2 2.5.252 that a connection starts with auto-commit on and the isolation level `READ_COMMITTED`.

**READ_COMMITTED** means a transaction sees only data that other transactions have committed. It never sees their half-finished work.

### A row lock

When a transaction changes a row, the database **locks** that row until the transaction ends. A second transaction that wants to change the same row **waits**. When the first commits or rolls back, the second goes on. When the wait is longer than the `LOCK_TIMEOUT` setting of the URL (2,000 ms in `config/serve.properties`), the second fails with error code `50200`.

For an `UPDATE ... WHERE condition`, H2 locks the row and then checks the condition again with the latest committed data. That recheck is what makes a conditional `UPDATE` safe against a race. I ran it: connection X changed a seat from `AVAILABLE` to `HELD` without committing. Connection Y ran the same conditional update and waited (`y waiting: true`). After X committed, Y finished and its update reported 0 rows changed. One more fact from that run: Y's zero-row update still held the lock until Y's transaction ended, and a third statement on the row timed out with `50200` until Y was rolled back. So always end a transaction.

The same mechanism sorts out a race for a primary key. Two transactions insert the same key. The second waits for the first. If the first commits, the second fails with `23505`. If the first rolls back, the second succeeds. I ran that too; see `java-idempotency-walkthrough.md`.

### The JDBC URL

`jdbc:h2:mem:try;LOCK_TIMEOUT=2000` reads as: JDBC, driver H2, an in-memory database named `try`, with the setting `LOCK_TIMEOUT=2000`. `jdbc:h2:file:./build/data/seatwave-api;LOCK_TIMEOUT=2000` keeps the data in a file under `build/data/`. An in-memory database lives only while a connection to it is open (`Database` keeps one open for that reason). A file database can be opened by one process at a time: I started a second process on one file and got `Database may be already in use`. `ApiSettings` accepts only URLs that match `jdbc:h2:(mem|file):` followed by letters, digits, and `_ . / ; = -`.

### Migrations

A migration is a numbered SQL file. The names are `V1__module_1_schema.sql`, `V2__idempotency_and_payment.sql`, `V3__seat_order_for_cursors.sql`, `V4__seats_by_hold.sql`: the letter V, a version number, two underscores, a description.

**How they run at start-up.** `SeatWaveApi.start` calls `Database.open(url)`, which calls `migrate`. `migrate` creates the table `schema_history` (version number and script name) if it does not exist, then goes through the list of four file names. For each one, if its version is already in `schema_history`, it skips it; if not, it runs the file with the H2 command `RUNSCRIPT FROM 'classpath:/db/migration/<name>'` and records the version. `DatabaseTest` runs this on a fresh file database and on the same file again: the first run applies all four, the second skips all four, and `schema_history` holds the same four rows.

**Why every statement can run twice.** Running a script and recording it are two separate commits, because H2 commits a schema statement the moment it runs. A crash between the two leaves the schema changed and the history without its row, so the next start runs the script again. Every statement is therefore written so that a second run does nothing: `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` and `ADD CONSTRAINT IF NOT EXISTS` skip what is already there, and V3's `UPDATE` writes the same values again. Without `IF NOT EXISTS`, the second run stopped at the first statement with `Table "EVENTS" already exists` and the program could not start. The same property covers a crash in the middle of a script. A fresh database ends with five tables in the `PUBLIC` schema: `events`, `seats`, `bookings`, `idempotency_keys` and `schema_history`.

**Why migrations only add.** The rule in `Database.java` is that a new migration is appended, and a released one is never changed in what it creates. Two reasons.
1. A database that already ran version 2 will never run version 2 again. If you change what the file creates, new databases and old databases silently become different. (Adding `IF NOT EXISTS` was allowed for that reason: it changes no object the file creates, only whether a second run fails.)
2. While a new version of the program rolls out, the old version is still running against the same database. A change that only **adds** (a new table, a new nullable column, a new index) leaves the old program working. A change that removes or renames a column breaks it at once. The safe order is called **expand and contract**: first add the new shape (expand), move the data, switch the program over, and only much later remove the old shape (contract). The comments in V2 and V3 say "Expand only" and "expand and migrate". This repository has no contract step: nothing is ever dropped.

**What a migration can and cannot undo.** The migration folder has no "down" scripts. To undo a mistake you write the next version, here version 5. H2 runs a schema change as soon as it is sent, outside any rollback, so a script that stops half way leaves half the changes; because every statement can run twice, the next start finishes the rest.

## Walkthrough of this repository's use

### `V1__module_1_schema.sql`

```sql
-- Module 1's three tables, unchanged: the API in front of them changes, the rule inside them does not.
CREATE TABLE IF NOT EXISTS events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at BIGINT NOT NULL
);
```

- Line 1 is a comment: SQL comments start with `--` and run to the end of the line. Module 1's three tables arrive unchanged. The API in front of them changes, the rule inside does not.
- `CREATE TABLE IF NOT EXISTS events (...)` makes a table. The column list is in parentheses, separated by commas. `IF NOT EXISTS` makes the statement do nothing when the table is already there, so the file can run a second time after a crash (see "Why every statement can run twice").
- `event_id BIGINT PRIMARY KEY` makes the id a whole number that identifies the row.
- `name VARCHAR(120) NOT NULL` is the event name, at most 120 characters, always present.
- `starts_at BIGINT NOT NULL` is the start time as epoch milliseconds.

```sql
-- The status column is the single source of truth for a seat. Java never decides alone.
CREATE TABLE IF NOT EXISTS seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  section VARCHAR(4) NOT NULL,
  status VARCHAR(12) NOT NULL,
  hold_id VARCHAR(40),
  hold_customer VARCHAR(64),
  hold_expires_at BIGINT,
  booking_id VARCHAR(40),
  PRIMARY KEY (event_id, seat_label)
);
```

- The comment on line 8 is the design rule of Module 1: the `status` column is the single source of truth for a seat. Java never decides alone. The program reads the database's answer to a conditional update, it does not hold a copy of the status.
- `event_id BIGINT NOT NULL` and `seat_label VARCHAR(16) NOT NULL` say which seat, for example event 1 and `A-7`.
- `section VARCHAR(4) NOT NULL` is the section letter, kept as its own column so the catalog can filter by section.
- `status VARCHAR(12) NOT NULL` is one of the status words. `CONFIRMING` has 10 letters, the longest, so 12 is enough.
- `hold_id VARCHAR(40)`, `hold_customer VARCHAR(64)`, `hold_expires_at BIGINT`, `booking_id VARCHAR(40)` have no `NOT NULL`: an available seat has none of them. A UUID is 36 characters, so 40 fits.
- `PRIMARY KEY (event_id, seat_label)` is the composite key. It also gives an index, which is why finding a seat by event and label is fast.

```sql
-- Second guard: even a bug in the confirm logic cannot create two bookings for one seat.
CREATE TABLE IF NOT EXISTS bookings (
  booking_id VARCHAR(40) PRIMARY KEY,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer VARCHAR(64) NOT NULL,
  confirmed_at BIGINT NOT NULL,
  CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label)
);

CREATE INDEX IF NOT EXISTS seats_by_section ON seats (event_id, section);
```

- The comment: this is the second guard. Even a bug in the confirm logic cannot create two bookings for one seat.
- `bookings` has one row per paid seat: id, event, seat label, customer, and the confirm time.
- `CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label)` refuses a second row with the same event and seat. The test above showed `UNIQUE ... ONE_BOOKING_PER_SEAT` refusing a second booking.
- `CREATE INDEX IF NOT EXISTS seats_by_section ON seats (event_id, section)` makes an index to find the seats of one section of one event quickly (Module 1's seat list).

### `V2__idempotency_and_payment.sql`

```sql
-- Module 7: a retried write must not act twice, and a confirm now takes a payment (Module 2).
-- Expand only: new table, new nullable columns. Module 1 code would still run against this schema.

-- One row per (endpoint, key). The primary key is the invariant: one key, at most one effect.
-- The row is written in the same transaction as the effect it records.
```

- Lines 1 and 2 say what this migration is for: a retried write must not act twice, and a confirm now takes a payment. They also say "Expand only: new table, new nullable columns. Module 1 code would still run against this schema." That is the "only add" rule in action: nothing here makes the old program fail.
- Lines 4 and 5 are the comment of the key table: the primary key is the invariant (one key, at most one effect) and the key row is written in the same transaction as the effect.

```sql
CREATE TABLE IF NOT EXISTS idempotency_keys (
  endpoint VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  state VARCHAR(12) NOT NULL,
  response_status INT,
  response_location VARCHAR(80),
  response_body VARCHAR(2048),
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  CONSTRAINT one_effect_per_key PRIMARY KEY (endpoint, idempotency_key),
  CONSTRAINT known_key_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT completed_key_has_response
    CHECK (state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
```

- `CREATE TABLE IF NOT EXISTS idempotency_keys (...)`: one row per `(endpoint, key)`.
- `endpoint VARCHAR(32) NOT NULL` holds `POST /v1/holds` or `POST /v1/bookings`.
- `idempotency_key VARCHAR(64) NOT NULL` is the client's key (1 to 64 characters, the same limit as the contract).
- `fingerprint CHAR(64) NOT NULL` is the SHA-256 digest of the request in hexadecimal: exactly 64 characters.
- `state VARCHAR(12) NOT NULL` is `IN_PROGRESS` or `COMPLETED`.
- `response_status INT`, `response_location VARCHAR(80)`, `response_body VARCHAR(2048)` hold the stored reply. They are NULL until the request completes. A location is at most about 50 characters (`/v1/bookings/` plus a UUID), so 80 is enough.
- `created_at BIGINT NOT NULL` and `expires_at BIGINT NOT NULL` are epoch milliseconds. Expiry is creation plus 24 hours.
- `CONSTRAINT one_effect_per_key PRIMARY KEY (endpoint, idempotency_key)` is the line the whole lesson rests on. Naming the primary key as a constraint gives it a readable name in error messages. The database will refuse a second row with the same pair, even under 100 simultaneous inserts.
- `CONSTRAINT known_key_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))` refuses any other state word. `IN (...)` means "equal to one of these".
- `CONSTRAINT completed_key_has_response CHECK (state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))` reads: either the state is not COMPLETED (`<>` means "not equal"), or both the status and the body are present. So a row cannot claim to be finished while holding no reply. `IS NOT NULL` is the way to test for a value; `= NULL` never works in SQL.

```sql
-- The purge deletes by expiry; without this index it would scan every key.
CREATE INDEX IF NOT EXISTS idempotency_keys_by_expiry ON idempotency_keys (expires_at);
```

- The purge deletes by expiry. Without the index `idempotency_keys_by_expiry` on `expires_at`, `DELETE ... WHERE expires_at <= ?` would scan every key. With 100,000 keys the README measures 233 bytes per stored key, table and indexes together.

```sql
-- While a confirm waits for the payment provider, the seat is CONFIRMING until this time.
-- After it, another attempt may take over; the provider's own key stops a second charge.
ALTER TABLE seats ADD COLUMN IF NOT EXISTS confirm_lease_until BIGINT;

ALTER TABLE seats ADD CONSTRAINT IF NOT EXISTS known_seat_status
  CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMING', 'CONFIRMED'));
```

- `ALTER TABLE seats ADD COLUMN IF NOT EXISTS confirm_lease_until BIGINT;` adds a column to an existing table, unless a column of that name is already there. It is nullable, so existing rows get NULL and the old program does not care. The comments give its meaning: while a confirm waits for the payment provider, the seat is `CONFIRMING` until this time. After it, another attempt may take over, and the provider's own key stops a second charge.
- `ALTER TABLE seats ADD CONSTRAINT IF NOT EXISTS known_seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMING', 'CONFIRMED'))` makes the database refuse any other status. Before V2 the program alone guarded the words. The check also makes the `IllegalStateException` in `SeatLedger.stateOf` unreachable.

```sql
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS hold_id VARCHAR(40);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payment_id VARCHAR(64);
-- Money as an exact decimal, never a floating-point number.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS amount DECIMAL(12, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS currency CHAR(3);
```

- Four columns are added to `bookings`: `hold_id VARCHAR(40)`, `payment_id VARCHAR(64)`, `amount DECIMAL(12, 2)` and `currency CHAR(3)`. All are nullable, because older rows have no payment (Module 1 booked without one).
- The comment above `amount`: money as an exact decimal, never a floating-point number. A floating-point number cannot store 0.10 exactly, and tiny errors add up across many bookings. `DECIMAL(12, 2)` can hold up to 9,999,999,999.99.

### `V3__seat_order_for_cursors.sql`

```sql
-- Module 7: cursor pagination needs a stable, unique order that matches the seat map.
-- seat_label sorts as text (A-1, A-10, A-100, A-2), so a numeric column is added and filled
-- from the label (expand and migrate); new seats get it from the code that inserts them.
ALTER TABLE seats ADD COLUMN IF NOT EXISTS seat_number INT;
```

- Cursor pagination (a page ends where the last seat the client saw ends) needs a stable, unique order that matches the seat map. The label cannot give it. As **text**, `A-1`, `A-10`, `A-100`, `A-2` sort in that order, because text sorts character by character. The seat map wants 1, 2, 3 ... 10. So a numeric column is added.
- `ALTER TABLE seats ADD COLUMN IF NOT EXISTS seat_number INT;` adds it (or does nothing on a second run), nullable at first, so the statement works on a table that already has rows.

```sql
UPDATE seats SET seat_number = CAST(SUBSTRING(seat_label, 3) AS INT);
```

- `UPDATE seats SET seat_number = CAST(SUBSTRING(seat_label, 3) AS INT);` fills the new column for every existing row. `SUBSTRING(seat_label, 3)` takes the text from the third character on (counting from 1): `A-7` becomes `7`, `T-1000` becomes `1000`. `CAST(... AS INT)` turns that text into a number. This is the **migrate** step of expand and migrate. On a new empty database it changes nothing.

```sql
ALTER TABLE seats ALTER COLUMN seat_number SET NOT NULL;
```

- `ALTER TABLE seats ALTER COLUMN seat_number SET NOT NULL;` now that every row has a value, the column is forbidden to be empty. This succeeds only because the previous statement filled it. New seats get the number from `DemoSeed`, which inserts it with every row.

```sql
-- The index the keyset query walks: equality on the event, then the order of the cursor.
CREATE INDEX IF NOT EXISTS seats_in_map_order ON seats (event_id, section, seat_number);

-- The event list is paged by start time, then id, so two events at one time keep an order.
CREATE INDEX IF NOT EXISTS events_in_start_order ON events (starts_at, event_id);
```

- `CREATE INDEX IF NOT EXISTS seats_in_map_order ON seats (event_id, section, seat_number)` is the index the keyset query walks: equality on the event, then the order of the cursor (section, then number). The next page is "the next rows after this position", found by the index, not by counting rows.
- `CREATE INDEX IF NOT EXISTS events_in_start_order ON events (starts_at, event_id)` does the same for the event list, which is paged by start time and then id, so two events at one time keep a stable order.

### `V4__seats_by_hold.sql`

```sql
-- Module 7: reading, confirming and cancelling a hold find its seat by hold_id. Without an index
-- each of those queries scans every seat of every event.
CREATE INDEX IF NOT EXISTS seats_by_hold ON seats (hold_id);
```

- Lines 1 and 2 are the comment: the queries that read, confirm and cancel a hold look for the seat by `hold_id` (`SeatLedger.findHold`, `leaseForConfirm`, `markConfirmed`, `cancel` and `releaseLease`), and without an index each of them reads every seat of every event.
- `CREATE INDEX IF NOT EXISTS seats_by_hold ON seats (hold_id)` makes that index. The file does only this: it adds, so the program that ran before it still works. Most seats have no hold, so most entries hold `NULL`; an index on a nullable column is allowed and a lookup by a real hold id skips them. Try 3 below shows the plan before and after, and `DatabaseTest` fails if any of the five lookups goes back to a table scan.

### `src/main/resources/logback.xml`

This file is not SQL. It is in the same resources folder, and it is read by the logging library, Logback, when the first log line is written. **XML** is a text format made of nested tags: `<name>text</name>`, and `<!-- ... -->` is a comment. See `slf4j-and-logback.md` for the logging library itself.

```xml
<configuration>
  <!-- One line per request with its correlation id, which is also the instance of any problem body. -->
  <!-- Log lines go to standard error, so a tool's report on standard output stays clean. -->
```

- `<configuration>` is the root element. The two comments say what to expect: one log line per request with its correlation id (the same id is the `instance` of any problem reply, so a client can quote it and a person can find the line), and log lines go to standard error so a tool's report on standard output stays clean (the `serve` command prints its own lines on standard output).

```xml
  <appender name="console" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-none}] %logger{0} - %msg%n</pattern>
    </encoder>
  </appender>
```

- `<appender name="console" class="ch.qos.logback.core.ConsoleAppender">` defines where log lines go: an appender called `console`, of the class that writes to the console.
- `<target>System.err</target>` chooses standard error instead of the default standard output.
- `<encoder>` with `<pattern>` is the line layout. `%d{HH:mm:ss.SSS}` is the time with milliseconds. `%-5level` is the level (`INFO`, `WARN`) padded to five characters. `[%X{correlationId:-none}]` prints the value called `correlationId` that the HTTP layer put in the logging context for this request, or `none` when there is none. `%logger{0}` is the short name of the class that wrote the line. `%msg` is the message and `%n` a line break. A real line from my session was `01:39:03.213 WARN  [45dc01b3-e796-46eb-8588-7604bc0fd3bd] ConfirmService - payment not confirmed (PROVIDER_ERROR); lease ended, key kept for the retry`.

```xml
  <root level="INFO">
    <appender-ref ref="console"/>
  </root>
</configuration>
```

- `<root level="INFO">` sets the lowest level that is written: `INFO` and above (`WARN`, `ERROR`). `DEBUG` lines are dropped. `<appender-ref ref="console"/>` connects the root logger to the appender above. `</configuration>` closes the file.

## Try it yourself

All commands run in a scratch copy of the repository or in a scratch folder. Output differs only in timings.

**1. Break each rule on purpose.** Save this as `Rules.java` in a scratch folder. It runs the first three migrations (V4 only adds an index) on an in-memory database and tries six statements that must be refused:

```text
import java.sql.*;

public class Rules {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:rules");
        Statement s = c.createStatement()) {
      for (String file : new String[] {"V1__module_1_schema.sql", "V2__idempotency_and_payment.sql", "V3__seat_order_for_cursors.sql"}) {
        s.execute("RUNSCRIPT FROM '" + args[0] + "/" + file + "'");
      }
      String key = "INSERT INTO idempotency_keys (endpoint, idempotency_key, fingerprint, state, created_at, expires_at) VALUES ('POST /v1/holds', 'k1', '" + "a".repeat(64) + "', 'IN_PROGRESS', 0, 1)";
      s.execute(key);
      attempt(s, "same key twice", key);
      attempt(s, "bad key state", key.replace("'k1'", "'k2'").replace("IN_PROGRESS", "DONE"));
      attempt(s, "completed with no reply", key.replace("'k1'", "'k3'").replace("IN_PROGRESS", "COMPLETED"));
      s.execute("INSERT INTO events VALUES (1, 'Arena Night', 0)");
      s.execute("INSERT INTO seats (event_id, seat_label, section, seat_number, status) VALUES (1, 'A-7', 'A', 7, 'AVAILABLE')");
      attempt(s, "bad seat status", "UPDATE seats SET status = 'SOLD'");
      attempt(s, "same seat twice", "INSERT INTO seats (event_id, seat_label, section, seat_number, status) VALUES (1, 'A-7', 'A', 7, 'AVAILABLE')");
      String booking = "INSERT INTO bookings (booking_id, event_id, seat_label, customer, confirmed_at) VALUES ('%s', 1, 'A-7', 'fan_1', 0)";
      s.execute(booking.formatted("b1"));
      attempt(s, "second booking of one seat", booking.formatted("b2"));
    }
  }

  static void attempt(Statement s, String what, String sql) {
    try {
      s.execute(sql);
      System.out.println(what + ": accepted");
    } catch (SQLException refused) {
      System.out.println(what + ": refused, SQLState " + refused.getSQLState() + ", " + refused.getMessage().split("[:;]")[0] + ": " + refused.getMessage().split("\"")[1].split(" ")[0]);
    }
  }
}
```

```text
java -cp <path to h2-2.5.252.jar> Rules.java src/main/resources/db/migration
```

The six output lines are the ones shown in "Keys, constraints and indexes" above.

**2. Run the migrations through the repository's own class and read the history.** See Try 4 of `java-payment-and-store-walkthrough.md`: it prints the rows of `schema_history`.

**3. Ask the database how it will find a seat.** `EXPLAIN` shows the plan. Save this as `Plan.java` and run it with the compiled repository on the class path (`./gradlew compileJava processResources` first):

```text
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.store.Database;
import java.sql.*;
import java.time.Clock;

public class Plan {
  public static void main(String[] args) throws Exception {
    try (Database db = Database.open("jdbc:h2:mem:plan")) {
      db.seedIfEmpty(new ApiSettings.Seed(1, 20, 1000), Clock.systemUTC());
      try (Connection c = db.connect(); Statement s = c.createStatement()) {
        for (String q : new String[] {
            "EXPLAIN SELECT event_id FROM seats WHERE event_id = 1 AND seat_label = 'A-7'",
            "EXPLAIN SELECT event_id FROM seats WHERE hold_id = 'x'"}) {
          ResultSet r = s.executeQuery(q);
          r.next();
          System.out.println(r.getString(1).replace("\n", " ").replaceAll(" +", " "));
        }
      }
    }
  }
}
```

```text
java -cp build/classes/java/main:build/resources/main:<path to h2-2.5.252.jar> Plan.java
```

```text
SELECT "EVENT_ID" FROM "PUBLIC"."SEATS" /* PUBLIC.PRIMARY_KEY_76: EVENT_ID = CAST(1 AS BIGINT) AND SEAT_LABEL = 'A-7' */ WHERE ("EVENT_ID" = CAST(1 AS BIGINT)) AND ("SEAT_LABEL" = 'A-7')
SELECT "EVENT_ID" FROM "PUBLIC"."SEATS" /* PUBLIC.SEATS_BY_HOLD: HOLD_ID = 'x' */ WHERE "HOLD_ID" = 'x'
```

The first plan uses the primary key. The second uses the index `seats_by_hold` from V4. Before V4 the second plan said `/* PUBLIC.SEATS.tableScan */`: every lookup by hold id read all 20,000 seats, and 1,000 such lookups took 480.5 ms against 3.6 ms for 1,000 lookups by primary key. With the index, in my run after a warm-up, 1,000 lookups by hold id took 3.5 ms and 1,000 by primary key 5.8 ms; timings differ on every run. The plan number `76` in the first line changes between runs.

**4. Watch the migrations be skipped.** Run Try 4 of `java-payment-and-store-walkthrough.md` twice on the same file path. Nothing is applied the second time.

**5. Start the API with a file database and look for the file.** Copy `config/serve.properties`, change the ports, run `./gradlew -q serve --args='<copy>'` for a few seconds, and look in `build/data/`: it holds `seatwave-api.mv.db` (H2's data file). I did not run this one; I used an in-memory URL in my copies.

## Mistakes people make

- **Gluing text into SQL.** `"... WHERE id = " + userText` lets a person write their own SQL. Use `?` parameters, as every statement here does.
- **Reading, deciding, then writing.** Between the read and the write another transaction can change the row. Put the condition in the `WHERE` of the `UPDATE` and read how many rows changed.
- **Leaving a transaction open.** It keeps its locks. I saw an `UPDATE` that changed nothing hold a lock until the transaction ended.
- **Editing a migration after release.** Databases that already ran it will not run it again. Add a new file.
- **Dropping or renaming a column in one step.** The old program still running against the database breaks at once. Add the new column, move the data, switch the program, and only later remove the old one.
- **Storing money in a floating-point column.** Use `DECIMAL`.
- **Comparing with `= NULL`.** It is never true. Use `IS NULL` and `IS NOT NULL`.
- **Filtering on a column with no index.** The database scans every row. This repository had one: `seats.hold_id`, until V4 added `seats_by_hold` (Try 3 shows the plan).
- **Opening one file database from two programs.** The second fails.

## Check yourself

1. What does the primary key `one_effect_per_key` guarantee? (At most one row for each `(endpoint, idempotency_key)`. A second insert of the same pair is refused with `23505` once the first is committed, and waits while the first is open.)
2. What is the difference between a primary key and a unique constraint? (A primary key is the identity of the row and cannot be NULL; a table has one. A unique constraint only forbids duplicates; a table may have several. `one_booking_per_seat` is a unique constraint.)
3. Why does V3 add the column first, fill it with `UPDATE`, and only then make it `NOT NULL`? (A `NOT NULL` column cannot be added to a table that has rows without giving them a value. Adding it nullable, filling it, then tightening it works on a table with data and a new empty table alike.)
4. Why are the columns added by V2 nullable? (Old rows have no value for them, and the old program that does not know the columns must still be able to insert rows. This is the "expand only" rule.)
5. What happens if `Database.open` runs on a database that already ran all four migrations? (Each version is found in `schema_history` and skipped. Nothing changes. If a crash had lost a history row, the script runs again, changes nothing because of `IF NOT EXISTS`, and is recorded.)

## Further reading

Official documentation, for H2 2.5.252:

- H2 SQL grammar, all commands (`CREATE TABLE`, `ALTER TABLE`, `INSERT`, `UPDATE`, `DELETE`, `RUNSCRIPT`, `EXPLAIN`): https://h2database.com/html/commands.html
- H2 data types: https://h2database.com/html/datatypes.html
- H2 features (in-memory and file databases, database URL settings, `LOCK_TIMEOUT`): https://h2database.com/html/features.html
- H2 advanced topics (multi-version concurrency, isolation levels, row locks): https://h2database.com/html/advanced.html
- H2 error codes (23505, 23513, 50200, 90020): https://h2database.com/javadoc/org/h2/api/ErrorCode.html
- JDBC (`java.sql`) API for Java 25: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/package-summary.html
- SQLSTATE codes are defined by the SQL standard (ISO/IEC 9075); H2's list is in the error code page above.
- Logback 1.6.6 configuration and layout patterns: https://logback.qos.ch/manual/configuration.html and https://logback.qos.ch/manual/layouts.html
