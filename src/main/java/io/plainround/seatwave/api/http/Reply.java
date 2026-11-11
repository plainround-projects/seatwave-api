package io.plainround.seatwave.api.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One response, ready to send.
 *
 * @param status the HTTP status code
 * @param contentType the media type of the body, or an empty string when there is no body
 * @param body the body text, sent as UTF-8
 * @param headers further headers, kept in the order they were added (the JDK server chooses the
 *     order on the wire)
 */
public record Reply(int status, String contentType, String body, Map<String, String> headers) {

  static final String JSON = "application/json";
  static final String PROBLEM_JSON = "application/problem+json";
  private static final int NO_CONTENT = 204;

  /** Copies the headers, keeping their order, and checks the fields. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(body, "body");
    // Map.copyOf would lose the order; an unmodifiable copy of an ordered map keeps it.
    headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }

  static Reply json(int status, String body) {
    return new Reply(status, JSON, body, Map.of());
  }

  static Reply noContent() {
    return new Reply(NO_CONTENT, "", "", Map.of());
  }

  /**
   * The same reply with one more header.
   *
   * @param name the header name
   * @param value its value
   * @return a copy with the header
   */
  Reply withHeader(String name, String value) {
    Map<String, String> more = new LinkedHashMap<>(headers);
    more.put(name, value);
    return new Reply(status, contentType, body, more);
  }
}
