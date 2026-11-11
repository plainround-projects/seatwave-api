package io.plainround.seatwave.api.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * The one error shape of version 1: RFC 9457 problem details, with an {@code errors} extension that
 * lists each field at fault.
 *
 * @param type a URI that names the kind of problem; clients branch on it
 * @param title a short summary of the type, for people
 * @param status the HTTP status code, repeated so the body stands alone in a log
 * @param detail this occurrence, for people
 * @param instance this occurrence's identifier, the correlation id of the server's log lines
 * @param errors the fields at fault; left out of the JSON when there are none
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ProblemBody(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    List<FieldError> errors) {

  /** Copies the list and checks the fields. */
  public ProblemBody {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(instance, "instance");
    errors = List.copyOf(errors);
  }
}
