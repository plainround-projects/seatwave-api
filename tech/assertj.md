# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED))` says "all 100 replies are 201 Created" in one statement. The README gives the reason: "Fluent assertions with clear failure messages." In a test of an API the message matters, because a failure often shows a JSON body, a status number or a list of validation messages, and that value is the clue.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is used in the 16 test classes and in the helper `TestApi`; `assertThatThrownBy` in 4 classes (`FingerprintTest`, `IdempotencyKeyTest`, `ApiSettingsTest`, `DatabaseTest`). There are 223 `assertThat(` calls and 7 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Map`, one for an `int`, one for a `boolean`, one for an `Optional`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server blocks until the answer comes or its timeout ends, and only then does the assertion look at it. When a test must wait for something that happens later in another thread, it uses Awaitility (`awaitility.md`).

## Walkthrough of this repository's use

The numbers in this section come from `grep -rhoE` over `src/test` and count raw text matches of each method call, so a call of the same name on a plain `String` (for example inside a lambda) is counted too.

### `isEqualTo`: the most used check (123 calls)

```java
  @Test
  void confirmsOnceAndChargesOnceWhenTheConfirmIsRepeated() throws Exception {
    String first = bookingIdIn(api.confirm(holdId, "pay-once").body());

    TestApi.Response repeat = api.confirm(holdId, "pay-once");

    assertThat(repeat.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(bookingIdIn(repeat.body())).isEqualTo(first);
    assertThat(repeat.header("Location")).isEqualTo("/v1/bookings/" + first);
    assertThat(api.provider().chargesFor(holdId)).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
  }
```

`isEqualTo` compares with `equals`. In this test:
- `repeat.status()` is an `int`, and `isEqualTo(HttpStatus.CREATED)` expects 201.
- `bookingIdIn(repeat.body())` is a `String`, and `isEqualTo(first)` expects the second reply to name the same booking as the first.
- `repeat.header("Location")` is compared with the text built from `first`.
- `api.provider().chargesFor(holdId)` and `api.count(...)` are `long` numbers read from the stand-in provider's ledger and the database. `isEqualTo(1)` accepts the `int` literal 1 for a `long` value; AssertJ's number assertions compare by value.

The test proves "charged once" with the two counts, not with the replies.

### Text: `contains`, chained (57 calls)

```java
  @Test
  void namesEveryInvalidFieldInOneProblem() throws Exception {
    TestApi.Response reply =
        api.send(
            "POST", "/v1/holds", JSON, "{\"eventId\":0,\"seat\":\"Z-9\",\"customer\":\"a b\"}");

    assertThat(reply.body())
        .contains("\"pointer\":\"Idempotency-Key\"")
        .contains("\"pointer\":\"/eventId\"")
        .contains("\"pointer\":\"/seat\"")
        .contains("\"pointer\":\"/customer\"")
        .contains("\"instance\":\"urn:uuid:");
  }
```

`contains` on a `String` checks that the text includes the given piece. The calls are chained: the same body must include all four pieces. A failing `contains` prints the whole text and the missing piece. `startsWith` (5 calls), `endsWith` (1) and `doesNotContain` (2) are the neighbors.

### Numbers and flags: `isZero`, `isPositive`, `isLessThan`, `isTrue`, `isFalse`

```java
  @Test
  void neitherSkipsNorRepeatsASeatWhileFansHoldSeats() throws Exception {
    PageWalk.Report report = walk(new PageWalk.Changes(PAGE_SIZE, CHANGES_PER_PAGE, 0));

    assertThat(report.cursor().skipped()).isZero();
    assertThat(report.cursor().repeated()).isZero();
    // The same changes make the offset walk skip seats: each hold behind it moves the rest back.
    assertThat(report.offset().skipped()).isPositive();
  }
```

- `isZero()` (14 calls) checks that a number is 0. Here the cursor walk skipped no seat.
- `isPositive()` (3 calls) checks that a number is greater than 0. The comment says why the test expects it: the same changes make the offset walk skip seats, so its count must be above zero. If the offset walk ever stopped skipping, this test would fail and tell the reader that the lesson no longer holds.
- `isLessThan(...)` (1 call) and `isTrue()` / `isFalse()` (7 and 2 calls) are the other small checks. `isTrue()` appears for example on `Contract.judgeRequest(...).hasErrors()`: the test asserts that a request the contract forbids really gets an error from the validator.

### Collections: `hasSize`, `isEmpty`, `allSatisfy`, `anySatisfy`, `containsEntry`

