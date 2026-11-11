# JSON and Jackson 2.23.0

## What it is

**JSON** (JavaScript Object Notation) is a plain-text format for structured data. A JSON text is one **value**. A value is one of: an **object** (named fields in curly braces), an **array** (an ordered list in square brackets), a **string** (text in double quotes), a **number**, `true` or `false`, or `null` (no value). This is a real reply of the API:

```json
{"holdId":"3cf3931c-d0db-4905-ade4-98684267f96e","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T01:42:39.846330Z"}
```

It is an object with five fields. `eventId` is a number; the others are strings. The format is defined in RFC 8259.

**Jackson** is a Java library that converts between JSON text and Java objects. Reading JSON into an object is **binding** (also called deserializing or parsing into a type). Writing an object as JSON is **serializing**. The JDK has no JSON parser, so a library is needed. This repository pins Jackson **2.23.0**. Three Jackson jars take part: `jackson-core` (the low-level reader and writer of tokens, 2.23.0), `jackson-databind` (the binding, 2.23.0) and `jackson-annotations` (the tags such as `@JsonInclude`, version `2.23`, which Jackson numbers with two parts). A fourth artifact, `jackson-bom`, is a bill of materials: a list of matching versions, so every Jackson jar on the class path has the same version.

## Why this repository uses it

Module 7's contract says that a request body is an object with exactly the listed fields, of exactly the listed types. A parser that is lenient by default (ignoring unknown fields, converting `"1"` to `1`, accepting a repeated field) would accept requests the contract forbids, and two layers of a system could read one request in two ways. The repository therefore configures Jackson to be strict, binds only to known **records**, and turns each failure into a message that names the field.

The README states the choice: Jackson 2.x is used because the OpenAPI test tools use it, so one Jackson version serves every class path.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/`) | How it uses Jackson |
|---|---|
| `json/JsonCodec.java` | The one class that holds the configured `ObjectMapper`. `read` binds text to a record; `write` serializes an object. |
| `http/ProblemBody.java` | The only use of a Jackson annotation: `@JsonInclude`. |
| `http/V1Views.java`, `http/Version0Views.java` | Records whose fields are the JSON fields of replies and request bodies. |
| `http/Problems.java`, `CatalogRoutes.java`, `BookingRoutes.java`, `OperationsRoutes.java`, `Version0Routes.java` | Call `JsonCodec.write` for replies and `JsonCodec.read` for request bodies. |
| `payment/PaymentClient.java`, `PaymentStandIn.java` | Use the same codec for the provider's messages (another note). |
| `SeatWaveApi.java`, `http/ApiServer.java` | Create the codec once and pass it to the parts that need it. |
| `gradle/libs.versions.toml`, `build.gradle.kts` | Pin the version and import the bill of materials (another note). |

## How it works underneath

**An `ObjectMapper`** is Jackson's main object. You configure it once and then call `readValue(text, SomeType.class)` or `writeValueAsString(object)` as often as you like, from any thread. Configuration is not changed after the mapper is built; Jackson documents that use as thread-safe.

**Binding to a record.** A record has a canonical constructor with one parameter per field. Jackson calls it with the JSON fields matched by name. The record `HoldRequestBody(Long eventId, String seat, String customer)` binds `{"eventId":1,"seat":"A-7","customer":"fan_7"}` by calling `new HoldRequestBody(1L, "A-7", "fan_7")`. A field absent from the JSON arrives as `null` (or as 0 or false for a primitive). A record needs no setters, no default constructor and no annotations.

**Writing a record.** Jackson reads the record's accessor methods and writes the fields in the order the record declares them. That is why the replies always show `holdId` first.

**Streaming and features.** Underneath, `jackson-core` reads the text as a stream of tokens: start of object, field name, value, end of object. A **feature** is a named switch on the mapper or on the stream reader. There are `DeserializationFeature`s (about binding), `MapperFeature`s (about how types are inspected and converted) and `StreamReadFeature`s (about the token reader).

**Why the strict switches matter.** Each lenient default is a place where two parsers can disagree about one body. A proxy might read the first `eventId` and the service the last. A lenient parser turns `"1"` into `1` and a strict contract check does not. Mass assignment (a client setting a field it should not) starts with ignoring unknown fields.

## Walkthrough of this repository's use

### The configuration

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

Line by line:

- `JsonMapper.builder()` starts a builder for a mapper. A builder collects the settings, and `.build()` makes the mapper; after that the settings are fixed.
- `.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`: a JSON field with no matching record component is an error (`UnrecognizedPropertyException`). Jackson's default is to ignore it. The contract says `additionalProperties: false`.
- `.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)`: a JSON `null` for a Java `int`, `long` or `boolean` is an error. Without it, `null` becomes 0 or false in silence. (The request bodies of this API use the boxed types `Long` and `String`, so `null` arrives as `null` and the validation step names the field. The switch protects records with primitive fields.)
- `.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)`: a string such as `"1"` is not accepted where a number is expected.
- `.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)`: `1.5` is not accepted for a whole number and is not rounded.
- `.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)`: after the first complete value, only white space may follow.
- `.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)`: the same field name twice in one object is an error. This one is a stream feature, which is why it is `StreamReadFeature` and not `DeserializationFeature`.

### Reading

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

`mapper.readValue(json, type)` binds the text. The exceptions map to messages:

- `UnrecognizedPropertyException` is an unknown field. The pointer is `/` plus the name, such as `/price`.
- `MismatchedInputException` is a value of the wrong type. The pointer is built from the path Jackson was reading.
- `JacksonException` is the parent of every Jackson failure (broken syntax, a repeated field, a number too large for its type). The pointer is `""` (the whole body) and the message is "the body is not valid JSON".
- A `null` result means the text was the JSON value `null`.

The order of the `catch` blocks goes from the most specific exception to the most general.

```java
  private static String pointerOf(MismatchedInputException wrongType) {
    // RFC 6901 pointer: "/field" for each step of the path Jackson was reading.
    return wrongType.getPath().stream()
        .map(step ->
            step.getFieldName() == null ? String.valueOf(step.getIndex()) : step.getFieldName())
        .collect(Collectors.joining("/", "/", ""));
  }
