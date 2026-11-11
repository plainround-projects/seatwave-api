package io.plainround.seatwave.api.catalog;

/**
 * What the seat map shows. Module 1's three words: who holds a seat, and a payment in flight, are
 * never shown, so both HELD and CONFIRMING show as held.
 */
public enum SeatAvailability {
  /** Anyone may hold it. */
  AVAILABLE,
  /** Someone holds it, or is paying for it. */
  HELD,
  /** It is booked. */
  SOLD
}
