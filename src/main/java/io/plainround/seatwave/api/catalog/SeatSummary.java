package io.plainround.seatwave.api.catalog;

import java.util.Objects;

/**
 * A seat on the seat map.
 *
 * @param seatLabel the seat, for example A-7
 * @param section its section letter
 * @param seatNumber its number inside the section, the second part of the map order
 * @param state what the map shows
 */
public record SeatSummary(
    String seatLabel, String section, int seatNumber, SeatAvailability state) {

  /** Checks the fields. */
  public SeatSummary {
    Objects.requireNonNull(seatLabel, "seatLabel");
    Objects.requireNonNull(section, "section");
    Objects.requireNonNull(state, "state");
  }
}
