# Walkthrough: the `http` and `json` packages

## What it is

This file reads sixteen Java source files line by line: the fifteen files of the `http/` package (`ApiServer`, `ApiRequest`, `Reply`, `Problems`, `ProblemType`, `ProblemBody`, `FieldError`, `Parameters`, `CatalogRoutes`, `BookingRoutes`, `Version0Routes`, `OperationsRoutes`, `V1Views`, `Version0Views`, `ApiMetrics`) and the one file of the `json/` package (`JsonCodec`).

The `http` package is the **boundary** of the program. On one side is the network: bytes arriving on a socket. On the other side are the services (`catalog`, `booking`, `idempotency`), which know nothing about HTTP. This package turns bytes into an `ApiRequest`, picks the route, checks the input against the contract, calls a service, and turns the answer, or the error, into an HTTP reply.

Read `http-and-the-jdk-server.md` first if you have never seen an HTTP request. Read `jackson.md` for the JSON library that `JsonCodec` wraps.

## Why this repository uses it

The lesson of Module 7 is "the contract first": the OpenAPI document says what a request and a reply look like, and the code must match it exactly. All of the matching is done here:

- Every input is validated **before any work** is done, with errors that name the field at fault.
- Every failure becomes the **one error shape** of the contract, problem details, in **one place** (`Problems`). No stack trace, SQL state or class name ever reaches a client.
- Module 1's old paths still answer exactly as before, with `Deprecation`, `Sunset` and `Link` headers (`Version0Routes`).
- The server refuses work when it is overloaded (a 503 with `Retry-After`) instead of slowing down for everyone.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/`) | What it does |
|---|---|
| `http/ApiServer.java` | Creates the JDK server, reads each request, matches a route, limits requests in flight, maps failures, sends the reply. |
| `http/ApiRequest.java`, `http/Reply.java` | The request and reply as plain records. |
| `http/Problems.java`, `ProblemType.java`, `ProblemBody.java`, `FieldError.java` | The one error shape (problem details). |
| `http/Parameters.java` | Reads a query string or form body against an allow-list of names. |
| `http/CatalogRoutes.java` | `GET /v1/events` and `GET /v1/events/{eventId}/seats`. |
| `http/BookingRoutes.java` | Hold, read hold, cancel, confirm, read booking. |
| `http/Version0Routes.java`, `Version0Views.java` | Module 1's four paths, kept and marked deprecated. |
| `http/OperationsRoutes.java`, `ApiMetrics.java` | `GET /health` and `GET /metrics`. |
| `http/V1Views.java` | The JSON shapes of version 1. |
| `json/JsonCodec.java` | JSON in and out, bound only to known records. |

## How it works underneath

### How one request travels from the socket to a handler

1. **The socket.** The server listens on `127.0.0.1` and a port. A client (for example `curl`) opens a TCP connection and sends text: a request line such as `POST /v1/holds HTTP/1.1`, header lines, an empty line, and a body. The JDK's server code (`com.sun.net.httpserver`) reads and parses this into an `HttpExchange` object.
2. **The executor.** The JDK server does not run our code on its own thread. It hands the exchange to the **executor** we gave it. Ours starts one **virtual thread** for each request. A virtual thread is a very cheap thread managed by the JDK; when it waits for the database or the network it costs almost nothing.
3. **One handler.** Our server registers a single handler for the path `/`, which matches everything. That handler is the method `ApiServer.serve`. The route choice is made by our own code, not by the JDK.
4. **The correlation id.** `serve` creates a random id for this request and puts it in the logging context, so every log line of this request carries it. The id is also the `instance` of any error body, so a client that quotes it lets support find the log lines.
5. **Reading.** `ApiServer.read` copies the headers (names in lower case) and reads at most 1,025 bytes of body. One byte more than the 1,024 limit is enough to tell "exactly at the limit" from "too large" without reading on.
6. **Matching.** `match` looks for a route with the same method and a path that fits the route's pattern. No match is a 404 (or a 405 if the path exists for another method).
7. **Admission.** A semaphore with as many permits as `http.maxInFlight` counts the requests being served. With no permit left, the request is refused at once with a 503.
8. **The handler.** The route's method (for example `BookingRoutes.hold`) validates, calls a service, and returns a `Reply`.
9. **Sending.** `send` writes the status line, the headers and the body, and the exchange is closed.
10. **Counting.** In a `finally` block, whatever happened, the request is counted in the metrics and one log line is written.

### How input is validated before any work

For each route the order is the same, and the database is touched only at the end:

1. Path values (`eventId`, `holdId`) are checked against a pattern.
2. For a write: body too large (413), then wrong content type (415).
3. The `Idempotency-Key` header is parsed.
4. The body is bound to a record by Jackson, strictly (unknown field, wrong type, duplicate field, trailing data all fail).
5. Each field is checked against the contract's rules.
6. **All** the errors found in steps 3 to 5 are collected, and if there is any, the reply is one 400 that lists every field at fault.
7. Only then is a service called.

A client that sends three wrong fields learns about three, not one.

### How an error becomes a problem-details reply

Every failure ends in `Problems.reply(type, detail, correlationId)`. There are four sources:

- **The request is wrong.** A route calls `problems.invalid(errors, correlationId)` or `problems.reply(...)`: 400, 404, 405, 413, 415, 422.
- **A service says no.** A service returns a `Result` with an error value (seat taken, hold not active, key reused). The route's `switch` maps each one to a `ProblemType`.
- **The database fails.** `ApiServer.admit` catches `SQLException`, logs the details on the server, and replies with `storage-unavailable` (503) and `Retry-After`.
- **A bug.** `ApiServer.admit` catches `RuntimeException`, logs it, and replies with `internal-error` (500) that asks the client to quote the instance.

The reply has the media type `application/problem+json` and a body with `type`, `title`, `status`, `detail`, `instance` and, for field errors, `errors`. A client acts on `type` and the status, never on the words.

## Walkthrough of this repository's use

### `json/JsonCodec.java`

```java
package io.plainround.seatwave.api.json;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.sviperll.result4j.Result;
import java.util.Objects;
import java.util.stream.Collectors;
```

Imports. `ObjectMapper` and `JsonMapper` are Jackson's main classes. `DeserializationFeature`, `MapperFeature` and `StreamReadFeature` are the switches this class sets. `UnrecognizedPropertyException` and `MismatchedInputException` are two kinds of failure Jackson reports. `Result` is result4j's success-or-error type.

```java
/**
 * JSON in and out, bound only to known records. Unknown fields, nulls for numbers, trailing data
 * and duplicate fields are refused, so a body either matches a record exactly or is rejected with a
 * reason that names the field.
 *
 * <p>Thread-safe: the mapper is configured once in the constructor and only read afterwards, which
 * is the use Jackson documents as safe across threads.
 */
public final class JsonCodec {
```

The Javadoc states the rule: bound only to known records; unknown fields, nulls for numbers, trailing data and duplicate fields are refused; and a refusal names the field. "Thread-safe: the mapper is configured once in the constructor and only read afterwards" is the way Jackson documents its mapper as safe to share between threads.

```java
  private final ObjectMapper mapper =
      JsonMapper.builder()
          // A field the contract does not list is an error (additionalProperties: false), never
          // ignored: ignoring it would let a typo pass silently, and is how mass assignment starts.
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          // "1" and 1.5 are not the integer the contract asks for: no silent conversion.
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
```

The mapper is built once. `JsonMapper.builder()` starts a builder and `.build()` ends it. Each `.enable(...)` or `.disable(...)` flips one switch:

- `FAIL_ON_UNKNOWN_PROPERTIES` on: a field the record does not have is an error. The contract says `additionalProperties: false`. Ignoring unknown fields would let a typo pass in silence, and it is how "mass assignment" starts (a client setting a field it should not).
- `FAIL_ON_NULL_FOR_PRIMITIVES` on: a JSON `null` for a Java `int` or `long` is an error and not a quiet zero.
- `ALLOW_COERCION_OF_SCALARS` off: the text `"1"` is not accepted where a number is expected.
- `ACCEPT_FLOAT_AS_INT` off: `1.5` is not accepted for a whole number, and is not rounded.
- `FAIL_ON_TRAILING_TOKENS` on: anything after the first complete JSON value (for example `{...} {}`) is an error.
- `STRICT_DUPLICATE_DETECTION` on: the same field name twice in one object is an error. Without this, the last value wins and two parsers can disagree about one request.

```java
  /**
   * Reads a body into a record.
   *
   * @param json the body
   * @param type the record it must match
   * @param <T> the record type
   * @return the record, or a problem that names the JSON pointer of the field at fault
   */
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
    try {
      T value = mapper.readValue(json, type);
      if (value == null) {
        return Result.error(new BindingProblem("", "the body must be a JSON object"));
      }
      return Result.success(value);
    } catch (UnrecognizedPropertyException unknownField) {
      return Result.error(
          new BindingProblem(
              "/" + unknownField.getPropertyName(), "is not a field of this request"));
    } catch (MismatchedInputException wrongType) {
      return Result.error(new BindingProblem(pointerOf(wrongType), "has the wrong type"));
    } catch (JacksonException malformed) {
      return Result.error(new BindingProblem("", "the body is not valid JSON"));
    }
  }
```

`read` binds a JSON text to a record type. `<T> Result<T, BindingProblem>` is a generic method: it returns the record or a problem. `mapper.readValue(json, type)` does the binding. The four outcomes:

- A `null` result (the body was the JSON text `null`) becomes "the body must be a JSON object" at the pointer `""`.
- `UnrecognizedPropertyException` becomes the pointer `/` plus the field name, with "is not a field of this request".
- `MismatchedInputException` (a wrong type, such as text for a number) becomes the pointer of the field and "has the wrong type".
- Any other `JacksonException` (the text is not JSON at all, or a duplicate field) becomes the whole-body pointer `""` and "the body is not valid JSON".

The order of the `catch` blocks matters: the two specific kinds are subtypes of `JacksonException`, so they must come first. A **JSON pointer** (RFC 6901) is a path into a JSON document: `/eventId` means the field `eventId` of the top-level object.

```java
  /**
   * Writes a value as JSON.
   *
   * @param value a record, a list or a map of them
   * @return the JSON text
   * @throws IllegalArgumentException if the value cannot be written, which is a programming error
   */
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException notWritable) {
      throw new IllegalArgumentException("not writable as JSON", notWritable);
    }
  }
```

`write` turns a record, a list or a map into JSON text. A failure to write means the programmer passed something Jackson cannot write, so it is a bug, and it is rethrown as an unchecked `IllegalArgumentException` with the original as its cause.

```java
  private static String pointerOf(MismatchedInputException wrongType) {
    // RFC 6901 pointer: "/field" for each step of the path Jackson was reading.
    return wrongType.getPath().stream()
        .map(step ->
            step.getFieldName() == null ? String.valueOf(step.getIndex()) : step.getFieldName())
        .collect(Collectors.joining("/", "/", ""));
  }
```

`pointerOf` builds the pointer from the path Jackson was reading when it failed. `getPath()` is a list of steps; each step has a field name or, inside an array, an index. `.stream().map(...)` turns each step into text. `Collectors.joining("/", "/", "")` joins the steps with `/`, puts `/` at the start and nothing at the end. The comment names RFC 6901. One quirk: a failure with an empty path, such as trailing data or a JSON array in place of an object, gives the pointer `/` and not `""`.

```java
  /**
   * Why a body did not bind.
   *
   * @param pointer the JSON pointer of the field at fault, or an empty string for the whole body
   * @param detail what is wrong, in words a client developer can act on
   */
  public record BindingProblem(String pointer, String detail) {

    /** Checks the fields. */
    public BindingProblem {
      Objects.requireNonNull(pointer, "pointer");
      Objects.requireNonNull(detail, "detail");
    }
  }
```

`BindingProblem` is a record with the pointer and the detail. The compact constructor refuses `null`.

### `http/FieldError.java`, `ProblemBody.java`, `ProblemType.java`, `Problems.java`

```java
package io.plainround.seatwave.api.http;

