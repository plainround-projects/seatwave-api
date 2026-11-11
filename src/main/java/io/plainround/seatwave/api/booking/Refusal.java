package io.plainround.seatwave.api.booking;

/**
 * Why a keyed write was refused before or without a final outcome. None is stored under the key:
 * the client may correct the request (a new key) or retry it (the same key).
 */
public sealed interface Refusal {

  /** The key was used with a different body (422 in the contract). */
  record KeyReusedWithOtherBody() implements Refusal {}

  /** Another attempt is paying for this hold right now (409): retry after a moment. */
  record ConfirmInProgress() implements Refusal {}

  /** The payment provider did not answer in time or failed (503): retry with the same key. */
  record PaymentUnavailable() implements Refusal {}
}
