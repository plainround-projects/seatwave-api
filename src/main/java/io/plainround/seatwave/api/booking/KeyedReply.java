package io.plainround.seatwave.api.booking;

import io.plainround.seatwave.api.idempotency.StoredReply;
import java.util.Objects;

/**
 * The response to a keyed write, and whether it was produced now or replayed from the key store.
 *
 * @param reply the response
 * @param source where it came from
 */
public record KeyedReply(StoredReply reply, Source source) {

  /** Checks the fields. */
  public KeyedReply {
    Objects.requireNonNull(reply, "reply");
    Objects.requireNonNull(source, "source");
  }

  /** Where a keyed response came from. */
  public enum Source {
    /** This request did the work. */
    FIRST,
    /** An earlier request with the same key did the work; this is its stored response. */
    REPLAY
  }
}
