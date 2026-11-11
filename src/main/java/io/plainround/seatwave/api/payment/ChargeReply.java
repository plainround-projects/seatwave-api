package io.plainround.seatwave.api.payment;

import java.util.Objects;

/**
 * The stand-in provider's reply to a charge.
 *
 * @param paymentId the provider's identifier of the charge
 * @param status always {@code captured}: the provider never answers 2xx for anything else
 */
public record ChargeReply(String paymentId, String status) {

  /** Checks the fields. */
  public ChargeReply {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(status, "status");
  }
}
