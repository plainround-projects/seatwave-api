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

  // Section letters sort after the empty string and seat numbers start at 1, so ("", 0) is a
  // position before every seat.
  private static final String BEFORE_FIRST_SECTION = "";
  private static final int BEFORE_FIRST_NUMBER = 0;

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
    try (Connection connection = database.connect();
        PreparedStatement query =
            connection.prepareStatement(
                """
                SELECT event_id, name, starts_at FROM events
                WHERE starts_at > ? OR (starts_at = ? AND event_id > ?)
                ORDER BY starts_at, event_id
                LIMIT ?
                """)) {
      query.setLong(1, after.get().startsAt().toEpochMilli());
      query.setLong(2, after.get().startsAt().toEpochMilli());
      query.setLong(3, after.get().eventId());
      query.setInt(4, limit + 1);
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
      return Result.success(
          pageOf(
              events,
              limit,
              lastEvent ->
                  Cursors.encode(
                      new Cursors.EventPosition(lastEvent.startsAt(), lastEvent.eventId()))));
    }
  }

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

  private static boolean eventExists(Connection connection, long eventId) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM events WHERE event_id = ?")) {
      query.setLong(1, eventId);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static <T> Page<T> pageOf(List<T> fetched, int limit, Function<T, String> cursorAfter) {
    if (fetched.size() <= limit) {
      return new Page.Last<>(fetched);
    }
    List<T> items = fetched.subList(0, limit);
    return new Page.More<>(items, cursorAfter.apply(items.getLast()));
  }

  private static void requirePositive(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be 1 or more");
    }
  }
}
