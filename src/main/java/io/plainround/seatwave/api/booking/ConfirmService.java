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
        if (ledger.leaseForConfirm(connection, holdId, now, now.plus(lease))) {
          connection.commit();
          return Result.success(Optional.empty());
        }
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
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

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
        }
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

  private void endLease(HoldId holdId) throws SQLException {
    try (Connection connection = database.connect()) {
      ledger.releaseLease(connection, holdId, clock.instant());
    }
  }

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
