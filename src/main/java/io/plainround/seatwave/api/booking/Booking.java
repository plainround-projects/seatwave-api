package io.plainround.seatwave.api.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A paid seat.
 *
 * @param bookingId its identifier, a lowercase UUID
 * @param holdId the hold it came from
 * @param eventId the event
 * @param seatLabel the seat
 * @param paymentId the provider's identifier of the one charge
 * @param amount what was charged, with two decimals
 * @param currency the ISO 4217 code
 * @param confirmedAt when the booking was written
 */
public record Booking(
    String bookingId,
    HoldId holdId,
    long eventId,
    String seatLabel,
    String paymentId,
    BigDecimal amount,
    String currency,
    Instant confirmedAt) {

  /** Checks the fields. */
  public Booking {
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
  }
}
