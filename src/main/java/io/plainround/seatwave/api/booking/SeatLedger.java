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
