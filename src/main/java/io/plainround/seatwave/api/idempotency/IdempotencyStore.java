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

  // SQLSTATE class 23 is an integrity violation; 23505 is a unique or primary key violation.
  private static final String UNIQUE_VIOLATION = "23505";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String COMPLETED = "COMPLETED";

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
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT fingerprint, state, response_status, response_location, response_body,"
                + " expires_at FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?")) {
      query.setString(1, claim.endpoint());
      query.setString(2, claim.key().value());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          // The other claim was rolled back between our insert and our read: try once more.
          return tryInsert(connection, claim, now)
              ? new ClaimResult.Claimed()
              : new ClaimResult.Resumed();
        }
        if (row.getLong("expires_at") <= now.toEpochMilli()) {
          // An expired key is forgotten: this request is new, whatever the old one did.
          forget(connection, claim);
          return tryInsert(connection, claim, now)
              ? new ClaimResult.Claimed()
              : new ClaimResult.Resumed();
        }
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
  }

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
    } catch (SQLException insertFailure) {
      // H2 undoes only the failed statement and keeps the transaction usable; PostgreSQL would
      // abort the whole transaction, so there this insert would run inside a savepoint.
      if (UNIQUE_VIOLATION.equals(insertFailure.getSQLState())) {
        return false;
      }
      throw insertFailure;
    }
  }

  private static void forget(Connection connection, KeyClaim claim) throws SQLException {
    try (PreparedStatement delete =
        connection.prepareStatement(
            "DELETE FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?")) {
      delete.setString(1, claim.endpoint());
      delete.setString(2, claim.key().value());
      delete.executeUpdate();
    }
  }
}
