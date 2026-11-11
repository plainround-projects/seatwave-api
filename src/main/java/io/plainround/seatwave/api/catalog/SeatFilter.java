package io.plainround.seatwave.api.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which seats a list shows.
 *
 * @param section one section letter, or {@link #ANY_SECTION} for all
 * @param state one state, or {@link StateFilter#ANY} for all
 */
public record SeatFilter(String section, StateFilter state) {

  /** The section value that means every section. */
  public static final String ANY_SECTION = "*";

  private static final Pattern SECTION = Pattern.compile("[A-T]|\\*");

  /** Checks the fields. */
  public SeatFilter {
    if (!SECTION.matcher(Objects.requireNonNull(section, "section")).matches()) {
      throw new IllegalArgumentException("section must be A to T");
    }
    Objects.requireNonNull(state, "state");
  }

  /** A state filter: one of the map's three states, or any. */
  public enum StateFilter {
    /** Every state. */
    ANY,
    /** Only available seats. */
    AVAILABLE,
    /** Only held seats. */
    HELD,
    /** Only sold seats. */
    SOLD
  }
}
