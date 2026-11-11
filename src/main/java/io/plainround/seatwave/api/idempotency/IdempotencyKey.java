package io.plainround.seatwave.api.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client-chosen key that names one intended write. Valid by construction: 1 to 64 letters,
 * digits, hyphens or underscores.
 *
 * @param value the key, without the quotes of the header syntax
 */
public record IdempotencyKey(String value) {

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  // The IETF draft makes the header an RFC 9651 String: the key inside double quotes. Only the
  // characters of KEY are allowed inside, so no escape sequence can appear.
  private static final Pattern HEADER = Pattern.compile("\"([A-Za-z0-9_-]{1,64})\"");

  /** Checks the value. */
  public IdempotencyKey {
    if (!KEY.matcher(Objects.requireNonNull(value, "value")).matches()) {
      throw new IllegalArgumentException("an idempotency key is 1 to 64 of A-Z a-z 0-9 _ -");
    }
  }

  /**
   * Reads the Idempotency-Key header.
   *
   * @param headerValue the header as received, for example {@code "8e03978e-..."} with quotes
   * @return the key, or empty if the header does not match the contract's syntax
   */
  public static Optional<IdempotencyKey> fromHeader(String headerValue) {
    if (headerValue == null) {
      return Optional.empty();
    }
    Matcher matcher = HEADER.matcher(headerValue.strip());
    return matcher.matches() ? Optional.of(new IdempotencyKey(matcher.group(1))) : Optional.empty();
  }
}
