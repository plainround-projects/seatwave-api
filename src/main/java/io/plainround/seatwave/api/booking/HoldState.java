package io.plainround.seatwave.api.booking;

/** Where a hold is in its life. The contract's Hold.state lists the same five, in lower case. */
public enum HoldState {
  /** The seat is held and the hold has not expired. */
  HELD,
  /** A confirm owns the seat while it waits for the payment provider. */
  CONFIRMING,
  /** The hold became a booking. */
  CONFIRMED,
  /** The client released the hold. */
  CANCELLED,
  /** The hold's 300 seconds passed without a confirm. */
  EXPIRED
}
