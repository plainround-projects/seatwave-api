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

/**
 * The rejected alternative, kept only to measure it: available seats paged with {@code LIMIT ...
 * OFFSET ...}. The offset counts rows, so a row that leaves the filter or arrives before the
 * position shifts every later row by one, and a walker skips or repeats one.
 *
 * <p>Thread-safe: it holds no mutable state.
 */
final class OffsetSeatReader {

  private final Database database;
  private final Clock clock;

  OffsetSeatReader(Database database, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

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
}
