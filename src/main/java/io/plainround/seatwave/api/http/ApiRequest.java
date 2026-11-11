package io.plainround.seatwave.api.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One request as the handlers see it: no socket, no JDK server type, the body already read up to
 * the contract's limit.
 *
 * @param method the HTTP method
 * @param path the path, without the query
 * @param rawQuery the query string as sent, or an empty string
 * @param headers the request headers, names in lower case, the first value of each
 * @param body the body as UTF-8 text, at most the contract's limit
 * @param bodyTooLarge true when the body was longer than the limit and was cut off
 * @param pathValues the values the route's template captured, in order
 * @param correlationId the identifier of this request in the log lines and in problem bodies
 */
public record ApiRequest(
    String method,
    String path,
    String rawQuery,
    Map<String, String> headers,
    String body,
    boolean bodyTooLarge,
    List<String> pathValues,
    String correlationId) {

  /** Copies the collections and checks the fields. */
  public ApiRequest {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    headers = Map.copyOf(headers);
    Objects.requireNonNull(body, "body");
    pathValues = List.copyOf(pathValues);
    Objects.requireNonNull(correlationId, "correlationId");
  }

  /**
   * Reads one header.
   *
   * @param name the header name, in any case
   * @return its first value, or empty if it is absent
   */
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
  }

  /**
   * The same request with the values its route captured.
   *
   * @param values the captured values
   * @return a copy carrying them
   */
  ApiRequest withPathValues(List<String> values) {
    return new ApiRequest(
        method, path, rawQuery, headers, body, bodyTooLarge, values, correlationId);
  }
}
