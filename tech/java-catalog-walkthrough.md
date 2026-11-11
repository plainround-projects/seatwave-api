# Walkthrough: the `catalog` package (events, seats and cursor pagination)

## What it is

This file reads the nine Java files of the `catalog/` package line by line: `CatalogService`, `Cursors`, `Page`, `Position`, `SeatFilter`, `EventSummary`, `SeatSummary`, `SeatAvailability` and `CatalogProblem`.

The package answers one kind of question: "show me the events" and "show me the seats of event 1". These are **reads**: they change nothing. The answer can be long (20,000 seats), so it comes in **pages**, and the package knows how to ask the database for one page at a time.

**Pagination** means cutting a long list into pages. A client asks for the first page. If more exists, the reply carries a **cursor**: a small opaque text that means "continue after the last item you saw". The client sends it back to get the next page.

## Why this repository uses it

The lesson of Module 7 includes a design choice that looks small and is not: how to page. The usual first idea is `LIMIT 100 OFFSET 200` ("skip 200 rows, give me 100"). That breaks when the list changes between two requests. This package uses **keyset pagination** instead: the cursor names the last item by its place in the sort order, and the next page is "the next rows after that place". The `load/` package then measures both ways with real numbers (see `java-load-walkthrough.md`).

The package also keeps a boundary: it knows nothing about HTTP. It takes plain Java values (a limit, a position, a filter) and returns plain values (a `Page`, or a problem). The `http` package turns those into status codes and JSON.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/catalog/`) | What it does |
|---|---|
| `CatalogService.java` | Runs the two paged queries (events, seats) and builds the page. |
| `Cursors.java` | Turns a position into a cursor text and back. |
| `Page.java` | The result type: a page that has more after it, or the last page. |
| `Position.java` | Where a page starts: the beginning, or after a cursor. |
| `SeatFilter.java` | Which seats a list shows (section and state). |
| `EventSummary.java`, `SeatSummary.java`, `SeatAvailability.java` | The items a page holds. |
| `CatalogProblem.java` | Why a read was refused: no such event, or a bad cursor. |

The users of this package are `http/CatalogRoutes.java` and `http/Version0Routes.java` (see `java-http-walkthrough.md`) and `load/PageWalk.java`.

## How it works underneath

**A relational database stores rows in tables.** The `events` table has one row per event. The `seats` table has one row per seat. `SELECT ... FROM ... WHERE ... ORDER BY ... LIMIT n` asks for rows that match a condition, in a given order, at most `n` of them. The database here is H2, which runs inside the same process as the server. The SQL migrations (explained in another note) create the tables. The third one adds a numeric `seat_number` column and two indexes, `seats_in_map_order` on `(event_id, section, seat_number)` and `events_in_start_order` on `(starts_at, event_id)`, which are exactly the orders the cursor queries walk.

**An index** is a sorted side structure. If rows are sorted by `(section, seat_number)` in an index, the database can jump straight to "the first row after A-999" without reading the rows before it. A query with `OFFSET 10000` cannot jump: it must walk and throw away 10,000 rows.

**Why OFFSET goes wrong.** Take ten available seats, A to J, and pages of three.

- Page 1 (offset 0) shows A, B, C.
- Before page 2, a fan holds B. B leaves the "available" list, which is now A, C, D, E, F, G, H, I, J.
- Page 2 (offset 3) skips three rows: A, C, D. It shows E, F, G.
- Seat D was available the whole time, and the walker never saw it. **A row was skipped.**

The mirror case: a new seat arrives before the position. Everything after it moves down by one, and page 2 starts with a seat already shown on page 1. **A row is repeated.** The offset counts rows, so every row that leaves or enters before the position moves all the rest.

**Why a cursor does not go wrong.** The cursor after page 1 says "after C". Page 2 is "the next three available seats after C": D, E, F. That B left changes nothing, because C's place in the sort order did not move. Keyset pagination needs a total order on the rows with no ties: events are ordered by `(starts_at, event_id)` and seats by `(section, seat_number)`.

**Why `limit + 1`.** To know whether another page exists, the service asks for one row more than the page holds. If the extra row comes back, there is a next page; the extra row is dropped, and the cursor names the last row that stays. This costs no second query for a count.

**The maximum page size** is 500 items (`ContractLimits.MAX_PAGE_LIMIT`), and 100 when the client gives no limit. The check is in the `http` package (`CatalogRoutes.limitOf`), before any database work, and the catalog service itself only insists on 1 or more. Why a cap at all: a page is built in memory and sent in one reply, so an unbounded `limit` would let one request cost as much as the whole table. A page of 500 seats is about 25,000 bytes (see `java-load-walkthrough.md`).

## Walkthrough of this repository's use

### `Position.java` (where a page starts)

```java
package io.plainround.seatwave.api.catalog;

