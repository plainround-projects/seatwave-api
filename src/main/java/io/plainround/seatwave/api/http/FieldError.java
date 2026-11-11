package io.plainround.seatwave.api.http;

import java.util.Objects;

/**
 * One field that broke the contract, in the shape of RFC 9457's own example.
 *
 * @param pointer a JSON pointer into the body, or the name of a header or a query parameter
 * @param detail what is wrong
 */
public record FieldError(String pointer, String detail) {

  /** Checks the fields. */
  public FieldError {
    Objects.requireNonNull(pointer, "pointer");
    Objects.requireNonNull(detail, "detail");
  }
}
