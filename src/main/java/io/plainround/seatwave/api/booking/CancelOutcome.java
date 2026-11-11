package io.plainround.seatwave.api.booking;

/** The result of releasing a hold. Cancel is idempotent by design, so it needs no key. */
public sealed interface CancelOutcome {

  /** The hold is released, now or by an earlier request; the seat is available. */
  record Released() implements CancelOutcome {}

  /** No hold has this identifier. */
  record HoldNotFound() implements CancelOutcome {}

  /**
   * The hold cannot be released any more.
   *
   * @param state CONFIRMING (a payment is in flight) or CONFIRMED
   */
  record TooLate(HoldState state) implements CancelOutcome {}
}