import java.util.Objects;
```

The package line, then `Objects` for null checks.

```java
/** Where a page starts: at the beginning, or after the item a cursor names. */
public sealed interface Position {

  /** The first page. */
  record First() implements Position {}
```

`sealed interface Position` is a **sealed interface**: a type whose possible implementations are all listed in the same file. The compiler knows them all, so a `switch` over a `Position` can be checked to cover every case. `record First() implements Position {}` is a record with no fields: it means "the beginning". The Javadoc comment on the line above the interface is one line: `/** ... */`.

```java
  /**
   * The page after a previous one.
   *
   * @param cursor the previous page's nextCursor, as the client sent it back
   */
  record After(String cursor) implements Position {

    /** Checks the field. */
    public After {
      Objects.requireNonNull(cursor, "cursor");
    }
  }
```

`After(String cursor)` is a record with one field, the cursor text exactly as the client sent it. Its compact constructor `public After { ... }` only checks that the cursor is not `null`. Whether the cursor is valid is checked later, in `Cursors`.

### `Page.java` (the result of a read)

```java
package io.plainround.seatwave.api.catalog;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
```

Imports. `Function` is the JDK type for a one-argument function; it is used by `map`.

```java
/**
 * One page of a collection. A page either names where the next one starts or is the last, so no
 * field is ever null: the JSON of a last page simply has no {@code nextCursor}.
 *
 * @param <T> the item type
 */
public sealed interface Page<T> {
```

`Page<T>` is a **generic** sealed interface: `T` stands for the type of the items. A `Page<EventSummary>` holds events, a `Page<SeatSummary>` holds seats. The Javadoc says the design goal: a page either names where the next one starts or is the last, so no field is ever null. A last page simply has no `nextCursor` in its JSON.

```java
  /**
   * The items, in order.
   *
   * @return the items, never more than the requested limit
   */
  List<T> items();
```

Every page has `items()`, the list in order. Because `Page` is an interface, the method has no body here.

```java
  /**
   * Converts every item, keeping the cursor.
   *
   * @param convert the conversion
   * @param <R> the new item type
   * @return the converted page
   */
  default <R> Page<R> map(Function<T, R> convert) {
    List<R> converted = items().stream().map(convert).toList();
    return switch (this) {
      case More<T> more -> new More<>(converted, more.nextCursor());
      case Last<T> last -> new Last<>(converted);
    };
  }
```

`map` converts every item and keeps the cursor. `default` gives the interface method a body. `items().stream().map(convert).toList()` runs the conversion on each item and collects a new list. `switch (this)` then looks at what kind of page this is: `case More<T> more` builds a `More` with the converted list and the same cursor; `case Last<T> last` builds a `Last`. The `http` package uses it to turn catalog items into the JSON shapes of the contract without touching the cursor.

```java
  /**
   * A page with more after it.
   *
   * @param items the items
   * @param nextCursor the opaque position after the last item
   * @param <T> the item type
   */
  record More<T>(List<T> items, String nextCursor) implements Page<T> {

    /** Copies the items and checks the cursor. */
    public More {
      items = List.copyOf(items);
      Objects.requireNonNull(nextCursor, "nextCursor");
    }
  }
```

`More<T>` is a page with more after it. It implements `Page<T>`. The compact constructor copies the list with `List.copyOf` (so later changes to the caller's list cannot change the page) and insists on a non-null `nextCursor`.

```java
  /**
   * The last page.
   *
   * @param items the items, possibly none
   * @param <T> the item type
   */
  record Last<T>(List<T> items) implements Page<T> {

    /** Copies the items. */
    public Last {
      items = List.copyOf(items);
    }
  }
```

`Last<T>` is the final page; its list may be empty (a filter that matches nothing gives an empty last page).

### `SeatFilter.java` (which seats)

```java
package io.plainround.seatwave.api.catalog;

import java.util.Objects;
import java.util.regex.Pattern;
```

Imports: `Objects` and `Pattern` (regular expression).

```java
/**
 * Which seats a list shows.
 *
 * @param section one section letter, or {@link #ANY_SECTION} for all
 * @param state one state, or {@link StateFilter#ANY} for all
 */
public record SeatFilter(String section, StateFilter state) {
```

A seat filter has a `section` and a `state`. The section is a letter, or the special value `*` for every section.

```java
  /** The section value that means every section. */
  public static final String ANY_SECTION = "*";

