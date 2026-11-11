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