```

`getPath()` returns the steps from the root to the failing value. A step has a field name, or an array index. `Collectors.joining("/", "/", "")` writes the JSON pointer of RFC 6901. A failure at the root has no steps and so gives `/`.

### Writing

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

`writeValueAsString(value)` gives the JSON text. It throws the checked `JsonProcessingException` for something it cannot write; the code turns that into an unchecked `IllegalArgumentException`, because a value that cannot be written is a programming error.

### The one annotation

```java
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ProblemBody(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    List<FieldError> errors) {
```

`@JsonInclude(JsonInclude.Include.NON_EMPTY)` on a record tells Jackson to leave out null, empty collections and empty strings. A problem without field errors therefore has no `errors` key at all, as the contract says.

### Records written for the contract

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

`HoldRequestBody` uses `Long` and not `long` on purpose: with a primitive, a missing field would arrive as 0 and could not be told from a sent 0. As a boxed `Long`, a missing field is `null` and the route reports "is required". The views keep times as `String` (the code writes `Instant.toString()`), so Jackson needs no date module; the main class path has none.

## Try it yourself

I started the API in a copy of the repository (`./gradlew -q serve --args=...`, port 28187, in-memory database) and sent bodies to `POST /v1/holds` with a valid `Content-Type` and key. Each line shows the `errors` entry of the 400 reply. Use the port of your own settings file (8087).

Command shape (replace the body):

`curl -s -X POST http://127.0.0.1:28187/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "j-1"' -d '<body>'`

| Body | What the reply said |
|---|---|
| `{"eventId":1,"seat":"D-1","customer":"fan_1","price":0}` | pointer `/price`, "is not a field of this request" |
| `{"eventid":1,"seat":"D-1","customer":"fan_1"}` | pointer `/eventid`, "is not a field of this request" (names are case-sensitive) |
| `{"eventId":"1","seat":"A-7","customer":"fan_7"}` | pointer `/eventId`, "has the wrong type" |
| `{"eventId":1.5,"seat":"A-7","customer":"fan_7"}` | pointer `/eventId`, "has the wrong type" |
| `{"eventId":true,"seat":"D-1","customer":"fan_1"}` | pointer `/eventId`, "has the wrong type" |
| `{"eventId":1,"seat":["D-1"],"customer":"fan_1"}` | pointer `/seat`, "has the wrong type" |
| `{"eventId":1,"eventId":2,"seat":"A-7","customer":"fan_7"}` | pointer empty, "the body is not valid JSON" |
| `{"eventId":1,"seat":"A-7","customer":"fan_7"} {}` | pointer `/`, "has the wrong type" |
| `{"eventId":1,"seat":"D-1","customer":"fan_1",}` | pointer empty, "the body is not valid JSON" |
| `{"eventId":null,"seat":"A-7","customer":"fan_7"}` | pointer `/eventId`, "is required: a whole number of 1 or more" |
| `null` | pointer empty, "the body must be a JSON object" |
| `[1]` | pointer `/`, "has the wrong type" |
| `{"eventId":99999999999999999999,"seat":"D-1","customer":"fan_1"}` | pointer empty, "the body is not valid JSON" (the number is valid JSON text but does not fit a `Long`; the reply uses the generic message) |

Two results are worth noticing, because they show where the switches stop. A number sent for a **string** field is accepted: `{"eventId":1,"seat":"D-1","customer":5}` created a hold for the customer `"5"`. `ALLOW_COERCION_OF_SCALARS` guards the number targets I tried (the `eventId` rows above), and I did not find a switch in this configuration that makes a string field refuse a number. The field is still checked by the customer pattern afterwards.

And a writer's view: the same hold sent with the fields in another order and extra spaces gets the identical stored reply, because the fingerprint is taken from the record written back out by `JsonCodec.write` (see `java-http-walkthrough.md`).

## Mistakes people make

- **Leaving the defaults.** Jackson ignores unknown fields and converts strings to numbers unless told not to. Here both are switched off.
- **Binding to `Map` or `Object`.** Anything can then arrive. Here every read names a record type.
- **Turning on default typing (class names inside the JSON).** It lets a sender choose which class is built, a classic attack. This code never enables it.
- **Changing the mapper after it is shared.** Settings are not thread-safe to change. Here everything is set in the builder.
- **Primitive fields for optional input.** A missing number becomes 0. Here the request records use boxed types.
- **Putting a library's exception message in the reply.** It may reveal class names. Here the code writes its own short message.
- **Using `double` for money in JSON.** Here `amount` is a string such as `"80.00"`.

## Check yourself

1. What does binding mean, and what does Jackson need to bind to a record?
   Answer: reading JSON text into a Java object; Jackson calls the record's canonical constructor with the JSON fields matched by name.
2. What does `FAIL_ON_UNKNOWN_PROPERTIES` protect against?
   Answer: a typo or an extra field passing in silence, and mass assignment.
3. Why is `STRICT_DUPLICATE_DETECTION` a `StreamReadFeature`?
   Answer: duplicates are found by the token reader, before any binding happens.
4. Why does `HoldRequestBody` use `Long` and not `long`?
   Answer: so a missing field is `null` and can be reported as "is required"; a `long` would turn a missing field into 0.
5. What JSON pointer does a type error in the field `eventId` get, and what pointer does a body with trailing data get?
   Answer: `/eventId` for the field; `/` for trailing data, because the path is empty.

## Further reading

- RFC 8259, The JavaScript Object Notation (JSON) Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259
- RFC 6901, JavaScript Object Notation (JSON) Pointer: https://www.rfc-editor.org/rfc/rfc6901
- Jackson project, `jackson-databind` (README and wiki): https://github.com/FasterXML/jackson-databind
- Jackson 2.23.0 Javadoc, `jackson-databind`: https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.23.0/index.html
- Jackson 2.23.0 Javadoc, `DeserializationFeature`: https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.23.0/com/fasterxml/jackson/databind/DeserializationFeature.html
- Jackson 2.23.0 Javadoc, `StreamReadFeature`: https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-core/2.23.0/com/fasterxml/jackson/core/StreamReadFeature.html
