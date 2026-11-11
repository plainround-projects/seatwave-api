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