import java.util.Objects;
```

```java
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
```

A `FieldError` says which field broke the contract and why. `pointer` is a JSON pointer into the body, or the name of a header or a query parameter (`Idempotency-Key`, `limit`). This is the shape of RFC 9457's own example.

```java
package io.plainround.seatwave.api.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
```

`JsonInclude` is a Jackson annotation. An **annotation** is a tag written with `@` that tools read.

```java
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
```

The record is the body of every error: `type`, `title`, `status`, `detail`, `instance`, and `errors`. `@JsonInclude(JsonInclude.Include.NON_EMPTY)` tells Jackson to leave out empty values, so a reply with no field errors has no `errors` key at all.

```java
  /** Copies the list and checks the fields. */
  public ProblemBody {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(instance, "instance");
    errors = List.copyOf(errors);
  }
```

The compact constructor refuses `null` for the five text and number fields and copies the `errors` list so it cannot be changed afterwards. `status` is a primitive `int` and needs no check.

```java
package io.plainround.seatwave.api.http;

import java.net.URI;

/**
 * Every problem SeatWave version 1 can answer with. A client acts on the {@code type} URI and the
 * status, never on the title or the detail, which are for people and may be reworded.
 */
enum ProblemType {
```

An **enum**: a type with a fixed set of named values, each with its own data. Package-private. The Javadoc says a client acts on `type` and the status, never on the title or detail.

```java
  INVALID_REQUEST(400, "invalid-request", "The request does not match the contract"),
  NOT_FOUND(404, "not-found", "No such resource"),
  METHOD_NOT_ALLOWED(405, "method-not-allowed", "This path does not take this method"),
  SEAT_UNAVAILABLE(409, "seat-unavailable", "The seat is held or sold"),
  HOLD_NOT_ACTIVE(409, "hold-not-active", "The hold can no longer be confirmed"),
  CONFIRM_IN_PROGRESS(409, "confirm-in-progress", "A confirm for this hold is running"),
  HOLD_NOT_CANCELLABLE(409, "hold-not-cancellable", "The hold is being paid for or is booked"),
  BODY_TOO_LARGE(413, "body-too-large", "The body is over 1,024 bytes"),
  UNSUPPORTED_MEDIA_TYPE(415, "unsupported-media-type", "The body must be application/json"),
  KEY_REUSED(422, "idempotency-key-reused", "This Idempotency-Key was used with another body"),
  INTERNAL_ERROR(500, "internal-error", "SeatWave failed; the request may be retried"),
  SERVICE_BUSY(503, "service-busy", "SeatWave is at its limit; retry after Retry-After"),
  STORAGE_UNAVAILABLE(503, "storage-unavailable", "The database did not answer; retry"),
  PAYMENT_UNAVAILABLE(
      503, "payment-unavailable", "The payment provider did not confirm; retry with the same key");

```

Fourteen problems, each with a status, a short **slug** (the last part of the type URI) and a title. For example `SEAT_UNAVAILABLE(409, "seat-unavailable", "The seat is held or sold")`. The three 503s differ by type: the server is busy, the storage did not answer, or the payment provider did not confirm. Every title is fixed text for people.

```java
  // Documentation names under the reserved .example domain (RFC 2606), as RFC 9457 suggests.
  private static final String TYPE_BASE = "https://seatwave.example/problems/";

  private final int status;
  private final URI type;
  private final String title;

  ProblemType(int status, String slug, String title) {
    this.status = status;
    this.type = URI.create(TYPE_BASE + slug);
    this.title = title;
  }
```

`TYPE_BASE` is the start of every type URI. The comment says the host is under the reserved `.example` domain (RFC 2606), as RFC 9457 suggests for documentation names: it can never be a real address. The constructor builds the `URI` once for each value.

```java
  int status() {
    return status;
  }

  URI type() {
    return type;
  }

