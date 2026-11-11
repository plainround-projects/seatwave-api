package io.plainround.seatwave.api.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * An event in the catalog.
 *
 * @param eventId its identifier
 * @param name its name
 * @param startsAt when it starts
 */
public record EventSummary(long eventId, String name, Instant startsAt) {

  /** Checks the fields. */
  public EventSummary {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(startsAt, "startsAt");
  }
}
