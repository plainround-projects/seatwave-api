package io.plainround.seatwave.api.booking;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identifier of a hold: a random UUID, lower case. It works as a capability (whoever knows it
 * can confirm or cancel the hold), so it is never shown to anyone but the client that made it.
 *
 * @param value the UUID in its canonical lowercase text form
 */
public record HoldId(String value) {

  /** The contract's Uuid schema. */
  public static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Checks the value. */
  public HoldId {
    if (!UUID_TEXT.matcher(Objects.requireNonNull(value, "value")).matches()) {
      throw new IllegalArgumentException("a hold id is a lowercase UUID");
    }
  }

  /**
   * Makes a new identifier.
   *
   * @return a random identifier; {@link UUID#randomUUID()} draws from a cryptographically strong
   *     generator, so it cannot be guessed
   */
  public static HoldId random() {
    return new HoldId(UUID.randomUUID().toString());
  }
}
