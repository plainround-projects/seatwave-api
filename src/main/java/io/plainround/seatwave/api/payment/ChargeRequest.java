package io.plainround.seatwave.api.payment;

import java.util.Objects;

/**
 * The body of a charge, as the stand-in provider's contract defines it.
 *
 * @param amount the amount, a decimal with two digits after the point
 * @param currency the ISO 4217 code
 * @param reference SeatWave's reference for the charge: the hold id
 */
public record ChargeRequest(String amount, String currency, String reference) {

  /** Checks the fields. */
  public ChargeRequest {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(reference, "reference");
  }
}
