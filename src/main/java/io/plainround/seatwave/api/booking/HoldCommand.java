package io.plainround.seatwave.api.booking;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated request to hold one seat. It cannot exist with a bad field, so code that receives one
 * never checks again.
 *
 * @param eventId the event, 1 or more
 * @param seatLabel the seat, a section letter A to T, a hyphen and a number, for example A-7
 * @param customer who holds it, 1 to 64 of A-Z a-z 0-9 _ -
 */
public record HoldCommand(long eventId, String seatLabel, String customer) {

  /** The seat label rule of the contract's SeatLabel schema. */
  public static final Pattern SEAT_LABEL = Pattern.compile("[A-T]-[1-9][0-9]{0,3}");

  /** The customer rule of the contract's HoldRequest schema. */
  public static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** Checks the fields. */
  public HoldCommand {
    if (eventId < 1) {
      throw new IllegalArgumentException("eventId must be 1 or more");
    }
    if (!SEAT_LABEL.matcher(Objects.requireNonNull(seatLabel, "seatLabel")).matches()) {
      throw new IllegalArgumentException("seat must look like A-7");
    }
    if (!CUSTOMER.matcher(Objects.requireNonNull(customer, "customer")).matches()) {
      throw new IllegalArgumentException("customer must be 1 to 64 of A-Z a-z 0-9 _ -");
    }
  }
}
