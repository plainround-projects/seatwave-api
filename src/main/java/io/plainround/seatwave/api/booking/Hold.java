package io.plainround.seatwave.api.booking;

import java.time.Instant;
import java.util.Objects;

/**
 * A hold as the database holds it now.
 *
 * @param holdId its identifier
 * @param eventId the event
 * @param seatLabel the seat
 * @param state its state at the time it was read
 * @param expiresAt when the hold ends unless it is confirmed
 */
public record Hold(
    HoldId holdId, long eventId, String seatLabel, HoldState state, Instant expiresAt) {

  /** Checks the fields. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