  private static final Pattern SECTION = Pattern.compile("[A-T]|\\*");
```

`ANY_SECTION` is `"*"`. The pattern `[A-T]|\\*` accepts one letter from A to T, or a literal star.

```java
  /** Checks the fields. */
  public SeatFilter {
    if (!SECTION.matcher(Objects.requireNonNull(section, "section")).matches()) {
      throw new IllegalArgumentException("section must be A to T");
    }
    Objects.requireNonNull(state, "state");
  }
```

The compact constructor refuses any other section text. `Objects.requireNonNull(section, "section")` returns the section when it is not null, so it can be used inside the `if`.

```java
  /** A state filter: one of the map's three states, or any. */
  public enum StateFilter {
    /** Every state. */
    ANY,
    /** Only available seats. */
    AVAILABLE,
    /** Only held seats. */
    HELD,
    /** Only sold seats. */
    SOLD
  }
```

`StateFilter` is an **enum**: a type with a fixed set of named values. `ANY` means every state; the other three are the states a seat map shows. Because `SeatFilter` is a record, two filters with the same section and state are `equals`, which `CatalogService.startOf` uses to compare a cursor's filter with the request's.

### The item types

```java
package io.plainround.seatwave.api.catalog;

import java.time.Instant;
import java.util.Objects;
```

```java
/**
 * An event in the catalog.
 *
 * @param eventId its identifier
 * @param name its name
 * @param startsAt when it starts
 */
public record EventSummary(long eventId, String name, Instant startsAt) {

