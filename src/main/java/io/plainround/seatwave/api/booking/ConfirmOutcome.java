package io.plainround.seatwave.api.booking;

/** The business result of one confirm. Each is final, so each is stored under the key. */
public sealed interface ConfirmOutcome {

  /**
   * The seat is paid for and booked.
   *
   * @param booking the booking
   */
  record Booked(Booking booking) implements ConfirmOutcome {}

  /** No hold has this identifier. */
  record HoldNotFound() implements ConfirmOutcome {}

  /**
   * The hold can no longer be confirmed.
   *
   * @param state why: CANCELLED, EXPIRED or CONFIRMED (by another request)
   */
  record HoldNotActive(HoldState state) implements ConfirmOutcome {}
}
