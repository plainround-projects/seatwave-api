package io.plainround.seatwave.api.payment;

import java.util.Objects;

/** What one charge call learned. Anything but a confirmed capture is "unavailable". */
public sealed interface PaymentOutcome {

  /**
   * The provider confirmed the charge.
   *
   * @param paymentId the provider's identifier of the charge
   */
  record Captured(String paymentId) implements PaymentOutcome {

    /** Checks the field. */
    public Captured {
      Objects.requireNonNull(paymentId, "paymentId");
    }
  }

  /**
   * The provider did not confirm a charge. The money may or may not have moved: a timeout after the
   * provider captured looks exactly like a timeout before, which is why the caller retries with the
   * same provider key instead of deciding "no charge".
   *
   * @param reason what happened
   */
  record Unavailable(Reason reason) implements PaymentOutcome {

    /** Checks the field. */
    public Unavailable {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Why a charge was not confirmed. */
  enum Reason {
    /** No complete reply within the request timeout. */
    TIMEOUT,
    /** No connection could be made. */
    UNREACHABLE,
    /** The provider answered with something other than a capture. */
    PROVIDER_ERROR,
    /** The waiting thread was interrupted, which happens when the server shuts down. */
    INTERRUPTED
  }
}
