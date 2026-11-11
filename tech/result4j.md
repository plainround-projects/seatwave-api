# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (the README of this repository lists the license and the date the version was checked).

## Why this repository uses it

A settings file, a JSON body, a query string, a cursor and the answer of a payment provider all come from outside the program. A person can write `http.port=80`. A client can send a body with a field that does not exist. A fan can retry with the same key and another seat. These are **expected failures**: the program should list them, name them and answer them, not die with a stack trace. One run should report every problem, not only the first.

Exceptions are kept for programmer errors: a null argument, a record built with a value its constructor forbids, a database that is down. The line is: if a person, a file or the network can cause it in normal use, it is a `Result`; if only a bug can cause it, it is an exception. In the booking code the second kind is a `SQLException` (the database failed: the request gets a 503) and `IllegalStateException` (a bug).

The README gives the reason the JDK alone is not enough: the JDK has no success-or-typed-error type, so "seat taken" or "key reused" would have to be an exception.

The dependency is declared in `gradle/libs.versions.toml`, used in `build.gradle.kts` and pinned in `gradle.lockfile`:

```text
result4j = "1.2.0"
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
implementation(libs.result4j)
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths.

## Where it appears

Fourteen files under `src/main/java` import the library.

| File | What it does with `Result` |
|---|---|
| `config/ApiSettings.java` | `from` returns `Result<ApiSettings, List<String>>`: the error is the list of problem lines. |
| `json/JsonCodec.java` | `read` returns `Result<T, BindingProblem>`: a JSON body bound to a record, or the field at fault. |
| `http/Parameters.java` | `parse` returns `Result<Parameters, List<FieldError>>`: a query string or a form body. |
| `catalog/CatalogService.java` | `listEvents` and `listSeats` return `Result<Page<...>, CatalogProblem>`; `startOf` returns `Result<Cursors.SeatPosition, CatalogProblem>`. |
| `booking/HoldService.java` | `hold` returns `Result<KeyedReply, Refusal>`. |
| `booking/ConfirmService.java` | `confirm`, `start`, `finish`, `replayAfterLostRace` and `replayStored` return results; `start` nests an `Optional` inside. |
| `payment/PaymentClient.java` | Switches over `json.read(...)`. |
| `payment/PaymentStandIn.java` | Tests the result of `json.read(...)` with a negated `instanceof` pattern. |
| `http/BookingRoutes.java` | `keyedReply` switches over the booking result; `bind` switches over `json.read`. |
| `http/CatalogRoutes.java` | Switches over the catalog result and over `Parameters.parse`. |
| `http/Version0Routes.java` | Tests and switches over catalog, parameter and booking results. |
| `ServeCli.java` | `main` switches over `ApiSettings.from`. |
| `load/PageWalkCli.java` | `main` switches over `ApiSettings.from`. |
| `load/RetryStormCli.java` | `main` switches over `ApiSettings.from`. |

Besides the main code, `src/test/.../config/ApiSettingsTest.java` imports it; that file is covered in the test notes.

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the 1.2.0 jar and confirmed it: the class file of `Result` has a `PermittedSubclasses` entry listing `Result$Success` and `Result$Error`, and both are `final` classes that extend `java.lang.Record`. A **sealed** interface lists every type allowed to implement it. That is what lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`, and a `switch` that forgets one does not compile.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=...]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=...]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<ApiSettings, List<String>>(ApiSettings settings)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

**The calls this repository uses:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch` over the `Result` with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`, as a statement or as an expression.
- `instanceof` with the same patterns: `result instanceof Result.Error<...>(Refusal refusal)`.
- `map` once: `replayStored(claim).map(Optional::of)` in `ConfirmService.start` (line 167) changes the value inside a success and leaves an error alone.
- `Result::success` as a method reference, in `ConfirmService.replayAfterLostRace`. It is passed to `Optional.map`, not to `Result.map`.

