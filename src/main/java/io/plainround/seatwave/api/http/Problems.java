package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.json.JsonCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one place a problem becomes a response (guideline: map errors once, at the boundary). No
 * stack trace, SQL state or class name ever reaches a client; the {@code instance} lets support
 * find the server's log lines for this request.
 *
 * <p>Thread-safe: it holds only the thread-safe codec.
 */
final class Problems {

  private final JsonCodec json;

  Problems(JsonCodec json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  Reply reply(ProblemType type, String detail, String correlationId) {
    return reply(type, detail, List.of(), correlationId);
  }

  Reply reply(ProblemType type, String detail, List<FieldError> errors, String correlationId) {
    ProblemBody body =
        new ProblemBody(
            type.type().toString(),
            type.title(),
            type.status(),
            detail,
            "urn:uuid:" + correlationId,
            errors);
    return new Reply(type.status(), Reply.PROBLEM_JSON, json.write(body), Map.of());
  }

  Reply invalid(List<FieldError> errors, String correlationId) {
    return reply(
        ProblemType.INVALID_REQUEST,
        errors.size() == 1 ? "one field is invalid" : errors.size() + " fields are invalid",
        errors,
        correlationId);
  }
}