```java
  @Test
  void holdsOnceWhenOneHundredRetriesWithOneKeyRaceEachOther() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, attempt -> () -> api.hold("A-1", "racing-retries"));

    assertThat(replies)
        .allSatisfy(reply -> assertThat(reply.status()).isEqualTo(HttpStatus.CREATED));
    assertThat(replies.stream().map(reply -> holdIdIn(reply.body())).distinct()).hasSize(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isEqualTo(1);
  }

  @Test
  void letsOneOfOneHundredFansWithTheirOwnKeysHoldTheSeat() throws Exception {
    List<TestApi.Response> replies =
        AllAtOnce.run(CONCURRENT_RETRIES, fan -> () -> api.hold("A-1", "fan-" + fan));

    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CREATED)).hasSize(1);
    assertThat(replies.stream().filter(reply -> reply.status() == HttpStatus.CONFLICT))
        .hasSize(CONCURRENT_RETRIES - 1);
    assertThat(api.count(LIVE_HOLDS_OF_SEAT, "A-1", TestApi.START.toEpochMilli())).isEqualTo(1);
  }
```

- `assertThat(replies).allSatisfy(reply -> ...)` (3 calls) runs the inner assertion on every element and fails on the first that does not hold. A lambda `reply -> assertThat(...)` is written for one element.
- `assertThat(replies.stream().map(...).distinct()).hasSize(1)` (6 `hasSize` calls) turns a stream of 100 hold ids into a list of the distinct ones and expects exactly one. AssertJ accepts a `Stream` and reads it into a list.
- `.filter(reply -> reply.status() == HttpStatus.CREATED)` keeps the replies with status 201. In the second test exactly one of the 100 fans got 201 and the other 99 got 409.
- `isEmpty()` (24 calls) checks that a text, list or `Optional` has nothing in it. For example `assertThat(ContractRules.check(document)).isEmpty()` expects no problem lines.
- `anySatisfy(...)` (9 calls) expects at least one element to satisfy the inner assertion. It reads naturally in `BreakingChangeTest`: `anySatisfy(change -> assertThat(change).contains("field state was removed"))` says "some line of the report says that".
- `containsEntry(key, value)` (2 calls) checks one pair of a `Map`. `RetryStormTest` uses it for the table of status counts: `containsEntry(HttpStatus.CREATED, 1)`.

### Exceptions: `assertThatThrownBy` and `isInstanceOf`

```java
  @Test
  void refusesAnUnquotedEmptyTooLongOrEscapedKey() {
    assertThat(IdempotencyKey.fromHeader("8e03978e")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"" + "k".repeat(LONGEST_KEY + 1) + "\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader("\"a\\\"b\"")).isEmpty();
    assertThat(IdempotencyKey.fromHeader(null)).isEmpty();
    assertThatThrownBy(() -> new IdempotencyKey("has space"))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`assertThatThrownBy(() -> new IdempotencyKey("has space"))` runs the constructor and catches what it throws. `.isInstanceOf(IllegalArgumentException.class)` (9 calls in all) checks the type. If nothing was thrown, the check fails with "Expecting code to raise a throwable". The same shape appears in `FingerprintTest`, `ApiSettingsTest` and `DatabaseTest` (there the thrown type is `SQLException`, from a database that refuses an unknown seat status).

Also in this file: `assertThat(IdempotencyKey.fromHeader("8e03978e")).isEmpty()`. `fromHeader` returns an `Optional`, and `isEmpty()` on an `Optional` checks that it holds nothing. The first test in the class uses `.contains(new IdempotencyKey("..."))`, which on an `Optional` checks the content.

### A description of your own: `as`

```java
  /**
   * Sends a request and checks the reply against the contract.
   *
   * @param method the method
   * @param pathAndQuery the path, with a query if any
   * @param headers request headers
   * @param body the body, or an empty string
   * @return the reply, already judged by the contract
   * @throws Exception if the exchange fails
   */
  public Response send(String method, String pathAndQuery, Map<String, String> headers, String body)
      throws Exception {
    Response response = sendUnchecked(method, pathAndQuery, headers, body);
    String path = URI.create(pathAndQuery).getPath();
    ValidationReport report =
        Contract.judgeReply(method, path, response.status(), response.headers(), response.body());
    assertThat(report.getMessages())
        .as(
            "the contract's judgment of %s %s -> %d %s",
            method, path, response.status(), response.body())
        .isEmpty();
    return response;
  }
```

`.as("the contract's judgment of %s %s -> %d %s", method, path, response.status(), response.body())` (5 calls in all) adds a sentence to the failure message. `%s` and `%d` are slots that are filled with the values after the sentence. It must come **before** the check that may fail, because a message set afterwards is too late. Without it, a failure would only say that a list was not empty; with it, the failure names the request and the reply that broke the contract. `report.getMessages()` is the list of messages from the contract validator, and `isEmpty()` demands that it has none (`openapi-validator-and-diff.md`).

### A type check with extra conditions: `isInstanceOfSatisfying`

```java
  @Test
  void listsEveryMissingKeyInOneRun() {
    Result<ApiSettings, List<String>> settings =
        ApiSettings.from(new SettingsReader(new Properties()));

    assertThat(settings)
        .isInstanceOfSatisfying(
            Result.Error.class,
            error ->
                assertThat(error.toString())
                    .contains("http.port is missing")
                    .contains("payment.url is missing")
                    .contains("version0.sunsetAt is missing"));
  }