  String title() {
    return title;
  }
```

Three accessors: `status()`, `type()` and `title()`.

```java
package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.json.JsonCodec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

```java
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
```

The Javadoc says this is the one place a problem becomes a response, and why: no stack trace, SQL state or class name ever reaches a client, and the `instance` lets support find the log lines. The class holds only the `JsonCodec`, so it is thread-safe.

```java
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
```

The first `reply` calls the second with no field errors. The second builds a `ProblemBody` from the type's URI text, title and status, the given detail, the `instance` `"urn:uuid:" + correlationId` (a URN, a name that is not an address) and the errors. It returns a `Reply` with the type's status, the media type `application/problem+json`, the JSON text and no extra headers.

```java
  Reply invalid(List<FieldError> errors, String correlationId) {
    return reply(
        ProblemType.INVALID_REQUEST,
        errors.size() == 1 ? "one field is invalid" : errors.size() + " fields are invalid",
        errors,
        correlationId);
  }
```

`invalid` is the 400 for bad input. The detail is "one field is invalid" or "N fields are invalid", and the list of `FieldError` goes in the body.

### `http/Reply.java` and `http/ApiRequest.java`

```java
package io.plainround.seatwave.api.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
```

```java
/**
 * One response, ready to send.
 *
 * @param status the HTTP status code
 * @param contentType the media type of the body, or an empty string when there is no body
 * @param body the body text, sent as UTF-8
 * @param headers further headers, in the order they are sent
 */
public record Reply(int status, String contentType, String body, Map<String, String> headers) {
```

A `Reply` is a response ready to send: status, content type, body, extra headers. (The Javadoc says the headers are "in the order they are sent"; the constructor copies them with `Map.copyOf`, which does not keep an order, so the order is not guaranteed. Clients must not rely on header order anyway.)

```java
  static final String JSON = "application/json";
  static final String PROBLEM_JSON = "application/problem+json";
  private static final int NO_CONTENT = 204;
```

Package-private constants for the two media types: `application/json` and `application/problem+json`. 204 means "No Content".

```java
  /** Copies the headers and checks the fields. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(body, "body");
    headers = Map.copyOf(headers);
  }
```

The compact constructor checks `contentType` and `body` for `null` and copies the header map into an unchangeable one.

```java
  static Reply json(int status, String body) {
    return new Reply(status, JSON, body, Map.of());
  }

  static Reply noContent() {
    return new Reply(NO_CONTENT, "", "", Map.of());
  }
```

Two **factory methods**: `json(status, body)` makes a JSON reply; `noContent()` makes the 204 reply with no body and an empty content type. `Map.of()` is an empty map.

```java
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
```

`withHeader` returns a copy of the reply with one more header. A record cannot be changed, so each change makes a new one: it copies the map into a `LinkedHashMap`, adds the pair, and builds a new `Reply`.

```java
package io.plainround.seatwave.api.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
```

```java
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
```

An `ApiRequest` is what a handler sees: no socket and no JDK server type. The `@param` lines say what each part is. `bodyTooLarge` is true when the body was longer than the limit and was cut off. `pathValues` are the values the route's `{name}` parts captured. `correlationId` is this request's id.

```java
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
```

The compact constructor refuses `null`s and copies the header map and the path values into unchangeable collections. Assigning to a parameter name (`headers = Map.copyOf(headers);`) inside a compact constructor sets the field.

```java
  /**
   * Reads one header.
   *
   * @param name the header name, in any case
   * @return its first value, or empty if it is absent
   */
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
  }
```

`header(name)` finds a header whatever the case of the name. The map holds lower-case names, so the lookup lower-cases the argument. `Optional.ofNullable(...)` gives an empty `Optional` if the header is absent.

```java
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
```

`withPathValues` returns a copy carrying the values the route captured. The router reads them from the path, then hands the handler the completed request.

### `http/Parameters.java`

```java
package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
```

```java
/**
 * Reads a query string or a form body into names and values, against an allow-list of names. A name
 * the endpoint does not take, or a name sent twice, is an error: silently picking one of two values
 * is how two layers end up disagreeing about one request.
 */
final class Parameters {

  private final Map<String, String> values;

  private Parameters(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }
```

`Parameters` reads a query string (`limit=2&cursor=abc`) or a form body into names and values, against an allow-list of names. The Javadoc says why it refuses a name sent twice: silently picking one of two values is how two layers end up disagreeing about one request. The values are held in an unchangeable map.

```java
  static Result<Parameters, List<FieldError>> parse(String encoded, Set<String> allowedNames) {
    Map<String, String> values = new LinkedHashMap<>();
    List<FieldError> errors = new ArrayList<>();
    if (encoded.isEmpty()) {
      return Result.success(new Parameters(values));
    }
```

`parse` takes the encoded text and the set of allowed names, and returns `Result<Parameters, List<FieldError>>`: the parameters, or the list of errors. An empty text is a success with no parameters.

```java
    for (String pair : encoded.split("&", -1)) {
      int separator = pair.indexOf('=');
      if (separator <= 0) {
        errors.add(new FieldError(pair, "is not a name=value pair"));
        continue;
      }
```

`encoded.split("&", -1)` cuts the text at each `&`; `-1` keeps empty pieces. `pair.indexOf('=')` finds the first `=`. If there is none, or it is the first character (`separator <= 0`), the piece is not a name-value pair and an error is recorded; `continue` goes to the next piece.

```java
      try {
        String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
        String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
        if (!allowedNames.contains(name)) {
          errors.add(new FieldError(name, "is not a parameter of this endpoint"));
        } else if (values.putIfAbsent(name, value) != null) {
          errors.add(new FieldError(name, "is given more than once"));
        }
```

`URLDecoder.decode(..., StandardCharsets.UTF_8)` undoes percent-encoding (`%20` becomes a space; `+` becomes a space) for the name and the value. A name that is not on the allow-list is an error. `values.putIfAbsent(name, value) != null` means the name was already there: "is given more than once".

```java
      } catch (IllegalArgumentException badEncoding) {
        // URLDecoder rejects a broken percent escape; that is the client's error, reported.
        errors.add(new FieldError(pair, "is not valid percent-encoding"));
      }
    }
    return errors.isEmpty() ? Result.success(new Parameters(values)) : Result.error(errors);
  }
```

A broken percent escape (`%zz`) makes `URLDecoder` throw `IllegalArgumentException`; it is the client's mistake and is reported. After the loop the method returns the errors if there are any, and the parameters if not. Note that one unknown name makes the whole parse fail, so the other parameters are not checked in the same run.

```java
  Optional<String> get(String name) {
    return Optional.ofNullable(values.get(name));
  }
```

`get(name)` gives an `Optional` with the value, or empty.

```java
  /**
   * Reads an optional whole number in a range.
   *
   * @return the value, the fallback when the parameter is absent, or empty when it is invalid (the
   *     error is added to {@code errors})
   */
  Optional<Integer> intBetween(
      String name, int minimum, int maximum, int fallback, List<FieldError> errors) {
    Optional<String> text = get(name);
    if (text.isEmpty()) {
      return Optional.of(fallback);
    }
    if (Pattern.matches("[0-9]{1,9}", text.get())) {
      int value = Integer.parseInt(text.get());
      if (value >= minimum && value <= maximum) {
        return Optional.of(value);
      }
    }
    errors.add(new FieldError(name, "must be a whole number from " + minimum + " to " + maximum));
    return Optional.empty();
  }
```

`intBetween` reads an optional whole number in a range. If the parameter is absent, it returns the fallback. `Pattern.matches("[0-9]{1,9}", text)` allows only one to nine digits, so `Integer.parseInt` cannot overflow and a sign or a space is refused. If the value is in range, it is returned. Otherwise the error "must be a whole number from 1 to 500" is added and the result is empty.

### `http/V1Views.java` and `http/Version0Views.java`

```java
package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.booking.Booking;
import io.plainround.seatwave.api.booking.Hold;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.SeatSummary;
import java.util.Locale;

/**
 * The JSON shapes of version 1, one record per schema of the contract, so Jackson writes exactly
 * the fields the contract lists. Times are ISO-8601 in UTC; Module 1's epoch milliseconds stay on
 * the version 0 paths only.
 */
final class V1Views {
```

```java
  private V1Views() {}

  record EventView(long eventId, String name, String startsAt) {
    static EventView of(EventSummary event) {
      return new EventView(event.eventId(), event.name(), event.startsAt().toString());
    }
  }

  record SeatView(String seat, String section, String state) {
    static SeatView of(SeatSummary seat) {
      return new SeatView(
          seat.seatLabel(), seat.section(), seat.state().name().toLowerCase(Locale.ROOT));
    }
  }
```

Each view is one record per schema of the contract, so Jackson writes exactly the fields the contract lists, in the order of the record. Times are ISO-8601 text in UTC. `EventView.of` copies an `EventSummary` and writes the start as `event.startsAt().toString()`. `SeatView.of` lowercases the enum name (`AVAILABLE` becomes `available`); `Locale.ROOT` keeps this independent of the computer's language (in Turkish, upper-case `I` lower-cases to a dotless `i` otherwise).

```java
  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold) {
      return new HoldView(
          hold.holdId().value(),
          hold.eventId(),
          hold.seatLabel(),
          hold.state().name().toLowerCase(Locale.ROOT),
          hold.expiresAt().toString());
    }
  }

  record BookingView(
      String bookingId,
      String holdId,
      long eventId,
      String seat,
      String paymentId,
      String amount,
      String currency,
      String confirmedAt) {
    static BookingView of(Booking booking) {
      return new BookingView(
          booking.bookingId(),
          booking.holdId().value(),
          booking.eventId(),
          booking.seatLabel(),
          booking.paymentId(),
          booking.amount().toPlainString(),
          booking.currency(),
          booking.confirmedAt().toString());
    }
  }
```

`HoldView` has the hold id, the event, the seat label, the state and the expiry. `BookingView` has the booking, hold, event, seat, payment id, amount, currency and confirm time. `amount` is `booking.amount().toPlainString()`, such as `80.00`: exact decimal text, never a floating-point number.

```java
  record HealthView(String status) {}

  /**
   * The body of POST /v1/holds as sent. Boxed and unchecked on purpose: a missing field must reach
   * the validation step, which names it, instead of failing inside the JSON reader.
   */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  /** The body of POST /v1/bookings as sent. */
  record BookingRequestBody(String holdId) {}
```

`HealthView` is the body of `GET /health`. `HoldRequestBody(Long eventId, String seat, String customer)` is the body of `POST /v1/holds` as sent. The Javadoc explains the `Long` (a boxed number that can be `null`) and the lack of checks: a missing field must reach the validation step, which names it, instead of failing inside the JSON reader. `BookingRequestBody(String holdId)` is the body of `POST /v1/bookings`.

```java
package io.plainround.seatwave.api.http;

/**
 * Module 1's JSON shapes, kept byte for byte for clients that have not moved to version 1. They are
 * not fixed here, even where version 1 is better (the error body, epoch milliseconds), because
 * fixing them would break those clients.
 */
final class Version0Views {

  private Version0Views() {}

  record EventVersion0(long eventId, String name, long startsAt) {}

  record SeatVersion0(String seat, String state) {}

  record HoldVersion0(String holdId, long expiresInSeconds) {}

  record BookingVersion0(String bookingId) {}

  record ErrorVersion0(String error) {}
}
```

Module 1's JSON shapes, kept byte for byte. They are not improved even where version 1 is better (the plain `{"error":"..."}` body, times as epoch milliseconds), because that would break the clients that have not moved.

### `http/ApiMetrics.java`

```java
package io.plainround.seatwave.api.http;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
```

Imports. `LongAdder` is a counter built for many threads adding at once. `ConcurrentHashMap` is a map safe for many threads. `TreeMap` keeps keys sorted.

```java
/**
 * Counters and latency histograms, written in the Prometheus text format at GET /metrics.
 *
 * <p>Labels are the route template (for example {@code GET /v1/holds/{holdId}}), the status code
 * and the key outcome: a few dozen values in all. Hold ids, keys and customers never become labels,
 * because each new label value is a new series to store (guideline: low-cardinality labels).
 *
 * <p>Thread-safe: every counter is a {@link LongAdder} in a concurrent map, so many request threads
 * count at once without a lock.
 */
final class ApiMetrics {
```

The Javadoc: counters and latency **histograms**, written in the Prometheus text format at `GET /metrics`. The labels are the route template, the status code and the key outcome, a few dozen values in all. Hold ids, keys and customers never become labels, because each new label value is a new series to store. Every counter is a `LongAdder` in a concurrent map, so many request threads count at once without a lock.

```java
  // Upper bounds of the latency buckets, in milliseconds; p50 and p99 are read from them.
  private static final long[] BUCKET_BOUNDS_MILLIS = {
    1, 2, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500
  };
  private static final String SEPARATOR = "\u0000";
  private static final long NANOS_PER_MICRO = 1_000;
  private static final double MICROS_PER_MILLI = 1_000.0;
```

`BUCKET_BOUNDS_MILLIS` lists the upper bounds, in milliseconds, of the latency buckets: 1, 2, 5, 10, 25, 50, 100, 250, 500, 1,000 and 2,500. A **histogram** counts how many requests took at most each bound, so a reader can estimate p50 and p99. `SEPARATOR` is the NUL character, which never appears in a route or a status, so it can join a route and a status into one map key. The two numbers convert nanoseconds to microseconds and microseconds to milliseconds.

```java
  private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final Map<String, LongAdder[]> buckets = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> durationSumMicros = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> keyOutcomes = new ConcurrentHashMap<>();
```

Four maps: request counts, bucket arrays, the sum of durations, and key outcomes.

```java
  void countRequest(String route, int status, Duration elapsed) {
    requests.computeIfAbsent(route + SEPARATOR + status, unused -> new LongAdder()).increment();
    LongAdder[] routeBuckets = buckets.computeIfAbsent(route, unused -> newBuckets());
    long millis = elapsed.toMillis();
    for (int index = 0; index < BUCKET_BOUNDS_MILLIS.length; index++) {
      if (millis <= BUCKET_BOUNDS_MILLIS[index]) {
        routeBuckets[index].increment();
      }
    }
    // The last bucket is +Inf: every request falls in it.
    routeBuckets[BUCKET_BOUNDS_MILLIS.length].increment();
    durationSumMicros
        .computeIfAbsent(route, unused -> new LongAdder())
        .add(elapsed.toNanos() / NANOS_PER_MICRO);
  }
```

`countRequest` adds one to the counter for `route + SEPARATOR + status`. `computeIfAbsent(key, unused -> new LongAdder())` creates the counter the first time. Then each bucket whose bound is at least the elapsed milliseconds is incremented (buckets are cumulative, as Prometheus expects). The last array slot is `+Inf`: every request falls in it. Finally the duration, in microseconds, is added to the sum.

```java
  void countKeyOutcome(String endpoint, String outcome) {
    keyOutcomes
        .computeIfAbsent(endpoint + SEPARATOR + outcome, unused -> new LongAdder())
        .increment();
  }
```

`countKeyOutcome` counts what happened to an idempotency key on an endpoint: `first`, `replayed`, `key_reused`, `in_progress` or `payment_unavailable`.

```java
  /**
   * Writes every series.
   *
   * @param keysStored the number of rows in the idempotency-key table now
   * @return the Prometheus text exposition
   */
  String render(long keysStored) {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    sorted(requests)
        .forEach((labels, count) -> {
          String[] parts = labels.split(SEPARATOR);
          text.append(
              String.format(
                  Locale.ROOT,
                  "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d%n",
                  parts[0],
                  parts[1],
                  count.sum()));
        });
    text.append("# TYPE seatwave_request_duration_milliseconds histogram\n");
    new TreeMap<>(buckets)
        .forEach((route, routeBuckets) -> appendHistogram(text, route, routeBuckets));
    text.append("# TYPE seatwave_idempotency_outcomes_total counter\n");
    sorted(keyOutcomes)
        .forEach((labels, count) -> {
          String[] parts = labels.split(SEPARATOR);
          text.append(
              String.format(
                  Locale.ROOT,
                  "seatwave_idempotency_outcomes_total{endpoint=\"%s\",outcome=\"%s\"} %d%n",
                  parts[0],
                  parts[1],
                  count.sum()));
        });
    text.append("# TYPE seatwave_idempotency_keys_stored gauge\n");
    text.append(String.format(Locale.ROOT, "seatwave_idempotency_keys_stored %d%n", keysStored));
    return text.toString();
  }
```

`render` writes every series as text. A `StringBuilder` collects the lines. For each counter it writes a line such as `seatwave_requests_total{route="GET /v1/events",status="200"} 1`. `String.format(Locale.ROOT, ..., %d%n)` formats; `%n` is a line break. `sorted(requests)` makes the order stable. The `# TYPE ... counter` lines tell the reader which kind each series is. The last series, `seatwave_idempotency_keys_stored`, is a **gauge** (it can go down), with the number of rows in the key table right now.

```java
  private void appendHistogram(StringBuilder text, String route, LongAdder[] routeBuckets) {
    for (int index = 0; index < BUCKET_BOUNDS_MILLIS.length; index++) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_request_duration_milliseconds_bucket{route=\"%s\",le=\"%d\"} %d%n",
              route,
              BUCKET_BOUNDS_MILLIS[index],
              routeBuckets[index].sum()));
    }
    long count = routeBuckets[BUCKET_BOUNDS_MILLIS.length].sum();
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_bucket{route=\"%s\",le=\"+Inf\"} %d%n",
            route,
            count));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_sum{route=\"%s\"} %.3f%n",
            route,
            durationSumMicros.get(route).sum() / MICROS_PER_MILLI));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_duration_milliseconds_count{route=\"%s\"} %d%n",
            route,
            count));
  }
```

`appendHistogram` writes, for one route, one line per bucket (`le="1"`, `le="2"`, up to the last bound), then `le="+Inf"`, then the `_sum` (in milliseconds, three decimals) and the `_count`.

```java
  private static Map<String, LongAdder> sorted(Map<String, LongAdder> counters) {
    return new TreeMap<>(counters);
  }

  private static LongAdder[] newBuckets() {
    LongAdder[] fresh = new LongAdder[BUCKET_BOUNDS_MILLIS.length + 1];
    for (int index = 0; index < fresh.length; index++) {
      fresh[index] = new LongAdder();
    }
    return fresh;
  }
```

`sorted` copies a map into a `TreeMap`. `newBuckets` makes the array: one counter per bound plus one for `+Inf`.

### `http/OperationsRoutes.java`

```java
package io.plainround.seatwave.api.http;

import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.store.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
```

```java
/**
 * GET /health and GET /metrics, for the people and tools that run SeatWave.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class OperationsRoutes {

  private static final int OK = 200;

  private final Database database;
  private final IdempotencyStore keys;
  private final ApiMetrics metrics;
  private final JsonCodec json;

  OperationsRoutes(Database database, IdempotencyStore keys, ApiMetrics metrics, JsonCodec json) {
    this.database = Objects.requireNonNull(database, "database");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
  }
```

`GET /health` and `GET /metrics`, for the people and tools that run SeatWave. The constructor stores four collaborators.

```java
  Reply health(ApiRequest request) throws SQLException {
    try (Connection connection = database.connect()) {
      // "Up" means the database answers, not only that the process runs: a check that never
      // touches the database stays green while every booking fails.
      keys.count(connection);
    }
    return Reply.json(OK, json.write(new V1Views.HealthView("up")));
  }
```

`health` opens a connection and calls `keys.count(connection)`. The comment explains: "up" means the database answers, not only that the process runs. A check that never touches the database stays green while every booking fails. The reply is `{"status":"up"}`. If the database fails, the `SQLException` goes up to `ApiServer.admit`, which answers 503.

```java
  Reply metrics(ApiRequest request) throws SQLException {
    long stored;
    try (Connection connection = database.connect()) {
      stored = keys.count(connection);
    }
    return new Reply(OK, "text/plain; version=0.0.4", metrics.render(stored), Map.of());
  }
```

`metrics` counts the stored keys and returns `metrics.render(stored)` with the media type `text/plain; version=0.0.4`, the Prometheus text format.

### `http/CatalogRoutes.java`

```java
package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.catalog.CatalogProblem;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.Page;
import io.plainround.seatwave.api.catalog.Position;
import io.plainround.seatwave.api.catalog.SeatFilter;
import io.plainround.seatwave.api.catalog.SeatSummary;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
```

```java
/**
 * GET /v1/events and GET /v1/events/{eventId}/seats: every parameter is checked against the
 * contract's rules before the database is touched.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class CatalogRoutes {

  static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,17}");
  private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,200}");
  private static final Pattern SECTION = Pattern.compile("[A-T]");
  private static final Set<String> EVENT_PARAMETERS = Set.of("limit", "cursor");
  private static final Set<String> SEAT_PARAMETERS = Set.of("section", "state", "limit", "cursor");
  private static final int OK = 200;
```

The routes for `GET /v1/events` and `GET /v1/events/{eventId}/seats`. The Javadoc says every parameter is checked against the contract's rules before the database is touched. The patterns: an event id is a whole number from 1 to 18 digits with no leading zero; a cursor is 1 to 200 characters from `A-Z a-z 0-9 _ -` (the base64url alphabet); a section is one letter from A to T. `EVENT_PARAMETERS` and `SEAT_PARAMETERS` are the allow-lists of names.

```java
  private final CatalogService catalog;
  private final JsonCodec json;
  private final Problems problems;

  CatalogRoutes(CatalogService catalog, JsonCodec json, Problems problems) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.json = Objects.requireNonNull(json, "json");
    this.problems = Objects.requireNonNull(problems, "problems");
  }
```

The constructor stores the catalog service, the JSON codec and `Problems`.

```java
  Reply listEvents(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<Parameters> parameters = parameters(request, EVENT_PARAMETERS, errors);
    Optional<Integer> limit = parameters.flatMap(found -> limitOf(found, errors));
    Optional<Position> position = parameters.flatMap(found -> positionOf(found, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    Result<Page<EventSummary>, CatalogProblem> page =
        catalog.listEvents(limit.orElseThrow(), position.orElseThrow());
    return switch (page) {
      case Result.Success<Page<EventSummary>, CatalogProblem>(Page<EventSummary> events) ->
          Reply.json(OK, json.write(events.map(V1Views.EventView::of)));
      case Result.Error<Page<EventSummary>, CatalogProblem>(CatalogProblem problem) ->
          problemReply(problem, request);
    };
  }
```

`listEvents`: it makes a list for errors and parses the query. `parameters.flatMap(found -> limitOf(found, errors))` runs only if the parse succeeded, and may add an error. The same for the position (the cursor). If any error was collected, the reply is `problems.invalid(errors, ...)`. Otherwise the service is called with `limit.orElseThrow()` and `position.orElseThrow()` (they are present when there is no error). The `switch` on the `Result`: a success becomes `Reply.json(200, ...)` where `events.map(V1Views.EventView::of)` converts each item and keeps the cursor; an error goes to `problemReply`.

```java
  Reply listSeats(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    String eventIdText = request.pathValues().getFirst();
    if (!EVENT_ID.matcher(eventIdText).matches()) {
      errors.add(new FieldError("eventId", "must be a whole number of 1 or more"));
    }
    Optional<Parameters> parameters = parameters(request, SEAT_PARAMETERS, errors);
    Optional<SeatFilter> filter = parameters.flatMap(found -> filterOf(found, errors));
    Optional<Integer> limit = parameters.flatMap(found -> limitOf(found, errors));
    Optional<Position> position = parameters.flatMap(found -> positionOf(found, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    Result<Page<SeatSummary>, CatalogProblem> page =
        catalog.listSeats(
            Long.parseLong(eventIdText),
            filter.orElseThrow(),
            limit.orElseThrow(),
            position.orElseThrow());
    return switch (page) {
      case Result.Success<Page<SeatSummary>, CatalogProblem>(Page<SeatSummary> seats) ->
          Reply.json(OK, json.write(seats.map(V1Views.SeatView::of)));
      case Result.Error<Page<SeatSummary>, CatalogProblem>(CatalogProblem problem) ->
          problemReply(problem, request);
    };
  }
```

`listSeats` does the same and also checks the event id from the path (`request.pathValues().getFirst()`) and the section and state filter. The id is converted with `Long.parseLong` only after the pattern said it is safe.

```java
  private Reply problemReply(CatalogProblem problem, ApiRequest request) {
    return switch (problem) {
      case CatalogProblem.EventNotFound() ->
          problems.reply(ProblemType.NOT_FOUND, "no event has this id", request.correlationId());
      case CatalogProblem.BadCursor(String detail) ->
          problems.invalid(List.of(new FieldError("cursor", detail)), request.correlationId());
    };
  }
```

`problemReply` maps the two catalog problems: `EventNotFound()` becomes a 404 "no event has this id"; `BadCursor(detail)` becomes a 400 with a field error at the pointer `cursor`.

```java
  private static Optional<Parameters> parameters(
      ApiRequest request, Set<String> allowed, List<FieldError> errors) {
    return switch (Parameters.parse(request.rawQuery(), allowed)) {
      case Result.Success<Parameters, List<FieldError>>(Parameters found) -> Optional.of(found);
      case Result.Error<Parameters, List<FieldError>>(List<FieldError> bad) -> {
        errors.addAll(bad);
        yield Optional.empty();
      }
    };
  }
```

`parameters` wraps `Parameters.parse`. On an error, the errors are added to the caller's list and the result is empty. `yield` returns a value from a block of a `switch` expression.

```java
  private static Optional<Integer> limitOf(Parameters parameters, List<FieldError> errors) {
    return parameters.intBetween(
        "limit", 1, ContractLimits.MAX_PAGE_LIMIT, ContractLimits.DEFAULT_PAGE_LIMIT, errors);
  }
```

`limitOf` reads `limit`: 1 to `MAX_PAGE_LIMIT` (500), default `DEFAULT_PAGE_LIMIT` (100).

```java
  private static Optional<Position> positionOf(Parameters parameters, List<FieldError> errors) {
    Optional<String> cursor = parameters.get("cursor");
    if (cursor.isEmpty()) {
      return Optional.of(new Position.First());
    }
    if (!CURSOR.matcher(cursor.get()).matches()) {
      errors.add(new FieldError("cursor", "must be a nextCursor value, unchanged"));
      return Optional.empty();
    }
    return Optional.of(new Position.After(cursor.get()));
  }
```

`positionOf`: no cursor means `Position.First()`. A cursor that does not fit the base64url pattern is an error at `cursor`. Otherwise it becomes `Position.After(cursor)`; the catalog service decides whether it is really a cursor it made.

```java
  private static Optional<SeatFilter> filterOf(Parameters parameters, List<FieldError> errors) {
    String section = parameters.get("section").orElse(SeatFilter.ANY_SECTION);
    boolean isSectionValid =
        section.equals(SeatFilter.ANY_SECTION) || SECTION.matcher(section).matches();
    if (!isSectionValid) {
      errors.add(new FieldError("section", "must be one letter from A to T"));
    }
    Optional<String> stateText = parameters.get("state");
    Optional<SeatFilter.StateFilter> state =
        stateText.isEmpty()
            ? Optional.of(SeatFilter.StateFilter.ANY)
            : stateOf(stateText.get(), errors);
    if (!isSectionValid || state.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new SeatFilter(section, state.get()));
  }
```

`filterOf` reads `section` (default `*`) and `state` (default any). It records an error for a bad section, and for a bad state through `stateOf`. If either is bad it returns empty; otherwise a `SeatFilter`.

```java
  private static Optional<SeatFilter.StateFilter> stateOf(String text, List<FieldError> errors) {
    return switch (text) {
      case "available", "held", "sold" ->
          Optional.of(SeatFilter.StateFilter.valueOf(text.toUpperCase(Locale.ROOT)));
      default -> {
        errors.add(new FieldError("state", "must be available, held or sold"));
        yield Optional.empty();
      }
    };
  }
```

`stateOf` accepts only `available`, `held` or `sold`, and turns the text into the enum value with `valueOf(text.toUpperCase(Locale.ROOT))`. Anything else is the error "must be available, held or sold".

### `http/BookingRoutes.java`

```java
package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.CancelOutcome;
import io.plainround.seatwave.api.booking.ConfirmCommand;
import io.plainround.seatwave.api.booking.ConfirmOutcome;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldCommand;
import io.plainround.seatwave.api.booking.HoldId;
import io.plainround.seatwave.api.booking.HoldOutcome;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.booking.HoldState;
import io.plainround.seatwave.api.booking.KeyedReply;
import io.plainround.seatwave.api.booking.Refusal;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
```

```java
/**
 * The booking operations of version 1. The two writes that create something, hold and confirm,
 * require an Idempotency-Key; cancel is idempotent by its nature and the reads change nothing.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class BookingRoutes {

  static final String HOLD_ENDPOINT = "POST /v1/holds";
  static final String CONFIRM_ENDPOINT = "POST /v1/bookings";
  // application/json, with at most a UTF-8 charset parameter (RFC 8259 makes UTF-8 the encoding).
  private static final Pattern JSON_MEDIA_TYPE =
      Pattern.compile("application/json\\s*(;\\s*charset=\"?utf-8\"?)?", Pattern.CASE_INSENSITIVE);
  private static final int OK = 200;
  private static final int CREATED = 201;

```

The booking operations of version 1. The two writes that create something, hold and confirm, require an `Idempotency-Key`; cancel is idempotent by nature and the reads change nothing. `HOLD_ENDPOINT` and `CONFIRM_ENDPOINT` are the names under which a key is stored. `JSON_MEDIA_TYPE` accepts `application/json` with, at most, a UTF-8 charset parameter, in any letter case; RFC 8259 makes UTF-8 the encoding.

```java
  private final HoldService holds;
  private final ConfirmService confirms;
  private final JsonCodec json;
  private final Problems problems;
  private final ApiMetrics metrics;
  private final Duration retryAfter;

  BookingRoutes(
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json,
      Problems problems,
      ApiMetrics metrics,
      Duration retryAfter) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.confirms = Objects.requireNonNull(confirms, "confirms");
    this.json = Objects.requireNonNull(json, "json");
    this.problems = Objects.requireNonNull(problems, "problems");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }
```

Six collaborators, each checked for `null` in the constructor.

```java
  Reply hold(ApiRequest request) throws SQLException {
    Optional<Reply> unreadable = refuseUnreadableBody(request);
    if (unreadable.isPresent()) {
      return unreadable.get();
    }
    List<FieldError> errors = new ArrayList<>();
    Optional<IdempotencyKey> key = keyOf(request, errors);
    Optional<HoldCommand> command =
        bind(request, V1Views.HoldRequestBody.class, errors)
            .flatMap(body -> holdCommandOf(body, errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    HoldCommand valid = command.orElseThrow();
    // The fingerprint is taken from the validated request written back out, so spacing and field
    // order do not count as "a different body".
    String canonical =
        json.write(
            new V1Views.HoldRequestBody(valid.eventId(), valid.seatLabel(), valid.customer()));
    KeyClaim claim =
        new KeyClaim(HOLD_ENDPOINT, key.orElseThrow(), Fingerprint.of(HOLD_ENDPOINT, canonical));
    return keyedReply(
        holds.hold(valid, claim, outcome -> renderHold(outcome, request)), HOLD_ENDPOINT, request);
  }
```

`hold`, the heart of the write path. First `refuseUnreadableBody` (413 or 415). Then a fresh error list, the key (`keyOf`) and the body bound to `HoldRequestBody` and checked by `holdCommandOf`. Any error gives one 400 listing them all. Then `canonical`: the validated request is written back out as JSON, and the fingerprint is taken from that text, not from the client's bytes. The comment says why: spacing and field order do not count as "a different body". A `KeyClaim` bundles the endpoint, the key and the fingerprint. Last, `holds.hold(valid, claim, outcome -> renderHold(outcome, request))` runs the service, which receives a function that turns its outcome into the reply to store; `keyedReply` turns the service's result into a `Reply`.

```java
  Reply confirm(ApiRequest request) throws SQLException {
    Optional<Reply> unreadable = refuseUnreadableBody(request);
    if (unreadable.isPresent()) {
      return unreadable.get();
    }
    List<FieldError> errors = new ArrayList<>();
    Optional<IdempotencyKey> key = keyOf(request, errors);
    Optional<HoldId> holdId =
        bind(request, V1Views.BookingRequestBody.class, errors)
            .flatMap(body -> holdIdOf(body.holdId(), "/holdId", errors));
    if (!errors.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    String canonical = json.write(new V1Views.BookingRequestBody(holdId.orElseThrow().value()));
    KeyClaim claim =
        new KeyClaim(
            CONFIRM_ENDPOINT, key.orElseThrow(), Fingerprint.of(CONFIRM_ENDPOINT, canonical));
    return keyedReply(
        confirms.confirm(
            new ConfirmCommand(holdId.get()), claim, outcome -> renderConfirm(outcome, request)),
        CONFIRM_ENDPOINT,
        request);
  }
```

`confirm` is the same shape for `POST /v1/bookings`: the body holds only `holdId`, validated by `holdIdOf`.

```java
  Reply getHold(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<HoldId> holdId = holdIdOf(request.pathValues().getFirst(), "holdId", errors);
    if (holdId.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    return holds
        .find(holdId.get())
        .map(hold -> Reply.json(OK, json.write(V1Views.HoldView.of(hold))))
        .orElseGet(() ->
            problems.reply(
                ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId()));
  }
```

`getHold` checks the id in the path, then `holds.find(...)`; an empty result gives 404 "no seat carries this hold". `Optional.map(...).orElseGet(() -> ...)` returns the mapped reply if present and computes the 404 only when absent.

```java
  Reply cancel(ApiRequest request) throws SQLException {
    List<FieldError> errors = new ArrayList<>();
    Optional<HoldId> holdId = holdIdOf(request.pathValues().getFirst(), "holdId", errors);
    if (holdId.isEmpty()) {
      return problems.invalid(errors, request.correlationId());
    }
    return switch (holds.cancel(holdId.get())) {
      case CancelOutcome.Released() -> Reply.noContent();
      case CancelOutcome.HoldNotFound() ->
          problems.reply(
              ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId());
      case CancelOutcome.TooLate(HoldState state) ->
          problems.reply(
              ProblemType.HOLD_NOT_CANCELLABLE,
              "the hold is " + state.name().toLowerCase(Locale.ROOT),
              request.correlationId());
    };
  }
```

`cancel` maps the three outcomes of the service: `Released()` is a 204 with no body; `HoldNotFound()` is a 404; `TooLate(state)` is a 409 `hold-not-cancellable` that names the state ("the hold is confirming").

```java
  Reply getBooking(ApiRequest request) throws SQLException {
    String bookingId = request.pathValues().getFirst();
    if (!HoldId.UUID_TEXT.matcher(bookingId).matches()) {
      return problems.invalid(
          List.of(new FieldError("bookingId", "must be a lowercase UUID")),
          request.correlationId());
    }
    return confirms
        .find(bookingId)
        .map(booking -> Reply.json(OK, json.write(V1Views.BookingView.of(booking))))
        .orElseGet(() ->
            problems.reply(
                ProblemType.NOT_FOUND, "no booking has this id", request.correlationId()));
  }
```

`getBooking` checks the id is a lowercase UUID, then looks the booking up (404 if none).

```java
  private StoredReply renderHold(HoldOutcome outcome, ApiRequest request) {
    return switch (outcome) {
      case HoldOutcome.Held(var hold) ->
          new StoredReply(
              CREATED, "/v1/holds/" + hold.holdId().value(), json.write(V1Views.HoldView.of(hold)));
      case HoldOutcome.SeatTaken() ->
          stored(
              problems.reply(
                  ProblemType.SEAT_UNAVAILABLE,
                  "another fan holds or bought this seat",
                  request.correlationId()));
      case HoldOutcome.SeatNotFound() ->
          stored(
              problems.reply(
                  ProblemType.NOT_FOUND, "no such event or seat", request.correlationId()));
    };
  }
```

`renderHold` turns a `HoldOutcome` into a `StoredReply`, the reply that will be kept under the key and replayed. `Held` is a 201 with the `Location` `/v1/holds/<id>` and the hold's JSON. `SeatTaken` is a stored 409 problem and `SeatNotFound` a stored 404. `var hold` lets the compiler infer the type of the record component. These are final outcomes, stored so that a retry gets the same answer.

```java
  private StoredReply renderConfirm(ConfirmOutcome outcome, ApiRequest request) {
    return switch (outcome) {
      case ConfirmOutcome.Booked(var booking) ->
          new StoredReply(
              CREATED,
              "/v1/bookings/" + booking.bookingId(),
              json.write(V1Views.BookingView.of(booking)));
      case ConfirmOutcome.HoldNotFound() ->
          stored(
              problems.reply(
                  ProblemType.NOT_FOUND, "no seat carries this hold", request.correlationId()));
      case ConfirmOutcome.HoldNotActive(HoldState state) ->
          stored(
              problems.reply(
                  ProblemType.HOLD_NOT_ACTIVE,
                  "the hold is " + state.name().toLowerCase(Locale.ROOT),
                  request.correlationId()));
    };
  }
```

`renderConfirm` does the same for confirm: `Booked` is a 201 with `Location` `/v1/bookings/<id>`; `HoldNotFound` and `HoldNotActive` are stored problems.

```java
  private Reply keyedReply(
      Result<KeyedReply, Refusal> result, String endpoint, ApiRequest request) {
    return switch (result) {
      case Result.Success<KeyedReply, Refusal>(KeyedReply keyed) -> {
        metrics.countKeyOutcome(
            endpoint, keyed.source() == KeyedReply.Source.REPLAY ? "replayed" : "first");
        yield replyOf(keyed.reply());
      }
      case Result.Error<KeyedReply, Refusal>(Refusal refusal) ->
          refusalReply(refusal, endpoint, request);
    };
  }
```

`keyedReply` handles the service's `Result`. On success it counts the outcome (`replayed` if the reply came from the key store, `first` if this request did the work) and returns the reply. On a `Refusal` it goes to `refusalReply`.

```java
  private Reply refusalReply(Refusal refusal, String endpoint, ApiRequest request) {
    return switch (refusal) {
      case Refusal.KeyReusedWithOtherBody() -> {
        metrics.countKeyOutcome(endpoint, "key_reused");
        yield problems.reply(
            ProblemType.KEY_REUSED, "use a new key for a new request", request.correlationId());
      }
      case Refusal.ConfirmInProgress() -> {
        metrics.countKeyOutcome(endpoint, "in_progress");
        yield problems.reply(
            ProblemType.CONFIRM_IN_PROGRESS,
            "retry with the same key in a moment",
            request.correlationId());
      }
      case Refusal.PaymentUnavailable() -> {
        metrics.countKeyOutcome(endpoint, "payment_unavailable");
        yield problems
            .reply(
                ProblemType.PAYMENT_UNAVAILABLE,
                "nothing was booked; the same key will not charge twice",
                request.correlationId())
            .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
      }
    };
  }
```

`refusalReply` maps the three refusals, which are never stored: `KeyReusedWithOtherBody` is the 422; `ConfirmInProgress` is a 409 that asks for a retry with the same key; `PaymentUnavailable` is a 503 with `Retry-After`, and says that nothing was booked and the same key will not charge twice. Each counts its outcome first.

```java
  private static Reply replyOf(StoredReply stored) {
    // Stored errors are problem details; stored successes are plain JSON.
    String contentType =
        stored.status() >= ProblemType.INVALID_REQUEST.status() ? Reply.PROBLEM_JSON : Reply.JSON;
    Reply reply = new Reply(stored.status(), contentType, stored.body(), Map.of());
    return stored.location().isEmpty() ? reply : reply.withHeader("Location", stored.location());
  }

  private static StoredReply stored(Reply reply) {
    return new StoredReply(reply.status(), "", reply.body());
  }
```

`replyOf` turns a stored reply into a `Reply`: a status of 400 or more gets `application/problem+json`, anything else `application/json`; a `Location` header is added if the stored reply has one. `stored` wraps a problem `Reply` as a `StoredReply` with no location.

```java
  private Optional<Reply> refuseUnreadableBody(ApiRequest request) {
    if (request.bodyTooLarge()) {
      return Optional.of(
          problems.reply(
              ProblemType.BODY_TOO_LARGE,
              "the limit is " + ContractLimits.MAX_BODY_BYTES + " bytes",
              request.correlationId()));
    }
    boolean isJson =
        request
            .header("Content-Type")
            .map(type -> JSON_MEDIA_TYPE.matcher(type.strip()).matches())
            .orElse(false);
    if (!isJson) {
      return Optional.of(
          problems.reply(
              ProblemType.UNSUPPORTED_MEDIA_TYPE,
              "send Content-Type: application/json",
              request.correlationId()));
    }
    return Optional.empty();
  }
```

`refuseUnreadableBody`. Too large is a 413 with the limit in the detail. Then the content type: `request.header("Content-Type").map(...).orElse(false)` is true only if the header exists and matches `JSON_MEDIA_TYPE`. If not, 415. An empty `Optional` means the body is acceptable.

```java
  private <T> Optional<T> bind(ApiRequest request, Class<T> type, List<FieldError> errors) {
    return switch (json.read(request.body(), type)) {
      case Result.Success<T, JsonCodec.BindingProblem>(T body) -> Optional.of(body);
      case Result.Error<T, JsonCodec.BindingProblem>(JsonCodec.BindingProblem problem) -> {
        errors.add(new FieldError(problem.pointer(), problem.detail()));
        yield Optional.empty();
      }
    };
  }
```

`bind` calls `json.read` and returns the record or, on a `BindingProblem`, adds a `FieldError` with its pointer and detail and returns empty.

```java
  private static Optional<IdempotencyKey> keyOf(ApiRequest request, List<FieldError> errors) {
    Optional<IdempotencyKey> key =
        request.header("Idempotency-Key").flatMap(IdempotencyKey::fromHeader);
    if (key.isEmpty()) {
      errors.add(
          new FieldError(
              "Idempotency-Key",
              "is required: a quoted string of 1 to 64 of A-Z a-z 0-9 _ -, new for each action"));
    }
    return key;
  }
```

`keyOf` reads the `Idempotency-Key` header and parses it with `IdempotencyKey::fromHeader` (the key must be a quoted string of 1 to 64 characters from `A-Z a-z 0-9 _ -`). A missing or malformed key adds the error "is required: a quoted string of 1 to 64 of A-Z a-z 0-9 _ -, new for each action".

```java
  private static Optional<HoldCommand> holdCommandOf(
      V1Views.HoldRequestBody body, List<FieldError> errors) {
    int errorsBefore = errors.size();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add(new FieldError("/eventId", "is required: a whole number of 1 or more"));
    }
    if (body.seat() == null || !HoldCommand.SEAT_LABEL.matcher(body.seat()).matches()) {
      errors.add(new FieldError("/seat", "is required: a section A to T, a hyphen, a number"));
    }
    if (body.customer() == null || !HoldCommand.CUSTOMER.matcher(body.customer()).matches()) {
      errors.add(new FieldError("/customer", "is required: 1 to 64 of A-Z a-z 0-9 _ -"));
    }
    if (errors.size() > errorsBefore) {
      return Optional.empty();
    }
    return Optional.of(new HoldCommand(body.eventId(), body.seat(), body.customer()));
  }
```

`holdCommandOf` checks the three fields of a hold: `eventId` is present and at least 1; `seat` matches the seat-label pattern (a section letter A to T, a hyphen, a number); `customer` matches 1 to 64 safe characters. Each failure adds an error at its JSON pointer. `errorsBefore` remembers how many errors there were on entry, so the method can tell whether it added any.

```java
  private static Optional<HoldId> holdIdOf(String text, String pointer, List<FieldError> errors) {
    if (text == null || !HoldId.UUID_TEXT.matcher(text).matches()) {
      errors.add(new FieldError(pointer, "must be a lowercase UUID"));
      return Optional.empty();
    }
    return Optional.of(new HoldId(text));
  }
```

`holdIdOf` checks a hold id is a lowercase UUID and wraps it in `HoldId`.

### `http/Version0Routes.java`

```java
package io.plainround.seatwave.api.http;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.ConfirmCommand;
import io.plainround.seatwave.api.booking.ConfirmOutcome;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldCommand;
import io.plainround.seatwave.api.booking.HoldId;
import io.plainround.seatwave.api.booking.HoldOutcome;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.booking.KeyedReply;
import io.plainround.seatwave.api.booking.Refusal;
import io.plainround.seatwave.api.catalog.CatalogProblem;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.catalog.EventSummary;
import io.plainround.seatwave.api.catalog.Page;
import io.plainround.seatwave.api.catalog.Position;
import io.plainround.seatwave.api.catalog.SeatFilter;
import io.plainround.seatwave.api.catalog.SeatSummary;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.idempotency.Fingerprint;
import io.plainround.seatwave.api.idempotency.IdempotencyKey;
import io.plainround.seatwave.api.idempotency.KeyClaim;
import io.plainround.seatwave.api.idempotency.StoredReply;
import io.plainround.seatwave.api.json.JsonCodec;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
```

```java
/**
 * Module 1's four paths, answering exactly as they did, so a client built for version 0 keeps
 * working until the sunset date. Every reply says so: a Deprecation header (RFC 9745), a Sunset
 * header (RFC 8594) and a Link to the version 1 replacement (RFC 8288).
 *
 * <p>Two things changed underneath, and only these: a confirm now takes the payment (Module 2's
 * requirement), and each write gets a one-time key, which keeps the old retry behavior (a retry is
 * a new request) while the payment provider's key, made from the hold, still stops a second charge.
 *
 * <p>Thread-safe: it holds only thread-safe collaborators.
 */
final class Version0Routes {
```

Module 1's four paths, answering exactly as before, so a client built for version 0 keeps working until the sunset date. Every reply says so with three headers: `Deprecation` (RFC 9745), `Sunset` (RFC 8594) and `Link` to the successor (RFC 8288). The Javadoc names the two things that changed underneath: a confirm now takes the payment, and each write gets a one-time key (so a retry stays a new request, as in Module 1, while the provider's key, made from the hold, still stops a second charge).