  /** Checks the fields. */
  public EventSummary {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(startsAt, "startsAt");
  }
```

An event has an id, a name and a start time (`Instant`, a point in time in UTC). The constructor refuses `null` name or start.

```java
package io.plainround.seatwave.api.catalog;

import java.util.Objects;
```

```java
/**
 * A seat on the seat map.
 *
 * @param seatLabel the seat, for example A-7
 * @param section its section letter
 * @param seatNumber its number inside the section, the second part of the map order
 * @param state what the map shows
 */
public record SeatSummary(
    String seatLabel, String section, int seatNumber, SeatAvailability state) {

  /** Checks the fields. */
  public SeatSummary {
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(section, "section");
    Objects.requireNonNull(state, "state");
  }
```

A seat has a label such as `A-7`, its section letter, its number within the section, and what the map shows (`SeatAvailability`). The number is a separate field because the sort order and the cursor need it as a number: `A-9` comes before `A-10` only when the number is compared as a number, not as text.

```java
package io.plainround.seatwave.api.catalog;

/**
 * What the seat map shows. Module 1's three words: who holds a seat, and a payment in flight, are
 * never shown, so both HELD and CONFIRMING show as held.
 */
public enum SeatAvailability {
  /** Anyone may hold it. */
  AVAILABLE,
  /** Someone holds it, or is paying for it. */
  HELD,
  /** It is booked. */
  SOLD
}
```

An enum with the three words of Module 1's seat map. The comment says that who holds a seat, and a payment in flight, are never shown, so both a held seat and a seat being paid for show as `HELD`.

```java
package io.plainround.seatwave.api.catalog;

/** Why a catalog read was refused. */
public sealed interface CatalogProblem {

  /** There is no event with that id (404). */
  record EventNotFound() implements CatalogProblem {}

  /**
   * The cursor is not one this server made, or was made with other filters (400).
   *
   * @param detail what is wrong
   */
  record BadCursor(String detail) implements CatalogProblem {}
}
```

A sealed interface with two records: `EventNotFound()` (a 404) and `BadCursor(String detail)` (a 400). The service returns one of these inside a `Result` instead of throwing, because both are expected outcomes of a client's request, not bugs.

### `Cursors.java` (positions to text and back)

```java
package io.plainround.seatwave.api.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

Imports. `Base64` encodes bytes as printable text. `Matcher` and `Pattern` are the regular-expression classes.

```java
/**
 * Makes and reads cursors. A cursor names the last item of a page by its place in the sort order
 * (keyset pagination), not by a count of items before it, so seats changing state or rows being
 * added and removed before it cannot move it.
 *
 * <p>A cursor is opaque to clients: base64url of a short versioned text. It is not secret and not
 * signed; a client that edits one only moves its own position, and every value is checked against
 * an allow-list when it comes back. The seat cursor carries its filters, so it cannot be replayed
 * under different ones.
 */
final class Cursors {
```

The Javadoc is the design statement. A cursor names the last item by its place in the sort order, not by a count. It is **opaque** to clients: they must send it back unchanged and not look inside. It is not secret and not signed. A client that edits a cursor only moves its own position, and every value is checked when the cursor comes back. The seat cursor carries its filters, so it cannot be replayed under different ones. The class is package-private (no `public`), so only the `catalog` package can use it.

```java
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final Pattern EVENT_CURSOR = Pattern.compile("e1\\.([0-9]{1,18})\\.([0-9]{1,18})");
  private static final Pattern SEAT_CURSOR =
      Pattern.compile(
          "s1\\.([0-9]{1,18})\\.([A-T*])\\.(any|available|held|sold)\\.([A-T])\\.([0-9]{1,4})");
```

`Base64.getUrlEncoder().withoutPadding()` is an encoder for the URL-safe base64 alphabet (letters, digits, `-` and `_`) with no trailing `=`. That makes a cursor safe to put in a query string without escaping. `EVENT_CURSOR` accepts text of the form `e1.<number>.<number>`. `SEAT_CURSOR` accepts `s1.<event>.<section or *>.<state>.<section letter>.<number>`. The `e1` and `s1` prefixes are version marks, so the format can change later. Each digit group is limited to 18 digits, which always fits a `long`, so `Long.parseLong` cannot overflow. The regular expressions are the **allow-list**: only text of exactly this shape is accepted.

```java
  private Cursors() {}
```

A private empty constructor: the class is a toolbox and is never instantiated.

```java
  /**
   * The position after one event.
   *
   * @param startsAt the event's start, the first sort key
   * @param eventId the event's id, which breaks ties between events at the same time
   */
  record EventPosition(Instant startsAt, long eventId) {}
```

`EventPosition` is the position after one event: its start time (the first sort key) and its id (which breaks ties between events that start at the same time).

```java
  /**
   * The position after one seat, under one filter.
   *
   * @param eventId the event the cursor belongs to
   * @param filter the filters it was made with
   * @param section the last seat's section, the first sort key
   * @param seatNumber the last seat's number, the second sort key
   */
  record SeatPosition(long eventId, SeatFilter filter, String section, int seatNumber) {}
```

`SeatPosition` is the position after one seat: the event, the filter the cursor was made with, the last seat's section and number.

```java
  static String encode(EventPosition position) {
    return encodeText("e1." + position.startsAt().toEpochMilli() + "." + position.eventId());
  }
```

Encoding an event position. The text is `e1.` plus the start time in epoch milliseconds, a dot and the event id; `encodeText` turns it into base64url. The first page of the demo data ends with a cursor that decodes to `e1.1797039442284.2`. (The number is a start time in milliseconds since 1 January 1970, so it differs in every run.)

```java
  static String encode(SeatPosition position) {
    return encodeText(
        String.join(
            ".",
            "s1",
            Long.toString(position.eventId()),
            position.filter().section(),
            position.filter().state().name().toLowerCase(Locale.ROOT),
            position.section(),
            Integer.toString(position.seatNumber())));
  }
```

Encoding a seat position. `String.join(".", ...)` puts a dot between the parts: the prefix `s1`, the event id, the filter's section, the filter's state in lower case, the last seat's section and number. A cursor after seat B-2 under the filter "section B, available" decodes to `s1.1.B.available.B.2`.

```java
  static Optional<EventPosition> decodeEvent(String cursor) {
    return decodeText(cursor)
        .map(EVENT_CURSOR::matcher)
        .filter(Matcher::matches)
        .map(matcher ->
            new EventPosition(
                Instant.ofEpochMilli(Long.parseLong(matcher.group(1))),
                Long.parseLong(matcher.group(2))));
  }
```

`decodeEvent` goes the other way. `decodeText(cursor)` gives an `Optional<String>` (a box that may be empty). `.map(EVENT_CURSOR::matcher)` makes a matcher from the text; `EVENT_CURSOR::matcher` is a **method reference**, a short form of a lambda. `.filter(Matcher::matches)` keeps it only if the whole text fits the pattern. `.map(matcher -> new EventPosition(...))` builds the position from the two captured number groups. If any step fails, the result is an empty `Optional`, and the caller reports "bad cursor".

```java
  static Optional<SeatPosition> decodeSeat(String cursor) {
    return decodeText(cursor)
        .map(SEAT_CURSOR::matcher)
        .filter(Matcher::matches)
        .map(matcher ->
            new SeatPosition(
                Long.parseLong(matcher.group(1)),
                new SeatFilter(
                    matcher.group(2),
                    SeatFilter.StateFilter.valueOf(matcher.group(3).toUpperCase(Locale.ROOT))),
                matcher.group(4),
                Integer.parseInt(matcher.group(5))));
  }
```

`decodeSeat` does the same for seats. It also rebuilds the `SeatFilter` from the captured section and state. `SeatFilter.StateFilter.valueOf(matcher.group(3).toUpperCase(Locale.ROOT))` turns `available` back into the enum value `AVAILABLE`. The regular expression allows only `any`, `available`, `held` or `sold` there, so `valueOf` cannot fail.

```java
  private static String encodeText(String text) {
    return ENCODER.encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
```

`encodeText` writes the text as UTF-8 bytes and base64url-encodes them.

```java
  private static Optional<String> decodeText(String cursor) {
    try {
      return Optional.of(new String(DECODER.decode(cursor), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException notBase64) {
      // A cursor that is not base64url is a client error, reported as "bad cursor" by the caller.
      return Optional.empty();
    }
  }
```

`decodeText` reverses it. A cursor that is not valid base64url makes `DECODER.decode` throw `IllegalArgumentException`. The `catch` turns that into an empty `Optional`. The comment says why: it is a client error, reported as "bad cursor" by the caller, not a crash.

### `CatalogService.java` (the queries)

```java
package io.plainround.seatwave.api.catalog;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
```

Imports. The database classes are `Connection` (an open link to the database), `PreparedStatement` (a SQL statement with `?` placeholders) and `ResultSet` (the rows that come back). `Function` is used by `pageOf`.

```java
/**
 * Reads events and seats one page at a time, with keyset cursors.
 *
 * <p>Each page is one query: "the next {@code limit + 1} rows after this position, in this order".
 * The extra row says whether another page exists without a second query for a count. The cost of a
 * page does not grow with how deep in the list it is, because the index finds the position
 * directly; an {@code OFFSET} would read and throw away every row before it.
 *
 * <p>Thread-safe: it holds no mutable state; every call uses its own connection.
 */
public final class CatalogService {
```

The Javadoc explains the whole design: each page is one query, "the next `limit + 1` rows after this position, in this order". The cost of a page does not grow with the depth, because the index finds the position directly. An `OFFSET` would read and throw away every row before it. The class is thread-safe because it holds no changing state: each call uses its own connection.

```java
  // Section letters sort after the empty string and seat numbers start at 1, so ("", 0) is a
  // position before every seat.
  private static final String BEFORE_FIRST_SECTION = "";
  private static final int BEFORE_FIRST_NUMBER = 0;
```

The position "before the first seat" is `("", 0)`: the empty text sorts before every section letter, and seat numbers start at 1.

```java
  private final Database database;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param database the database
   * @param clock the clock that decides whether a hold has expired
   */
  public CatalogService(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }
```

The service holds a `Database` (a source of connections) and a `Clock`. The clock decides whether a hold has expired.

```java
  /**
   * One page of events, soonest first.
   *
   * @param limit the most events on the page, 1 or more
   * @param position where the page starts
   * @return the page, or a bad-cursor problem
   * @throws SQLException if the database fails
   */
  public Result<Page<EventSummary>, CatalogProblem> listEvents(int limit, Position position)
      throws SQLException {
    requirePositive(limit);
    Optional<Cursors.EventPosition> after =
        switch (Objects.requireNonNull(position, "position")) {
          case Position.First() ->
              Optional.of(new Cursors.EventPosition(Instant.ofEpochMilli(Long.MIN_VALUE), 0));
          case Position.After(String cursor) -> Cursors.decodeEvent(cursor);
        };
    if (after.isEmpty()) {
      return Result.error(new CatalogProblem.BadCursor("this cursor was not made by this list"));
    }
```

`listEvents` returns a `Result<Page<EventSummary>, CatalogProblem>`: a page, or a problem. `requirePositive(limit)` throws if the limit is below 1. The `switch` expression turns a `Position` into an optional event position. For `First()` it is the earliest possible: `Instant.ofEpochMilli(Long.MIN_VALUE)` and id 0, so every event is after it. For `After(String cursor)` it decodes the cursor. If decoding gave nothing, the service returns the bad-cursor problem with the text "this cursor was not made by this list".

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

`try (Connection connection = database.connect(); PreparedStatement query = ...)` opens a connection and a prepared statement and closes both when the block ends. The text between `"""` and `"""` is a **text block**: a multi-line string. The SQL says: give me the id, name and start time of events that start after the position, or start at the same moment with a larger id (`starts_at > ? OR (starts_at = ? AND event_id > ?)`); order them by start time then id (the same pair, so the order is total); stop after `?` rows. The `?` marks are filled in below, which keeps data out of the SQL text and so prevents SQL injection.

```java
      query.setLong(1, after.get().startsAt().toEpochMilli());
      query.setLong(2, after.get().startsAt().toEpochMilli());
      query.setLong(3, after.get().eventId());
      query.setInt(4, limit + 1);
```

`setLong(1, ...)` fills the first `?`; the next calls fill the others in order. The start time is stored as epoch milliseconds, so `toEpochMilli()` is the stored form. The last placeholder is `limit + 1`: one row more than the page holds.

```java
      List<EventSummary> events = new ArrayList<>();
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          events.add(
              new EventSummary(
                  rows.getLong("event_id"),
                  rows.getString("name"),
                  Instant.ofEpochMilli(rows.getLong("starts_at"))));
        }
      }
```

`query.executeQuery()` runs the SQL and gives a `ResultSet`. `rows.next()` moves to the next row and returns false when there is none. For each row an `EventSummary` is built from the named columns. The inner `try` closes the result set.

```java
      return Result.success(
          pageOf(
              events,
              limit,
              lastEvent ->
                  Cursors.encode(
                      new Cursors.EventPosition(lastEvent.startsAt(), lastEvent.eventId()))));
    }
  }
```

`pageOf(events, limit, lastEvent -> Cursors.encode(...))` builds the page. The lambda says how to make a cursor from the last event that stays on the page: its start time and id.

```java
  /**
   * One page of an event's seats, in seat-map order (section, then number).
   *
   * @param eventId the event
   * @param filter which seats
   * @param limit the most seats on the page, 1 or more
   * @param position where the page starts
   * @return the page, or a problem: no such event, or a cursor from another list or filter
   * @throws SQLException if the database fails
   */
  public Result<Page<SeatSummary>, CatalogProblem> listSeats(
      long eventId, SeatFilter filter, int limit, Position position) throws SQLException {
    Objects.requireNonNull(filter, "filter");
    requirePositive(limit);
    return switch (startOf(eventId, filter, position)) {
      case Result.Error<Cursors.SeatPosition, CatalogProblem>(CatalogProblem bad) ->
          Result.error(bad);
      case Result.Success<Cursors.SeatPosition, CatalogProblem>(Cursors.SeatPosition start) ->
          readPage(start, limit);
    };
  }
```

`listSeats` is the same idea for one event's seats. `startOf` turns the request into a start position or a problem. The `switch` handles both: an `Error` is passed on; a `Success` goes to `readPage`.

```java
  private Result<Page<SeatSummary>, CatalogProblem> readPage(Cursors.SeatPosition start, int limit)
      throws SQLException {
    try (Connection connection = database.connect()) {
      if (!eventExists(connection, start.eventId())) {
        return Result.error(new CatalogProblem.EventNotFound());
      }
      List<SeatSummary> seats = readSeats(connection, start, limit + 1);
      return Result.success(
          pageOf(
              seats,
              limit,
              lastSeat ->
                  Cursors.encode(
                      new Cursors.SeatPosition(
                          start.eventId(),
                          start.filter(),
                          lastSeat.section(),
                          lastSeat.seatNumber()))));
    }
  }
```

`readPage` opens one connection. If the event does not exist, the answer is `EventNotFound`. Otherwise `readSeats(connection, start, limit + 1)` reads one row too many and `pageOf` builds the page. The cursor after the last kept seat carries the event, the same filter, and that seat's section and number.

```java
  private static Result<Cursors.SeatPosition, CatalogProblem> startOf(
      long eventId, SeatFilter filter, Position position) {
    return switch (Objects.requireNonNull(position, "position")) {
      case Position.First() ->
          Result.success(
              new Cursors.SeatPosition(eventId, filter, BEFORE_FIRST_SECTION, BEFORE_FIRST_NUMBER));
      case Position.After(String cursor) -> {
        Optional<Cursors.SeatPosition> decoded = Cursors.decodeSeat(cursor);
        if (decoded.isEmpty()) {
          yield Result.error(new CatalogProblem.BadCursor("this cursor was not made by this list"));
        }
        // A cursor names a place under one set of filters; under others it could skip seats.
        if (decoded.get().eventId() != eventId || !decoded.get().filter().equals(filter)) {
          yield Result.error(
              new CatalogProblem.BadCursor("this cursor was made for another event or filter"));
        }
        yield Result.success(decoded.get());
      }
    };
  }
```

`startOf` turns a `Position` into a `SeatPosition`. The first page starts at `("", 0)`. For a cursor, `Cursors.decodeSeat` must succeed; otherwise "this cursor was not made by this list". Then two checks guard against a replay: the cursor's event must equal the request's event, and the cursor's filter must equal the request's filter. The comment says why: a cursor names a place under one set of filters; under others it could skip seats. A failure gives "this cursor was made for another event or filter". `yield` returns a value from a block inside a `switch` expression.

```java
  private List<SeatSummary> readSeats(Connection connection, Cursors.SeatPosition after, int rows)
      throws SQLException {
    String state = after.filter().state().name().toLowerCase(Locale.ROOT);
    long now = clock.instant().toEpochMilli();
    try (PreparedStatement query =
        connection.prepareStatement(
            """
            SELECT seat_label, section, seat_number, status, hold_expires_at FROM seats
            WHERE event_id = ?
              AND (? = '*' OR section = ?)
              AND (? = 'any'
                OR (? = 'available'
                    AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?)))
                OR (? = 'held'
                    AND ((status = 'HELD' AND hold_expires_at > ?) OR status = 'CONFIRMING'))
                OR (? = 'sold' AND status = 'CONFIRMED'))
              AND (section > ? OR (section = ? AND seat_number > ?))
            ORDER BY section, seat_number
            LIMIT ?
            """)) {
```

`readSeats` first turns the filter's state into lower case text (`any`, `available`, `held` or `sold`) and reads the clock once. It then runs one SQL statement. Reading the SQL in order:

- `WHERE event_id = ?` limits it to one event.
- `AND (? = '*' OR section = ?)` is the section filter: either no section was chosen (`*`), or the row's section equals it.
- The block `AND (? = 'any' OR (? = 'available' AND ...) OR (? = 'held' AND ...) OR (? = 'sold' AND ...))` is the state filter. A seat is **available** when its status is `AVAILABLE`, or `HELD` with a hold that already expired (`hold_expires_at <= now`). It is **held** when `HELD` with an unexpired hold, or `CONFIRMING` (a payment in flight). It is **sold** when `CONFIRMED`. Expiry is lazy, as in Module 1: nothing changes the row when a hold runs out; the clock decides when the row is read.
- `AND (section > ? OR (section = ? AND seat_number > ?))` is the keyset: "after the position". A seat is after `(B, 2)` if its section is greater than B, or it is in section B with a number greater than 2.
- `ORDER BY section, seat_number` is the total order, and `LIMIT ?` is `limit + 1`.

```java
      int parameter = 1;
      query.setLong(parameter++, after.eventId());
      query.setString(parameter++, after.filter().section());
      query.setString(parameter++, after.filter().section());
      query.setString(parameter++, state);
      query.setString(parameter++, state);
      query.setLong(parameter++, now);
      query.setString(parameter++, state);
      query.setLong(parameter++, now);
      query.setString(parameter++, state);
      query.setString(parameter++, after.section());
      query.setString(parameter++, after.section());
      query.setInt(parameter++, after.seatNumber());
      query.setInt(parameter, rows);
```

The thirteen placeholders are filled in the order they appear in the SQL. `parameter++` uses the current number and then adds one, so the lines cannot get out of step by hand. The state text is bound four times (once for each `? = '...'` test) and the clock value twice.

```java
      List<SeatSummary> seats = new ArrayList<>();
      try (ResultSet row = query.executeQuery()) {
        while (row.next()) {
          seats.add(
              new SeatSummary(
                  row.getString("seat_label"),
                  row.getString("section"),
                  row.getInt("seat_number"),
                  availabilityOf(row.getString("status"), row.getLong("hold_expires_at"), now)));
        }
      }
      return seats;
    }
  }
```

Each row becomes a `SeatSummary`. `availabilityOf` turns the stored status and expiry into what the map shows.

```java
  private static SeatAvailability availabilityOf(String status, long expiresAt, long now) {
    return switch (status) {
      case "CONFIRMED" -> SeatAvailability.SOLD;
      case "CONFIRMING" -> SeatAvailability.HELD;
      // Expiry is lazy, as in Module 1: an expired hold shows as available.
      case "HELD" -> expiresAt > now ? SeatAvailability.HELD : SeatAvailability.AVAILABLE;
      case "AVAILABLE" -> SeatAvailability.AVAILABLE;
      default -> throw new IllegalStateException("the seats table has an unknown status");
    };
  }
```

`CONFIRMED` is sold. `CONFIRMING` is shown as held. `HELD` is held while the expiry is still in the future, and available after it (lazy expiry). `AVAILABLE` is available. Any other text means the table holds a status the code does not know, which is a bug, so it throws `IllegalStateException`.

```java
  private static boolean eventExists(Connection connection, long eventId) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM events WHERE event_id = ?")) {
      query.setLong(1, eventId);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }
```

`eventExists` runs `SELECT 1 FROM events WHERE event_id = ?`; the event exists if the result set has a row.

```java
  private static <T> Page<T> pageOf(List<T> fetched, int limit, Function<T, String> cursorAfter) {
    if (fetched.size() <= limit) {
      return new Page.Last<>(fetched);
    }
    List<T> items = fetched.subList(0, limit);
    return new Page.More<>(items, cursorAfter.apply(items.getLast()));
  }
```

`pageOf` is the `limit + 1` rule in code. If the database returned `limit` rows or fewer, there is no next page: a `Last`. Otherwise `fetched.subList(0, limit)` keeps the first `limit` rows, and `cursorAfter.apply(items.getLast())` makes the cursor from the last row that stays. `getLast()` is a `List` method added in Java 21. `<T>` before the return type makes the method generic, so it serves both events and seats.

```java
  private static void requirePositive(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be 1 or more");
    }
  }
}
```

`requirePositive` throws `IllegalArgumentException` for a limit below 1. This is a programming error check; the `http` package has already refused such a limit with a 400.

## Try it yourself

I started the API in a copy of the repository, on port 28187 with an in-memory database, with `./gradlew -q serve --args=...`. Use the port of your own settings file (`config/serve.properties` says 8087). The start times and ids differ in every run.

1. List two events. The reply carries a cursor:

```text
$ curl -s "http://127.0.0.1:28187/v1/events?limit=2"
{"items":[{"eventId":1,"name":"Arena Night","startsAt":"2026-12-11T01:37:22.284Z"},{"eventId":2,"name":"Event 2","startsAt":"2026-12-12T01:37:22.284Z"}],"nextCursor":"ZTEuMTc5NzAzOTQ0MjI4NC4y"}
```

2. Look inside a cursor. Base64url text needs padding for some tools, so a short Python line decodes it:

```text
$ python3 -c "import base64;c='ZTEuMTc5NzAzOTQ0MjI4NC4y';print(base64.urlsafe_b64decode(c+'='*(-len(c)%4)).decode())"
e1.1797039442284.2
```

3. The default page holds 100 events; with 120 events the second page holds 20 and has no `nextCursor`. A page of 500 is accepted; 501 is refused with a 400 that names the parameter:

```text
$ curl -s "http://127.0.0.1:28187/v1/events?limit=501"
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"one field is invalid","instance":"urn:uuid:cb842abe-d219-4e92-b917-1c4cbc26e8b4","errors":[{"pointer":"limit","detail":"must be a whole number from 1 to 500"}]}
```

4. A made-up cursor and a cursor from another filter are refused with a 400:

```text
$ curl -s "http://127.0.0.1:28187/v1/events?cursor=abc"
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"one field is invalid","instance":"urn:uuid:f16e367e-aacd-44c3-9e79-80e6adcff4be","errors":[{"pointer":"cursor","detail":"this cursor was not made by this list"}]}
$ curl -s "http://127.0.0.1:28187/v1/events/1/seats?section=C&cursor=czEuMS5CLmF2YWlsYWJsZS5CLjI"
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"one field is invalid","instance":"urn:uuid:9a3b1d48-ce9a-4887-80fd-2e188f00b414","errors":[{"pointer":"cursor","detail":"this cursor was made for another event or filter"}]}
```

5. A cursor is only a position, so an edited one works. I built the cursor `s1.1.*.any.A.999` by hand and asked for two seats; the reply began after seat A-999:

```text
{"items":[{"seat":"A-1000","section":"A","state":"available"},{"seat":"B-1","section":"B","state":"available"}],"nextCursor":"czEuMS4qLmFueS5CLjE"}
```

   This is the point of the design note "not secret and not signed": an edited cursor can only move the client's own place.

6. The comparison of the two ways to page, with real numbers, is in `java-load-walkthrough.md`.

## Mistakes people make

- **Paging with OFFSET on a list that changes.** Rows are skipped or repeated. Here the cursor names a place in the sort order.
- **A sort order with ties.** If two rows have the same sort key, a cursor cannot say which comes first. Events add `event_id` and seats add `seat_number` to make the order total.
- **Counting rows to find out whether a next page exists.** That is a second query. Here the service asks for `limit + 1` rows.
- **Trusting a cursor.** A client can edit it. Here every part is matched against an allow-list pattern, the event and filter are compared with the request, and the values go to the database as bound parameters.
- **Building SQL by joining text.** That invites injection. Every value here goes through a `?` placeholder.
- **No upper limit on the page size.** One request could ask for the whole table. Here the cap is 500.

## Check yourself

1. In the ten-seat example, why does page 2 miss seat D when seat B is held between the two requests?
   Answer: with `OFFSET 3` the database skips three rows of the new list (A, C, D); B leaving moved every later row one place up, so D is now inside the skipped rows.
2. Why does the service ask the database for `limit + 1` rows?
   Answer: if the extra row arrives, a next page exists; the extra row is dropped, and no second query for a count is needed.
3. What makes the order of events a total order?
   Answer: the pair `(starts_at, event_id)`; the id breaks ties between events that start at the same moment.
4. Why must a seat cursor carry its filter?
   Answer: a position means something only under the filter it was made with. Replayed under another filter, the position could skip seats; the service compares the cursor's filter with the request's and refuses a mismatch.
5. What is the largest page the API serves, and where is that checked?
   Answer: 500 items (`ContractLimits.MAX_PAGE_LIMIT`); `http/CatalogRoutes` checks it before the database is touched.

## Further reading

- RFC 8259, The JavaScript Object Notation (JSON) Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259
- RFC 4648, base64url encoding (Section 5): https://www.rfc-editor.org/rfc/rfc4648#section-5
- H2 database 2.5 documentation, SQL grammar for `SELECT` with `ORDER BY` and `LIMIT`: https://h2database.com/html/grammar.html#select
- Java SE 25 API, `java.util.Base64`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Base64.html
- Java SE 25 API, `java.sql.PreparedStatement`: https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/PreparedStatement.html
- Java Language Specification, SE 25, sealed classes and interfaces (Section 8.1.6): https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6
