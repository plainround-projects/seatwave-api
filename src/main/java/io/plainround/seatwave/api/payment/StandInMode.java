package io.plainround.seatwave.api.payment;

/** How the stand-in payment provider behaves. It never pretends: a failure is never a 2xx. */
public enum StandInMode {
  /** Capture the charge and answer 201 at once. */
  OK,
  /**
   * Capture the charge, then wait longer than SeatWave's timeout before answering: the money moved
   * but SeatWave never hears so. This is the case idempotency exists for.
   */
  CHARGE_THEN_TIMEOUT,
  /** Capture nothing and answer 503. */
  FAIL
}