```java
  // Module 1's patterns, kept as they were: version 0 accepts what it accepted before.
  private static final Pattern SEAT_LABEL = Pattern.compile("[A-T]-[0-9]{1,4}");
  private static final Pattern SECTION = Pattern.compile("[A-T]");
  // Module 1 seeded 1,000 seats per section and returned a whole section in one reply.
  private static final int SECTION_SIZE = 1_000;
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNAVAILABLE = 503;
```

Module 1's patterns and statuses, kept: seat labels `[A-T]-[0-9]{1,4}`, a section letter, and 1,000 seats per section (Module 1 returned a whole section in one reply).

```java
  private final CatalogService catalog;
  private final HoldService holds;
  private final ConfirmService confirms;
  private final JsonCodec json;
  private final String deprecation;
  private final String sunset;

  Version0Routes(
      CatalogService catalog,
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json,
      ApiSettings.Version0 dates) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.holds = Objects.requireNonNull(holds, "holds");
    this.confirms = Objects.requireNonNull(confirms, "confirms");
    this.json = Objects.requireNonNull(json, "json");
    // RFC 9745: a Structured Field Date, "@" and seconds since the epoch.
    this.deprecation = "@" + dates.deprecatedAt().getEpochSecond();
    // RFC 8594: an HTTP date, as RFC 9110 defines it (RFC 1123 format, always GMT).
    this.sunset =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(dates.sunsetAt().atOffset(ZoneOffset.UTC));
  }
```

