package io.plainround.seatwave.api.idempotency;

import java.util.Objects;

/**
 * The response a completed request produced, kept under its key so a retry gets it again.
 *
 * @param status the HTTP status code
 * @param location the Location header, or an empty string when the response has none
 * @param body the JSON body
 */
public record StoredReply(int status, String location, String body) {

  // HTTP status codes are three digits (RFC 9110, section 15).
  private static final int LOWEST_STATUS = 100;
  private static final int HIGHEST_STATUS = 599;

  /** Checks the fields. */
  public StoredReply {
    if (status < LOWEST_STATUS || status > HIGHEST_STATUS) {
      throw new IllegalArgumentException("not an HTTP status: " + status);
    }
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(body, "body");
  }
}
