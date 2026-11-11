package io.plainround.seatwave.api.http;

import java.net.URI;

/**
 * Every problem SeatWave version 1 can answer with. A client acts on the {@code type} URI and the
 * status, never on the title or the detail, which are for people and may be reworded.
 */
enum ProblemType {
  INVALID_REQUEST(400, "invalid-request", "The request does not match the contract"),
  NOT_FOUND(404, "not-found", "No such resource"),
  METHOD_NOT_ALLOWED(405, "method-not-allowed", "This path does not take this method"),
  SEAT_UNAVAILABLE(409, "seat-unavailable", "The seat is held or sold"),
  HOLD_NOT_ACTIVE(409, "hold-not-active", "The hold can no longer be confirmed"),
  CONFIRM_IN_PROGRESS(409, "confirm-in-progress", "A confirm for this hold is running"),
  HOLD_NOT_CANCELLABLE(409, "hold-not-cancellable", "The hold is being paid for or is booked"),
  BODY_TOO_LARGE(413, "body-too-large", "The body is over 1,024 bytes"),
  UNSUPPORTED_MEDIA_TYPE(415, "unsupported-media-type", "The body must be application/json"),
  KEY_REUSED(422, "idempotency-key-reused", "This Idempotency-Key was used with another body"),
  INTERNAL_ERROR(500, "internal-error", "SeatWave failed; the request may be retried"),
  SERVICE_BUSY(503, "service-busy", "SeatWave is at its limit; retry after Retry-After"),
  STORAGE_UNAVAILABLE(503, "storage-unavailable", "The database did not answer; retry"),
  PAYMENT_UNAVAILABLE(
      503, "payment-unavailable", "The payment provider did not confirm; retry with the same key");

  // Documentation names under the reserved .example domain (RFC 2606), as RFC 9457 suggests.
  private static final String TYPE_BASE = "https://seatwave.example/problems/";

  private final int status;
  private final URI type;
  private final String title;

  ProblemType(int status, String slug, String title) {
    this.status = status;
    this.type = URI.create(TYPE_BASE + slug);
    this.title = title;
  }

  int status() {
    return status;
  }

  URI type() {
    return type;
  }

  String title() {
    return title;
  }
}