The constructor computes the two header values once. `deprecation` is `"@" + seconds since the epoch` (RFC 9745 uses a structured-field date: `@` and a number). `sunset` is the date in RFC 1123 form, always GMT (`Tue, 11 May 2027 00:00:00 GMT`), made with `DateTimeFormatter.RFC_1123_DATE_TIME`.

```java
  Reply listEvents(ApiRequest request) throws SQLException {
    List<Version0Views.EventVersion0> events = new ArrayList<>();
    Position position = new Position.First();
    // Version 0 is unpaginated; it walks the version 1 pages and joins them, as Module 1 did.
    while (true) {
      Result<Page<EventSummary>, CatalogProblem> page =
          catalog.listEvents(ContractLimits.MAX_PAGE_LIMIT, position);
      if (!(page instanceof Result.Success<Page<EventSummary>, CatalogProblem>(var found))) {
        throw new IllegalStateException("a cursor this server made was refused");
      }
      found
          .items()
          .forEach(event ->
              events.add(
                  new Version0Views.EventVersion0(
                      event.eventId(), event.name(), event.startsAt().toEpochMilli())));
      if (!(found instanceof Page.More<EventSummary> more)) {
        return deprecated(Reply.json(OK, json.write(events)), "/v1/events");
      }
      position = new Position.After(more.nextCursor());
    }
  }
```