The library offers more. I listed its public methods with `javap -public` on the jar: `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, `fromOptional`, `fromOptionalResult`, and the function builders `mapping`, `flatMapping` and `errorMapping`. The main code uses none of them except `map` and `success`/`error`.

I ran a small program with the 1.2.0 jar and the compiled repository on the class path:

```text
true
Success[result=8087]
true
problem: http.port must be from 1024 to 65535, but was 80
problem: http.maxInFlight is missing
problem: database.url is missing
Error[error=no 7]
```

Line 1 shows `ApiSettings.from` on `config/serve.properties` gave a `Success` (an `instanceof` test). Line 2 shows `map` applied to a success: `ApiSettings::httpPort` turned it into `Success[result=8087]`. Line 3 shows `isError()` on the result of a settings file with only `http.port=80`. The next three lines are a `switch` over that error, printing the first three of its problem lines. The last line shows `flatMap` turning a success into an error.

**Java version.** The class files in the 1.2.0 jar have major version 65, which is Java 21 (`javap -v` printed `major version: 65`). So the library needs Java 21 or newer, and this repository's `switch` with record patterns also needs Java 21. The project builds with `--release 25`. The jar was built with JDK 23 (`Build-Jdk-Spec: 23` in its manifest).

## Walkthrough of this repository's use

### Building results: the settings

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings file
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<ApiSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
```

- `from` returns `Result<ApiSettings, List<String>>`. The success holds the settings. The error holds one text line per missing or invalid key.

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    // Every rule the constructors check was checked above, so these cannot throw.
    return Result.success(
        new ApiSettings(
            port,
            inFlight,
            url,
            seed,
            new Payment(paymentUrl, connect, request, lease),
            price,
            currency,
            Duration.ofSeconds(retryAfterSeconds),
            new Version0(deprecatedAt, sunsetAt)));
  }
