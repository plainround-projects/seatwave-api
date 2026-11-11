package io.plainround.seatwave.api.booking;

import java.util.Objects;

/**
 * A validated request to confirm a hold.
 *
 * @param holdId the hold to pay for
 */
public record ConfirmCommand(HoldId holdId) {

  /** Checks the field. */
  public ConfirmCommand {
    Objects.requireNonNull(holdId, "holdId");
  }
}