```

`isInstanceOfSatisfying(Result.Error.class, error -> ...)` (1 call) first checks that `settings` is an instance of the given type, then runs the inner assertion with the value already cast to that type. It is the safe form of "check the type, then look inside". `Result` is the library type from `result4j.md`.

### `isIn`

```java
    // Each racer either gets the booking or is told the first attempt is still paying
    // (409).
    assertThat(replies)
        .allSatisfy(
            reply -> assertThat(reply.status()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
```

`isIn(HttpStatus.CREATED, HttpStatus.CONFLICT)` (1 call) accepts a value that equals any of the listed ones. Here each of 50 racing confirms either gets the booking (201) or is told the first attempt is still paying (409).

### The build wiring

`testImplementation(libs.assertj.core)` makes AssertJ available to the test code only. Every test imports `assertThat` with `import static`, as `junit.md` explains.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Count the `assertThat(` calls:

```
grep -rhoE "assertThat\(" src/test | wc -l
```

   Output: `223`.

2. Count the `assertThatThrownBy(` calls:

```
grep -rhoE "assertThatThrownBy\(" src/test | wc -l
```

   Output: `7`.

3. See which assertion methods are used and how often:

```
grep -rhoE "\.(isEqualTo|contains|isEmpty|isZero|isInstanceOf|anySatisfy|isTrue|hasSize)\(" src/test | sort | uniq -c | sort -rn
```

   Output: `123 .isEqualTo(`, `57 .contains(`, `24 .isEmpty(`, `14 .isZero(`, `9 .isInstanceOf(`, `9 .anySatisfy(`, `7 .isTrue(` and `6 .hasSize(`.

4. Watch a failure message. In a scratch copy, change `isEqualTo(HttpStatus.CREATED)` in the first test of `ConfirmPaymentTest` to `isEqualTo(HttpStatus.OK)` and run:

```
./gradlew --offline test --tests '*ConfirmPaymentTest.confirmsOnceAndChargesOnceWhenTheConfirmIsRepeated'
```

   Output: `ConfirmPaymentTest > confirmsOnceAndChargesOnceWhenTheConfirmIsRepeated() FAILED` and `org.opentest4j.AssertionFailedError at ConfirmPaymentTest.java:51`. Gradle does not print the message itself. It is in the result file, `build/test-results/test/TEST-io.plainround.seatwave.api.booking.ConfirmPaymentTest.xml`, as `expected: 200` and ` but was: 201` (AssertJ's own wording). Put the line back afterwards.

5. List the files that use `assertThatThrownBy`:

```
grep -rl "assertThatThrownBy" src/test
```

   Output: four files, `FingerprintTest.java`, `IdempotencyKeyTest.java`, `ApiSettingsTest.java` and `DatabaseTest.java`.

## Mistakes people make

- **Swapping the two sides.** `assertThat(actual).isEqualTo(expected)`: the value you test goes inside `assertThat`. Swapped, the message calls the wrong one "expected".
- **A check that is built but not finished.** `assertThat(x);` with no check after it does nothing. Always end with a method such as `isEqualTo`.
- **Putting `.as(...)` after the check.** The description is read when the check fails, so it must come first.
- **Testing a type too loosely.** `isInstanceOf(Exception.class)` would pass for almost anything. The tests here name the exact type.
- **Using `==` on strings in a check.** `isEqualTo` compares content, as it should.
- **Checking a list with `get(0)`.** `containsExactly`, `hasSize` and `anySatisfy` say what you mean and print the whole list when they fail.

## Check yourself

1. *What does `assertThat(actual)` return?* An assertion object for the type of `actual`, with the methods that suit that type.
2. *Which method checks that a number is exactly zero?* `isZero()`.
3. *What does `allSatisfy` do?* It runs an inner assertion on every element of a collection.
4. *How do you check that code throws an `IllegalArgumentException`?* `assertThatThrownBy(() -> code).isInstanceOf(IllegalArgumentException.class)`.
5. *Why put `.as("...")` before the check?* So the sentence is part of the message when the check fails.

## Further reading

- AssertJ Core 3.27.7 documentation: <https://assertj.github.io/doc/>
- AssertJ Core API (Javadoc, 3.27.7): <https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.7/index.html>