`listEvents`: version 0 is unpaginated, so it walks the version 1 pages and joins them. `while (true)` loops. `page instanceof Result.Success<...>(var found)` tests and unpacks in one step. A refused cursor here would be a bug (the server made it), so it throws `IllegalStateException`. Each event is converted with its start in epoch milliseconds. If the page is not a `More`, the list is complete and the reply is sent; otherwise the loop continues from the page's cursor.

```java
  Reply listSeats(ApiRequest request) throws SQLException {
    String successor = "/v1/events/" + request.pathValues().getFirst() + "/seats";
    if (!CatalogRoutes.EVENT_ID.matcher(request.pathValues().getFirst()).matches()) {
      return error(BAD_REQUEST, "invalid eventId", successor);
    }
    Result<Parameters, List<FieldError>> parameters =
        Parameters.parse(request.rawQuery(), Set.of("section"));
    String section =
        parameters instanceof Result.Success<Parameters, List<FieldError>>(Parameters found)
            ? found.get("section").orElse("")
            : "";
    if (!SECTION.matcher(section).matches()) {
      return error(BAD_REQUEST, "invalid section", successor);
    }
    Result<Page<SeatSummary>, CatalogProblem> page =
        catalog.listSeats(
            Long.parseLong(request.pathValues().getFirst()),
            new SeatFilter(section, SeatFilter.StateFilter.ANY),
            SECTION_SIZE,
            new Position.First());
    // Module 1 answered an unknown event with an empty list, so version 0 still does.
    List<Version0Views.SeatVersion0> seats =
        page instanceof Result.Success<Page<SeatSummary>, CatalogProblem>(var found)
            ? found.items().stream()
                .map(seat ->
                    new Version0Views.SeatVersion0(
                        seat.seatLabel(), seat.state().name().toLowerCase(Locale.ROOT)))
                .toList()
            : List.of();
    return deprecated(Reply.json(OK, json.write(seats)), successor);
  }
```

`listSeats` checks the event id and the section, using error text of Module 1 (`invalid eventId`, `invalid section`). It asks for up to 1,000 seats of the section. An unknown event gives an empty list, as in Module 1.

```java
  Reply hold(ApiRequest request) throws SQLException {
    if (request.bodyTooLarge()) {
      return error(TOO_LARGE, "request body too large", "/v1/holds");
    }
    Result<Parameters, List<FieldError>> form =
        Parameters.parse(request.body(), Set.of("eventId", "seat", "customer"));
    if (!(form instanceof Result.Success<Parameters, List<FieldError>>(Parameters fields))) {
      return error(BAD_REQUEST, "malformed form field", "/v1/holds");
    }
    String eventId = fields.get("eventId").orElse("");
    String seat = fields.get("seat").orElse("");
    String customer = fields.get("customer").orElse("");
    if (!CatalogRoutes.EVENT_ID.matcher(eventId).matches()) {
      return error(BAD_REQUEST, "invalid eventId", "/v1/holds");
    }
    if (!SEAT_LABEL.matcher(seat).matches()) {
      return error(BAD_REQUEST, "invalid seat", "/v1/holds");
    }
    if (!HoldCommand.CUSTOMER.matcher(customer).matches()) {
      return error(BAD_REQUEST, "invalid customer", "/v1/holds");
    }
    // Module 1 accepted labels such as A-0 and then found no such seat: still a 409 here.
    if (!HoldCommand.SEAT_LABEL.matcher(seat).matches()) {
      return error(CONFLICT, "seat not available", "/v1/holds");
    }
    HoldCommand command = new HoldCommand(Long.parseLong(eventId), seat, customer);
    Result<KeyedReply, Refusal> held =
        holds.hold(command, oneTimeClaim("POST /holds"), this::renderHold);
    return replyOf(held, "/v1/holds");
  }
```

`hold` reads a **form body** (`eventId=1&seat=A-9&customer=fan_9`) with `Parameters.parse` against its three names, checks each field with Module 1's rules and messages, and keeps Module 1's quirk: a label such as `A-0` is accepted by the old pattern but is not a real seat, and the answer is a 409. Then the service runs with `oneTimeClaim`.

```java
  Reply confirm(ApiRequest request) throws SQLException {
    if (request.bodyTooLarge()) {
      return error(TOO_LARGE, "request body too large", "/v1/bookings");
    }
    Result<Parameters, List<FieldError>> form = Parameters.parse(request.body(), Set.of("holdId"));
    String holdId =
        form instanceof Result.Success<Parameters, List<FieldError>>(Parameters fields)
            ? fields.get("holdId").orElse("")
            : "";
    if (!HoldId.UUID_TEXT.matcher(holdId).matches()) {
      return error(BAD_REQUEST, "invalid holdId", "/v1/bookings");
    }
    Result<KeyedReply, Refusal> confirmed =
        confirms.confirm(
            new ConfirmCommand(new HoldId(holdId)),
            oneTimeClaim("POST /bookings"),
            this::renderConfirm);
    return replyOf(confirmed, "/v1/bookings");
  }
```

`confirm` does the same for a form with `holdId`.

```java
  private StoredReply renderHold(HoldOutcome outcome) {
    return switch (outcome) {
      case HoldOutcome.Held(var hold) ->
          new StoredReply(
              CREATED,
              "",
              json.write(
                  new Version0Views.HoldVersion0(
                      hold.holdId().value(), ContractLimits.HOLD_DURATION.toSeconds())));
      case HoldOutcome.SeatTaken(), HoldOutcome.SeatNotFound() ->
          new StoredReply(
              CONFLICT, "", json.write(new Version0Views.ErrorVersion0("seat not available")));
    };
  }

  private StoredReply renderConfirm(ConfirmOutcome outcome) {
    return switch (outcome) {
      case ConfirmOutcome.Booked(var booking) ->
          new StoredReply(
              CREATED, "", json.write(new Version0Views.BookingVersion0(booking.bookingId())));
      case ConfirmOutcome.HoldNotFound(), ConfirmOutcome.HoldNotActive(_) ->
          new StoredReply(
              CONFLICT, "", json.write(new Version0Views.ErrorVersion0("hold missing or expired")));
    };
  }
```

`renderHold` and `renderConfirm` turn outcomes into Module 1's reply bodies: a 201 with `holdId` and `expiresInSeconds`, a 201 with `bookingId`, and the plain `{"error":"..."}` 409 for the failures. In `renderConfirm`, `HoldNotActive(_)` uses the **unnamed pattern** `_` (Java 22), which says "there is a value here and I do not need it".