```

- After the reader has looked at every key, `if (!reader.problems().isEmpty()) return Result.error(reader.problems());` returns all the problems at once. Otherwise the record is built and returned with `Result.success(...)`.
- The two rules between keys (the lease against the timeouts, the sunset against the deprecation) are checked earlier in `from` and added to the same problem list with `reader.reject(...)`, so they appear with every other problem. The records' constructors still check the same rules and throw `IllegalArgumentException`, as the last line of defence for code that builds a record directly; the comment says that on this path they cannot throw. This shows the two styles side by side: the constructor is for code that must never pass a bad value, `from` is for text from outside.

### Building results: a JSON body

```java
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
    // The parser is driven by hand, so an empty body, a body that is not an object and data after
    // the object each get their own message instead of one generic type error.
    try (JsonParser parser = mapper.createParser(json)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body is empty; send a JSON object"));
      }
      if (first != JsonToken.START_OBJECT) {
        return Result.error(new BindingProblem(WHOLE_BODY, "the body must be a JSON object"));
      }
      T value = mapper.readValue(parser, type);
      if (parser.nextToken() != null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body has data after the JSON object"));
      }
      return Result.success(value);
    } catch (UnrecognizedPropertyException unknownField) {
      return Result.error(
          new BindingProblem(
              "/" + escaped(unknownField.getPropertyName()), "is not a field of this request"));
    } catch (MismatchedInputException wrongType) {
      return Result.error(new BindingProblem(pointerOf(wrongType), "has the wrong type"));
    } catch (JsonMappingException unbound) {
      // 99999999999999999999 is valid JSON; it only does not fit the field's type.
      if (unbound.getCause() instanceof InputCoercionException) {
        return Result.error(
            new BindingProblem(pointerOf(unbound), "is out of range for this field"));
      }
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (JacksonException malformed) {
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (IOException notReadable) {
      // Every parse failure is a JacksonException; reading from a string has no other I/O to fail.
      throw new UncheckedIOException("reading a string failed", notReadable);
    }
  }
```

- `read` returns `Result<T, BindingProblem>`. Jackson, the JSON library, signals problems with exceptions. The method checks the shape of the whole body itself (empty, not an object, data after the object) and catches the failures it expects, turning each into a `Result.error(new BindingProblem(pointer, detail))`: an unknown field (`is not a field of this request`), a wrong type (`has the wrong type`), a number out of range (`is out of range for this field`), anything else that is not valid JSON. A caller never sees a Jackson exception. The `pointer` names the field at fault, so a 400 reply can list it.

### Building results: a query string

```java
  static Result<Parameters, List<FieldError>> parse(String encoded, Set<String> allowedNames) {
    List<FieldError> errors = new ArrayList<>();
    Parameters parameters = read(encoded, allowedNames, errors);
    return errors.isEmpty() ? Result.success(parameters) : Result.error(List.copyOf(errors));
  }

  /**
   * Reads every pair, adds one error per bad pair to {@code errors}, and keeps the good ones, so
   * the caller can still check their values and report every problem in one reply.
   *
   * @return the allowed names with their first value
   */
  static Parameters read(String encoded, Set<String> allowedNames, List<FieldError> errors) {
    Map<String, String> values = new LinkedHashMap<>();
    if (encoded.isEmpty()) {
      return new Parameters(values);
    }
    for (String pair : encoded.split("&", -1)) {
      int separator = pair.indexOf('=');
      if (separator <= 0) {
        errors.add(new FieldError(pair, "is not a name=value pair"));
        continue;
      }
      try {
        String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
        String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
        if (!allowedNames.contains(name)) {
          errors.add(new FieldError(name, "is not a parameter of this endpoint"));
        } else if (values.putIfAbsent(name, value) != null) {
          errors.add(new FieldError(name, "is given more than once"));
        }
      } catch (IllegalArgumentException badEncoding) {
        // URLDecoder rejects a broken percent escape; that is the client's error, reported.
        errors.add(new FieldError(pair, "is not valid percent-encoding"));
      }
    }
    return new Parameters(values);
  }
```

- `read` collects errors in the caller's list while it walks the pairs and always returns the parameters it kept. `parse` wraps it: `Result.error(List.copyOf(errors))` if there is any error, `Result.success(parameters)` otherwise. A bad query string produces all its errors in one reply. `CatalogRoutes` calls `read` directly, so it can check the values of the good parameters too and add those errors to the same reply.

### Building results: the catalog

```java
  private static Result<Cursors.SeatPosition, CatalogProblem> startOf(
      long eventId, SeatFilter filter, Position position) {
    return switch (Objects.requireNonNull(position, "position")) {
      case Position.First() ->
          Result.success(
              new Cursors.SeatPosition(eventId, filter, BEFORE_FIRST_SECTION, BEFORE_FIRST_NUMBER));
      case Position.After(String cursor) -> {
        Optional<Cursors.SeatPosition> decoded = Cursors.decodeSeat(cursor);
        if (decoded.isEmpty()) {
          yield Result.error(new CatalogProblem.BadCursor("this cursor was not made by this list"));
        }
        // A cursor names a place under one set of filters; under others it could skip seats.
        if (decoded.get().eventId() != eventId || !decoded.get().filter().equals(filter)) {
          yield Result.error(
              new CatalogProblem.BadCursor("this cursor was made for another event or filter"));
        }
        yield Result.success(decoded.get());
      }
    };
  }
```

- `startOf` is a `switch` expression that yields a `Result` in each branch. `Result.success(...)` for the first page and for a cursor that checks out; `yield Result.error(new CatalogProblem.BadCursor(...))` for a cursor that is not valid or was made for another event or filter.

### Building results: the booking refusals

```java
  public Result<KeyedReply, Refusal> hold(
      HoldCommand command, KeyClaim claim, Function<HoldOutcome, StoredReply> render)
      throws SQLException {
```

```java
        switch (keys.claim(connection, claim, now)) {
          case ClaimResult.Replay(StoredReply reply) -> {
            connection.rollback();
            return Result.success(new KeyedReply(reply, KeyedReply.Source.REPLAY));
          }
          case ClaimResult.BodyMismatch() -> {
            connection.rollback();
            return Result.error(new Refusal.KeyReusedWithOtherBody());
          }
          case ClaimResult.Claimed(), ClaimResult.Resumed() -> {
            // This request owns the key: do the work below.
          }
        }
```

- `hold` returns `Result<KeyedReply, Refusal>`. The success is a reply (new or replayed). The error is a `Refusal`: a thing that is not a final outcome and is not stored under the key. The two returns inside the `switch` show both sides: `return Result.success(new KeyedReply(reply, KeyedReply.Source.REPLAY))` and `return Result.error(new Refusal.KeyReusedWithOtherBody())`.

```java
  public Result<KeyedReply, Refusal> confirm(
      ConfirmCommand command, KeyClaim claim, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
```

```java
    Result<Optional<KeyedReply>, Refusal> started = start(holdId, claim, render);
    if (started instanceof Result.Error<Optional<KeyedReply>, Refusal>(Refusal refusal)) {
      return Result.error(refusal);
    }
    if (started instanceof Result.Success<Optional<KeyedReply>, Refusal>(Optional<KeyedReply> done)
        && done.isPresent()) {
      return Result.success(done.get());
    }
```

- `confirm` calls `start`, whose type is `Result<Optional<KeyedReply>, Refusal>`: three outcomes in one value. An error is a refusal. A success holding a present optional is a finished request. A success holding an empty optional means "go and pay".
- `started instanceof Result.Error<Optional<KeyedReply>, Refusal>(Refusal refusal)` tests and unpacks in one step. If it is an error, the variable `refusal` is set and the method returns `Result.error(refusal)`.
- The second `if` uses the success pattern together with `&& done.isPresent()`. A pattern variable can be used in the rest of the same condition.

```java
        StoredReply reply = render.apply(refused.get());
        if (!keys.complete(connection, claim, reply)) {
          // A racing attempt with this key finished between our claim and now: send its answer.
          connection.rollback();
          return replayStored(claim).map(Optional::of);
        }
```

- The one use of `Result.map`: `replayStored(claim)` returns a `Result<KeyedReply, Refusal>`, and `start` needs a `Result<Optional<KeyedReply>, Refusal>`. `.map(Optional::of)` wraps the success value in an `Optional` and leaves an error as it is. `Optional::of` is a method reference.

### Taking results apart: `switch`

```java
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
      switch (ApiSettings.from(reader)) {
        case Result.Success<ApiSettings, List<String>>(ApiSettings settings) ->
            serve(settings, Duration.ofSeconds(seconds));
        case Result.Error<ApiSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
```

- `main` is a command-line entry point. `switch (ApiSettings.from(reader))` has one case per side. `Result.Success<ApiSettings, List<String>>(ApiSettings settings) -> serve(...)` runs the server. `Result.Error<ApiSettings, List<String>>(List<String> problems)` prints each problem as `invalid: ...` and exits with status 2. There is no `default`: the compiler knows the two cases are all of them. `PageWalkCli` and `RetryStormCli` use the same shape.

```java
      return switch (json.read(response.body(), ChargeReply.class)) {
        case Result.Success<ChargeReply, JsonCodec.BindingProblem>(ChargeReply reply) ->
            "captured".equals(reply.status())
                ? new PaymentOutcome.Captured(reply.paymentId())
                : new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
        case Result.Error<ChargeReply, JsonCodec.BindingProblem>(
                JsonCodec.BindingProblem problem) ->
            new PaymentOutcome.Unavailable(PaymentOutcome.Reason.PROVIDER_ERROR);
      };
```

- A `switch` expression that returns a `PaymentOutcome` from a `Result`. The success case also checks the status text; the error case ignores the problem and gives `PROVIDER_ERROR`. Both type parameters are written in each pattern.

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
```

- `keyedReply` shows the success case with a block and `yield`: it counts a metric (`replayed` or `first`) and builds the reply. The error case hands the `Refusal` on to `refusalReply`.

```java
    return switch (json.read(request.body(), type)) {
      case Result.Success<T, JsonCodec.BindingProblem>(T body) -> Optional.of(body);
      case Result.Error<T, JsonCodec.BindingProblem>(JsonCodec.BindingProblem problem) -> {
        errors.add(new FieldError(problem.pointer(), problem.detail()));
        yield Optional.empty();
      }
    };
  }
```

- `bind` turns a `Result` from `json.read` into an `Optional`: a success is `Optional.of(body)`; an error adds a `FieldError` to the list and yields `Optional.empty()`. This is how the route collects every bad field before it replies.

```java
  private Result<KeyedReply, Refusal> replayAfterLostRace(
      KeyClaim claim, HoldId holdId, Function<ConfirmOutcome, StoredReply> render)
      throws SQLException {
    // Running step 1 again finds the stored reply, or stores "already booked" for this key.
    Result<Optional<KeyedReply>, Refusal> again = start(holdId, claim, render);
    return switch (again) {
      case Result.Success<Optional<KeyedReply>, Refusal>(Optional<KeyedReply> reply) ->
          reply
              .<Result<KeyedReply, Refusal>>map(Result::success)
              .orElseGet(() -> Result.error(new Refusal.ConfirmInProgress()));
      case Result.Error<Optional<KeyedReply>, Refusal>(Refusal refusal) -> Result.error(refusal);
    };
  }
```

- `replayAfterLostRace` switches over a `Result<Optional<KeyedReply>, Refusal>` and returns a `Result<KeyedReply, Refusal>`. `reply.<Result<KeyedReply, Refusal>>map(Result::success)` converts the optional's content into a success, and `.orElseGet(() -> Result.error(new Refusal.ConfirmInProgress()))` supplies the error when the optional was empty.

### Taking results apart: `instanceof`

```java
      Result<ChargeRequest, JsonCodec.BindingProblem> body =
          json.read(readBody(exchange), ChargeRequest.class);
      if (!(body
          instanceof
          Result.Success<ChargeRequest, JsonCodec.BindingProblem>(ChargeRequest request))) {
        send(exchange, BAD_REQUEST, "{\"error\":\"malformed charge\"}");
        return;
      }
```

- A negated test: `if (!(body instanceof Result.Success<...>(ChargeRequest request)))` means "if it is not a success, answer 400 and return". After the `if`, `request` is in scope because the `if` always returns. This keeps the main path unindented.

```java
      Result<Page<EventSummary>, CatalogProblem> page =
          catalog.listEvents(ContractLimits.MAX_PAGE_LIMIT, position);
      if (!(page instanceof Result.Success<Page<EventSummary>, CatalogProblem>(var found))) {
        throw new IllegalStateException("a cursor this server made was refused");
      }
```

- A negated `instanceof` with `var found`: the type of `found` is worked out by the compiler. If the version 1 list ever refused a cursor this server made, that is a bug, so the code throws `IllegalStateException`.

### Nested patterns

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
```

- `case Result.Error<KeyedReply, Refusal>(Refusal.PaymentUnavailable()) -> ...` puts a record pattern inside a record pattern: an error whose `Refusal` is a `PaymentUnavailable`. The next case, `Result.Error<KeyedReply, Refusal>(Refusal _)`, matches any other refusal. The `_` is an **unnamed pattern variable** (final in Java 22): "I need to match here but I will not use the value". The compiler still checks the three cases are exhaustive.

## Try it yourself

All commands run in a scratch copy of the repository. Timings differ on every run.

**1. See the two kinds of `Result` print.** Compile the main code (`./gradlew -q compileJava`) and save this as `R4.java` in a scratch folder:

```text
import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.api.config.ApiSettings;
import io.plainround.seatwave.api.config.SettingsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public class R4 {
  public static void main(String[] args) throws Exception {
    Result<ApiSettings, List<String>> good =
        ApiSettings.from(new SettingsReader(SettingsReader.load(Path.of(args[0]))));
    Properties empty = new Properties();
    empty.setProperty("http.port", "80");
    Result<ApiSettings, List<String>> bad = ApiSettings.from(new SettingsReader(empty));
    System.out.println(good instanceof Result.Success<ApiSettings, List<String>>);
    System.out.println(good.map(ApiSettings::httpPort));
    System.out.println(bad.isError());
    switch (bad) {
      case Result.Success<ApiSettings, List<String>>(ApiSettings settings) ->
          System.out.println("settings " + settings.httpPort());
      case Result.Error<ApiSettings, List<String>>(List<String> problems) ->
          problems.stream().limit(3).forEach(problem -> System.out.println("problem: " + problem));
    }
    System.out.println(Result.<Integer, String>success(7).flatMap(n -> Result.<Integer, String>error("no " + n)));
  }
}
```

```text
java -cp build/classes/java/main:<path to result4j-1.2.0.jar> R4.java config/serve.properties
```

```text
true
Success[result=8087]
true
problem: http.port must be from 1024 to 65535, but was 80
problem: http.maxInFlight is missing
problem: database.url is missing
Error[error=no 7]
```

The jar is in the Gradle cache under `~/.gradle/caches/modules-2/files-2.1/com.github.sviperll/result4j/1.2.0/`.

**2. Remove a case and watch the compiler refuse.** Delete the `Result.Error` case from the `switch` in `R4.java` and run it again. The compiler stops with `error: the switch statement does not cover all possible input values` (I ran this edit). A forgotten failure is a compile error, not a surprise at 3 a.m.

**3. Look inside the jar.**

```text
javap -cp <path to result4j-1.2.0.jar> -public com.github.sviperll.result4j.Result
```

It lists `success`, `error`, `isError`, `map`, `flatMap`, `andThen`, `mapError`, and the others named above. `javap -v` on the same class prints `major version: 65` and a `PermittedSubclasses:` block with `Result$Success` and `Result$Error`.

**4. See a `Result` become a reply.** With the API running (see `java-booking-walkthrough.md`), send a hold with an unknown field. The `Result.error` of `JsonCodec.read` becomes a list of field errors in a 400 reply:

```text
curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "r-1"' -d '{"eventId":1,"seat":"A-7","customer":"fan_7","price":0}'
```

The README shows the shape of the answer: `"errors":[{"pointer":"/price","detail":"is not a field of this request"}]`. (I did not run this exact request; the README's session shows the same field error.)

## Mistakes people make

- **Using an exception for an expected failure.** Every caller must then remember to catch it. A `Result` is in the method's type, so the compiler reminds them.
- **Using a `Result` for a bug.** A null argument or a violated invariant is not something a caller can fix by looking at a value. It stays an exception (`IllegalArgumentException`, `IllegalStateException`).
- **Adding a `default` to a `switch` over a sealed type.** It silences the compiler when a new case is added later. The `switch` statements here have none.
- **Ignoring the error side.** `result instanceof Success(var value)` and then doing nothing for the failure drops the reason. Every place here either replies with it or throws.
- **Catching the same exception in every caller.** Turn it into a `Result` once, at the edge, as `JsonCodec.read` does for Jackson's exceptions. The callers then handle one type.
- **Stacking `Result` inside `Result`.** `Result<Optional<KeyedReply>, Refusal>` in `ConfirmService.start` is worth its cost only because the optional really has two meanings. Keep the nesting low and explain it in a comment, as the code does.

## Check yourself

1. What are the two possible values of a `Result<R, E>`? (A `Success` holding an `R`, or an `Error` holding an `E`.)
2. Why does a `switch` over a `Result` need no `default`? (The interface is sealed to two records, so the compiler knows the two cases are all of them.)
3. In this repository, which failures are `Result`s and which are exceptions? (Failures a person, a client or a file can cause in normal use, such as a bad setting, a bad body, a reused key, are `Result`s. A database failure and a bug are exceptions.)
4. What does `ConfirmService.start` return when this attempt must go and pay? (A success holding an empty `Optional`.)
5. What does `Refusal _` mean in `Version0Routes.replyOf`? (An unnamed pattern variable: match any other refusal and ignore its value.)

## Further reading

- result4j on Maven Central, version 1.2.0: https://central.sonatype.com/artifact/com.github.sviperll/result4j/1.2.0
- result4j project page and source: https://github.com/sviperll/result4j
- Record patterns (JEP 440): https://openjdk.org/jeps/440
- Pattern matching for `switch` (JEP 441): https://openjdk.org/jeps/441
- Unnamed variables and patterns (JEP 456): https://openjdk.org/jeps/456
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- `java.util.Optional` (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html
