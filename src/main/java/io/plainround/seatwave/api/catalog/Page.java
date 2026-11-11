package io.plainround.seatwave.api.catalog;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of a collection. A page either names where the next one starts or is the last, so no
 * field is ever null: the JSON of a last page simply has no {@code nextCursor}.
 *
 * @param <T> the item type
 */
public sealed interface Page<T> {

  /**
   * The items, in order.
   *
   * @return the items, never more than the requested limit
   */
  List<T> items();

  /**
   * Converts every item, keeping the cursor.
   *
   * @param convert the conversion
   * @param <R> the new item type
   * @return the converted page
   */
  default <R> Page<R> map(Function<T, R> convert) {
    List<R> converted = items().stream().map(convert).toList();
    return switch (this) {
      case More<T> more -> new More<>(converted, more.nextCursor());
      case Last<T> last -> new Last<>(converted);
    };
  }

  /**
   * A page with more after it.
   *
   * @param items the items
   * @param nextCursor the opaque position after the last item
   * @param <T> the item type
   */
  record More<T>(List<T> items, String nextCursor) implements Page<T> {

    /** Copies the items and checks the cursor. */
    public More {
      items = List.copyOf(items);
      Objects.requireNonNull(nextCursor, "nextCursor");
    }
  }

  /**
   * The last page.
   *
   * @param items the items, possibly none
   * @param <T> the item type
   */
  record Last<T>(List<T> items) implements Page<T> {

    /** Copies the items. */
    public Last {
      items = List.copyOf(items);
    }
  }
}