```java
  private Reply replyOf(Result<KeyedReply, Refusal> result, String successor) {
    return switch (result) {
      case Result.Success<KeyedReply, Refusal>(KeyedReply keyed) ->
          deprecated(Reply.json(keyed.reply().status(), keyed.reply().body()), successor);
      case Result.Error<KeyedReply, Refusal>(Refusal.PaymentUnavailable()) ->
          error(UNAVAILABLE, "payment unavailable", successor);
      // A one-time key is never reused, so only a concurrent confirm of the same hold is left.
      case Result.Error<KeyedReply, Refusal>(Refusal _) ->
          error(CONFLICT, "hold missing or expired", successor);
    };
  }
```

`replyOf` marks the reply deprecated. `PaymentUnavailable` becomes a 503 "payment unavailable". Any other refusal can only be a concurrent confirm of the same hold (a one-time key is never reused), so it is a 409.

```java
  private static KeyClaim oneTimeClaim(String endpoint) {
    // A fresh random key per request: version 0 never had retry safety, and still has none.
    IdempotencyKey key = new IdempotencyKey("v0-" + UUID.randomUUID());
    return new KeyClaim(endpoint, key, Fingerprint.of(endpoint, key.value()));
  }
```

`oneTimeClaim` makes a fresh random key (`"v0-" + UUID.randomUUID()`) for each request. Version 0 never had retry safety, and still has none: a retry is a new request.

```java
  private Reply error(int status, String message, String successor) {
    return deprecated(
        Reply.json(status, json.write(new Version0Views.ErrorVersion0(message))), successor);
  }

  private Reply deprecated(Reply reply, String successor) {
    return reply
        .withHeader("Deprecation", deprecation)
        .withHeader("Sunset", sunset)
        .withHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
  }
```

`error` builds a version 0 error reply, and `deprecated` adds the three headers: `Deprecation`, `Sunset` and `Link: </v1/events>; rel="successor-version"`.

### `http/ApiServer.java`

```java
package io.plainround.seatwave.api.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.booking.ConfirmService;
import io.plainround.seatwave.api.booking.HoldService;
import io.plainround.seatwave.api.catalog.CatalogService;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.idempotency.IdempotencyStore;
import io.plainround.seatwave.api.json.JsonCodec;
import io.plainround.seatwave.api.store.Database;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
```

Imports. The two from `com.sun.net.httpserver` are the JDK's server: `HttpServer` (listens) and `HttpExchange` (one request and its reply). `Semaphore` limits requests in flight. `MDC` is the logging context that carries the correlation id.

```java
/**
 * The HTTP server: the JDK's own {@code com.sun.net.httpserver} (an exported, supported JDK API),
 * on 127.0.0.1 only, one virtual thread per request, and a hard limit on requests in flight.
 *
 * <p>This class is the boundary. It turns bytes into an {@link ApiRequest}, picks the route, and
 * maps every failure to a response once: an overload to 503 service-busy, a database failure to 503
 * storage-unavailable, a bug to 500, all as problem details.
 *
 * <p>Thread-safe: the route table is immutable, the limit is a semaphore, and the metrics are
 * concurrent counters.
 */
public final class ApiServer implements AutoCloseable {
```

The Javadoc: the JDK's own `com.sun.net.httpserver` (an exported, supported JDK API), on 127.0.0.1 only, one virtual thread per request, and a hard limit on requests in flight. "This class is the boundary": it maps every failure to a response once. It is thread-safe: the route table is immutable, the limit is a semaphore, and the metrics are concurrent counters.

```java
  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int SYSTEM_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;
  // sendResponseHeaders takes -1 for "no body" (204), as the JDK's HttpExchange documents.
  private static final long NO_BODY = -1;
  private static final int NO_CONTENT = 204;
```

`SYSTEM_BACKLOG = 0` lets the system choose the length of the queue of waiting connections. `STOP_DELAY_SECONDS = 0` stops the server at once. `NO_BODY = -1` is the value `sendResponseHeaders` takes for "no body", as the JDK documents; 204 is "No Content".

```java
  private final HttpServer server;
  private final ExecutorService workers;
  private final Semaphore inFlight;
  private final List<Route> routes;
  private final Problems problems;
  private final ApiMetrics metrics;
  private final Duration retryAfter;

  private ApiServer(
      HttpServer server,
      ExecutorService workers,
      ApiSettings settings,
      List<Route> routes,
      Problems problems,
      ApiMetrics metrics) {
    this.server = server;
    this.workers = workers;
    this.inFlight = new Semaphore(settings.maxInFlight());
    this.routes = List.copyOf(routes);
    this.problems = problems;
    this.metrics = metrics;
    this.retryAfter = settings.retryAfter();
  }
```

The fields and the private constructor. `new Semaphore(settings.maxInFlight())` creates the permits. `List.copyOf(routes)` makes the route table unchangeable.

```java
  /**
   * The services the routes call.
   *
   * @param database the database
   * @param keys the idempotency-key store
   * @param catalog events and seats
   * @param holds holds
   * @param confirms confirms
   * @param json the JSON codec
   */
  public record Services(
      Database database,
      IdempotencyStore keys,
      CatalogService catalog,
      HoldService holds,
      ConfirmService confirms,
      JsonCodec json) {

    /** Checks the fields. */
    public Services {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(keys, "keys");
      Objects.requireNonNull(catalog, "catalog");
      Objects.requireNonNull(holds, "holds");
      Objects.requireNonNull(confirms, "confirms");
      Objects.requireNonNull(json, "json");
    }
  }
```

`Services` bundles what the routes call, and refuses a `null` in any slot.

```java
  /**
   * Starts the server on 127.0.0.1.
   *
   * @param settings the port, the in-flight limit, the Retry-After delay and version 0's dates
   * @param services what the routes call
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static ApiServer start(ApiSettings settings, Services services) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(services, "services");
    Problems problems = new Problems(services.json());
    ApiMetrics metrics = new ApiMetrics();
    List<Route> routes = routesFor(settings, services, problems, metrics);
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.httpPort()),
            SYSTEM_BACKLOG);
    // Virtual threads make a request waiting on the database or the payment provider cheap; the
    // semaphore, not the thread count, is what bounds the work (Module 4).
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    ApiServer api = new ApiServer(server, workers, settings, routes, problems, metrics);
    server.setExecutor(workers);
    server.createContext("/", api::serve);
    server.start();
    return api;
  }
```

`start` builds the problems helper, the metrics and the routes. `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.httpPort()), SYSTEM_BACKLOG)` creates the server **bound to the loopback address** (`127.0.0.1`): only programs on this machine can reach it. `Executors.newVirtualThreadPerTaskExecutor()` is the executor: each task, here each request, gets its own virtual thread. The comment gives the reason: the semaphore, not the thread count, bounds the work. `server.setExecutor(workers)` gives it to the server, `server.createContext("/", api::serve)` registers `serve` for every path, and `server.start()` begins accepting connections on a thread of its own.

```java
  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    workers.close();
  }
```

`port()` asks the server for its real address. `close()` stops the server at once (`server.stop(0)`) and closes the executor, which waits for running requests to finish.

```java
  private static List<Route> routesFor(
      ApiSettings settings, Services services, Problems problems, ApiMetrics metrics) {
    CatalogRoutes catalog = new CatalogRoutes(services.catalog(), services.json(), problems);
    BookingRoutes booking =
        new BookingRoutes(
            services.holds(),
            services.confirms(),
            services.json(),
            problems,
            metrics,
            settings.retryAfter());
    Version0Routes version0 =
        new Version0Routes(
            services.catalog(),
            services.holds(),
            services.confirms(),
            services.json(),
            settings.version0());
    OperationsRoutes operations =
        new OperationsRoutes(services.database(), services.keys(), metrics, services.json());
    return List.of(
        Route.of("GET", "/v1/events", catalog::listEvents),
        Route.of("GET", "/v1/events/{eventId}/seats", catalog::listSeats),
        Route.of("POST", "/v1/holds", booking::hold),
        Route.of("GET", "/v1/holds/{holdId}", booking::getHold),
        Route.of("DELETE", "/v1/holds/{holdId}", booking::cancel),
        Route.of("POST", "/v1/bookings", booking::confirm),
        Route.of("GET", "/v1/bookings/{bookingId}", booking::getBooking),
        Route.of("GET", "/health", operations::health),
        Route.of("GET", "/metrics", operations::metrics),
        Route.of("GET", "/events", version0::listEvents),
        Route.of("GET", "/events/{eventId}/seats", version0::listSeats),
        Route.of("POST", "/holds", version0::hold),
        Route.of("POST", "/bookings", version0::confirm));
  }
```

`routesFor` creates the four route classes and lists all thirteen routes. `Route.of("GET", "/v1/events", catalog::listEvents)` pairs a method and a path template with a **method reference** to the handler. Version 1 has seven routes, then `/health` and `/metrics`, then the four of version 0.

```java
  private void serve(HttpExchange exchange) {
    long startNanos = System.nanoTime();
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    String routeLabel = "unmatched";
    int status = ProblemType.INTERNAL_ERROR.status();
    try (exchange) {
      ApiRequest request = read(exchange, correlationId);
      Optional<Route> route = match(request);
      routeLabel = route.map(Route::label).orElse(routeLabel);
      Reply reply = admit(request, route);
      status = reply.status();
      send(exchange, reply);
    } catch (IOException clientGone) {
      // The client closed the connection, before or during the reply: the lost-reply case. Any
      // write already committed stays, and the client's retry with the same key gets it back.
      LOG.warn("the reply could not be sent: {}", clientGone.getMessage());
    } finally {
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      metrics.countRequest(routeLabel, status, elapsed);
      LOG.info("{} {} {} ms", routeLabel, status, elapsed.toMillis());
      MDC.remove("correlationId");
    }
  }
```

`serve` is the handler for every request. It records the start time in nanoseconds, makes the correlation id, and puts it in the `MDC` so the log pattern can print it. `routeLabel` starts as `unmatched` and `status` as 500, so that even a failure is counted. `try (exchange)` closes the exchange at the end (the exchange is `AutoCloseable`). Inside: read, match, admit, send. If sending fails with an `IOException`, the client has gone; the comment says what that means: any write already committed stays, and the client's retry with the same key gets it back. The `finally` block runs always: it counts the request with its route label, status and elapsed time, writes one log line `route status ms`, and removes the id from the `MDC`.

```java
  private Reply admit(ApiRequest request, Optional<Route> route) {
    if (route.isEmpty()) {
      return refuseUnrouted(request);
    }
    // Refuse at once over the limit: a fast 503 keeps the work already admitted fast (Module 4).
    if (!inFlight.tryAcquire()) {
      return problems
          .reply(ProblemType.SERVICE_BUSY, "too many requests in flight", request.correlationId())
          .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
    }
    try {
      return route.get().handler().handle(request.withPathValues(route.get().valuesIn(request)));
    } catch (SQLException databaseFailure) {
      LOG.error("database failure, SQL state {}", databaseFailure.getSQLState(), databaseFailure);
      return problems
          .reply(
              ProblemType.STORAGE_UNAVAILABLE,
              "nothing was changed; retry with the same key",
              request.correlationId())
          .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
    } catch (RuntimeException bug) {
      LOG.error("unexpected failure", bug);
      return problems.reply(
          ProblemType.INTERNAL_ERROR, "quote the instance to support", request.correlationId());
    } finally {
      inFlight.release();
    }
  }
```

`admit` decides what runs. No route: `refuseUnrouted`. `inFlight.tryAcquire()` takes a permit without waiting; if none is left, the reply is the 503 `service-busy` with `Retry-After`. The comment gives the reason: a fast refusal keeps the work already admitted fast. Otherwise the handler runs inside a `try`. A `SQLException` is logged with its SQL state and becomes the 503 `storage-unavailable` ("nothing was changed; retry with the same key") with `Retry-After`. Any `RuntimeException` is a bug: it is logged with its stack trace and becomes the 500 `internal-error`. The `finally` always releases the permit.

