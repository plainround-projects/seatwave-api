package io.plainround.seatwave.api.booking;

/** The business result of one hold attempt. Each is final, so each is stored under the key. */
public sealed interface HoldOutcome {

  /**
   * The seat is now held by this request.
   *
   * @param hold the new hold
   */
  record Held(Hold hold) implements HoldOutcome {}

  /** The seat exists but someone else holds it or bought it. */
  record SeatTaken() implements HoldOutcome {}

  /** The event or the seat does not exist. */
  record SeatNotFound() implements HoldOutcome {}
}
