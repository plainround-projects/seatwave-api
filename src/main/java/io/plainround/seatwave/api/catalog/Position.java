package io.plainround.seatwave.api.catalog;

import java.util.Objects;

/** Where a page starts: at the beginning, or after the item a cursor names. */
public sealed interface Position {

  /** The first page. */
  record First() implements Position {}

  /**
   * The page after a previous one.
   *
   * @param cursor the previous page's nextCursor, as the client sent it back
   */
  record After(String cursor) implements Position {

    /** Checks the field. */
    public After {
      Objects.requireNonNull(cursor, "cursor");
    }
  }
}