```java
  private Reply refuseUnrouted(ApiRequest request) {
    List<String> allowed =
        routes.stream()
            .filter(candidate -> candidate.pattern().matcher(request.path()).matches())
            .map(Route::method)
            .toList();
    if (allowed.isEmpty()) {
      return problems.reply(ProblemType.NOT_FOUND, "no such path", request.correlationId());
    }
    return problems
        .reply(
            ProblemType.METHOD_NOT_ALLOWED,
            "this path takes " + String.join(", ", allowed),
            request.correlationId())
        .withHeader("Allow", String.join(", ", allowed));
  }
```

`refuseUnrouted` finds the methods that exist for the path. None means 404 "no such path". Some means 405 "this path takes GET, POST" and an `Allow` header with the same list.

```java
  private Optional<Route> match(ApiRequest request) {
    return routes.stream()
        .filter(route -> route.method().equals(request.method()))
        .filter(route -> route.pattern().matcher(request.path()).matches())
        .findFirst();
  }
```

`match` is a stream pipeline: keep routes with the same method, keep those whose pattern matches the path, take the first, or an empty `Optional`.

```java
  private static ApiRequest read(HttpExchange exchange, String correlationId) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
    byte[] body;
    try (InputStream bodyStream = exchange.getRequestBody()) {
      // One byte over the limit tells "exactly at the limit" from "too large" without reading on.
      body = bodyStream.readNBytes(ContractLimits.MAX_BODY_BYTES + 1);
    }
    boolean tooLarge = body.length > ContractLimits.MAX_BODY_BYTES;
    return new ApiRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getRawPath(),
        Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""),
        headers,
        tooLarge ? "" : new String(body, StandardCharsets.UTF_8),
        tooLarge,
        List.of(),
        correlationId);
  }
```

`read` copies the headers into a map with lower-case names (the JDK normalizes the case of header names, so a client's `Idempotency-Key` arrives as `Idempotency-key`; the lower-casing makes lookups simple). It reads at most `MAX_BODY_BYTES + 1` bytes of the body with `readNBytes`. If more than the limit arrived, the body is dropped and `tooLarge` is true. The request is built with the method, the raw path (not decoded), the raw query or an empty string, the headers, the body as UTF-8 text, the flag, an empty list of path values and the id.

```java
  private static void send(HttpExchange exchange, Reply reply) throws IOException {
    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    if (reply.status() == NO_CONTENT) {
      exchange.sendResponseHeaders(NO_CONTENT, NO_BODY);
      return;
    }
    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
    exchange.sendResponseHeaders(reply.status(), body.length);
    try (OutputStream responseStream = exchange.getResponseBody()) {
      responseStream.write(body);
    }
  }
```

`send` copies the reply's headers into the exchange. A 204 sends the status with `NO_BODY` and returns. Otherwise it sets `Content-Type`, sends the status with the body length (so the reply has a `Content-length` and no chunking), and writes the bytes. The `try` closes the response stream.

```java
  /** What a route runs. */
  @FunctionalInterface
  interface RouteHandler {
    Reply handle(ApiRequest request) throws SQLException;
  }
```

`RouteHandler` is a **functional interface** (one method, `handle`), so a method reference such as `catalog::listEvents` can stand for it. It may throw `SQLException`.

```java
  /**
   * One method and path template.
   *
   * @param method the HTTP method
   * @param template the path as the contract writes it, also the metrics label
   * @param pattern the template as a regular expression, one group per {@code {name}}
   * @param handler what runs
   */
  private record Route(String method, String template, Pattern pattern, RouteHandler handler) {
```

`Route` is a private record: the method, the template as the contract writes it (also the metrics label), the pattern and the handler.

```java
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z]+\\}");

    static Route of(String method, String template, RouteHandler handler) {
      // Each {name} matches one path segment; the handler checks its format against the contract.
      String regex =
          PLACEHOLDER
              .splitAsStream(template)
              .map(Pattern::quote)
              .collect(Collectors.joining("([^/]+)"));
      if (template.endsWith("}")) {
        regex += "([^/]+)";
      }
      return new Route(method, template, Pattern.compile(regex), handler);
    }
```

`Route.of` builds the pattern from the template. `PLACEHOLDER` matches `{name}`. `splitAsStream(template)` cuts the template at each placeholder; `Pattern.quote` makes each fixed piece literal; `Collectors.joining("([^/]+)")` puts a capture group, "one or more characters that are not a slash", between the pieces. A split drops a trailing empty piece, so a template that ends with a placeholder gets its group added by the `endsWith("}")` line. `/v1/holds/{holdId}` becomes a pattern with one group; `/v1/events/{eventId}/seats` with one group in the middle. A group matches one path segment, and the handler checks its format.

```java
    String label() {
      return method + " " + template;
    }
```

`label()` is `GET /v1/holds/{holdId}`: the template, never the real path, so the metrics have few labels.

```java
    List<String> valuesIn(ApiRequest request) {
      Matcher matcher = pattern.matcher(request.path());
      if (!matcher.matches()) {
        throw new IllegalStateException("a matched route no longer matches");
      }
      List<String> values = new ArrayList<>();
      for (int group = 1; group <= matcher.groupCount(); group++) {
        values.add(matcher.group(group));
      }
      return values;
    }
  }
```

`valuesIn` runs the pattern on the path and returns the captured groups in order. It throws if a matched route no longer matches, which cannot happen.

## Try it yourself

I ran these in a copy of the repository: `./gradlew -q serve --args=...` with the port set to 28187 and an in-memory database. In your checkout use the port of `config/serve.properties` (8087). Ids, times and the order of headers differ in each run.

1. A hold with one key, then the same key again with the fields in another order. The second reply is the stored first reply:

```text
$ curl -s -i -X POST http://127.0.0.1:28187/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "k-3"' -d '{ "customer":"fan_7", "seat":"A-7" ,"eventId":1}'
HTTP/1.1 201 Created
Date: Wed, 11 Nov 2026 01:37:39 GMT
Content-type: application/json
Content-length: 131
Location: /v1/holds/3cf3931c-d0db-4905-ade4-98684267f96e

{"holdId":"3cf3931c-d0db-4905-ade4-98684267f96e","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T01:42:39.846330Z"}
```

2. Wrong body shapes, each with the same reply layout. Here the JSON field names the wrong type (a string for `eventId`), and its pointer is `/eventId`:

```text
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"one field is invalid","instance":"urn:uuid:8baa4cf7-acaf-4e94-99ab-c5249c66cf85","errors":[{"pointer":"/eventId","detail":"has the wrong type"}]}
```

   Command: `curl -s -X POST http://127.0.0.1:28187/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "k-2"' -d '{"eventId":"1","seat":"A-7","customer":"fan_7"}'`. A float (`1.5`) gave the same reply; a duplicate field gave pointer `""` and "the body is not valid JSON"; `{}` gave three errors at once (`/eventId`, `/seat`, `/customer`) and the detail "3 fields are invalid".

3. A path that does not exist, and a method the path does not take:

```text
$ curl -s -i http://127.0.0.1:28187/nothing
HTTP/1.1 404 Not Found
Date: Wed, 11 Nov 2026 01:37:22 GMT
Content-type: application/problem+json
Content-length: 177

{"type":"https://seatwave.example/problems/not-found","title":"No such resource","status":404,"detail":"no such path","instance":"urn:uuid:1afc09c2-38dc-4d4f-b157-63108e461d2c"}
$ curl -s -i -X DELETE http://127.0.0.1:28187/v1/events
HTTP/1.1 405 Method Not Allowed
Date: Wed, 11 Nov 2026 01:37:22 GMT
Allow: GET
Content-type: application/problem+json
Content-length: 212

{"type":"https://seatwave.example/problems/method-not-allowed","title":"This path does not take this method","status":405,"detail":"this path takes GET","instance":"urn:uuid:603b1b5b-f67c-4693-8ce1-3a68266f8d0b"}
```

4. A request without `Content-Type: application/json` is refused with 415 before the body is read; a body of 1,100 bytes gets 413:

```text
HTTP/1.1 413 Request Entity Too Large
Date: Wed, 11 Nov 2026 01:37:32 GMT
Content-type: application/problem+json
```

5. Module 1's path, with the three headers of the deprecation:

```text
$ curl -s -i http://127.0.0.1:28187/events
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 01:37:22 GMT
Sunset: Tue, 11 May 2027 00:00:00 GMT
Content-type: application/json
Deprecation: @1794355200
Content-length: 6989
Link: </v1/events>; rel="successor-version"
```

   Look at the order of the headers: it is not the order the code adds them. That confirms the header order is not guaranteed.

6. The metrics after these calls (first lines):

```text
seatwave_requests_total{route="POST /v1/holds",status="201"} 3
seatwave_requests_total{route="POST /v1/holds",status="400"} 8
seatwave_requests_total{route="POST /v1/holds",status="413"} 1
seatwave_requests_total{route="POST /v1/holds",status="415"} 10
seatwave_idempotency_outcomes_total{endpoint="POST /v1/holds",outcome="first"} 2
seatwave_idempotency_outcomes_total{endpoint="POST /v1/holds",outcome="replayed"} 1
seatwave_idempotency_keys_stored 2
```

   Command: `curl -s http://127.0.0.1:28187/metrics`. The counts here come from my own session and will differ from yours.

## Mistakes people make

- **Ignoring unknown JSON fields.** A typo passes in silence. Here the mapper refuses them.
- **Validating one field at a time.** The client then needs one round trip per mistake. Here all errors are collected into one 400.
- **Mapping errors in every handler.** The mappings drift apart. Here `Problems` is the one place.
- **Leaking details.** A stack trace or a SQL message tells an attacker about the system. Here the client gets a fixed title and a `instance` to quote; the details stay in the log.
- **Unlimited work.** Without a limit, an overload slows every request. Here a semaphore refuses the extra request at once with a 503 and `Retry-After`.
- **Using the real path as a metrics label.** Every hold id would create a new series. Here the label is the template.
- **Fingerprinting the raw bytes.** A retry with spaces or reordered fields would look like a different request. Here the validated request is written back out first.
- **Trusting header-name case.** Header names are case-insensitive. Here every name is lower-cased on the way in.

## Check yourself

1. Which object does the JDK pass to `ApiServer.serve`, and which thread runs it?
   Answer: an `HttpExchange`; a virtual thread from the executor, one per request.
2. Why does `read` ask for `MAX_BODY_BYTES + 1` bytes?
   Answer: to tell a body of exactly 1,024 bytes (allowed) from a longer one (413) without reading the rest.
3. What happens to a request when all `http.maxInFlight` permits are taken?
   Answer: `tryAcquire` fails and the reply is a 503 `service-busy` with a `Retry-After` header; no handler runs.
4. A client sends `{"eventId":"1","seat":"A-7","customer":"fan_7"}`. Which reply, and why that pointer?
   Answer: a 400 with one error at `/eventId`, "has the wrong type". Jackson was told not to convert text to a number, and the failing field's name makes the pointer.
5. Where does a `SQLException` become a client reply, and what does the client see?
   Answer: in `ApiServer.admit`; a 503 `storage-unavailable` with `Retry-After`. The SQL state is only logged.

## Further reading

- Java SE 25 API, package `com.sun.net.httpserver`: https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 9110, HTTP Semantics: https://www.rfc-editor.org/rfc/rfc9110
- RFC 6901, JavaScript Object Notation (JSON) Pointer: https://www.rfc-editor.org/rfc/rfc6901
- RFC 8594, The Sunset HTTP Header Field: https://www.rfc-editor.org/rfc/rfc8594
- RFC 9745, The Deprecation HTTP Response Header Field: https://www.rfc-editor.org/rfc/rfc9745
- RFC 8288, Web Linking: https://www.rfc-editor.org/rfc/rfc8288
- Prometheus, text exposition format: https://prometheus.io/docs/instrumenting/exposition_formats/
- Java SE 25 API, `java.util.concurrent.Semaphore`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Semaphore.html
