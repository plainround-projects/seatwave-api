# The OpenAPI validator 3.0.0 and openapi-diff 2.1.7 (the contract test libraries)

## What it is

An **API contract** is a document that says exactly what a service accepts and what it answers. This repository's contract is `openapi/seatwave-api.yaml`, written in **OpenAPI 3.1**: a standard format, in YAML, that lists every **path** (`/v1/holds`), every **operation** on it (`POST`, `GET`), the **parameters** and **headers** each takes, the **body** it expects and, for each **status code** (`201`, `409`), the **headers** and **body** it may answer with. The shape of a body is written as a **schema** in JSON Schema: "this is an object; these fields are required; `eventId` is an integer of at least 1".

Two libraries make the contract executable. Both are **test-only** libraries (`testImplementation` in `build.gradle.kts`):

1. **The validator**, `com.atlassian.oai:openapi-request-validator-core` version **3.0.0**. You give it the contract once. Then you hand it one request or one reply, and it answers with a **report** of everything in that exchange the contract does not allow. To **validate a reply against a contract** means this: take the real status code, headers and body that the running service sent, look up what the contract says for that path, method and status, and check each part. A report with no messages means the contract allows the reply.
2. **openapi-diff**, `org.openapitools.openapidiff:openapi-diff-core` version **2.1.7**. You give it two contracts, the **released** one and a **candidate**. It tells you what changed and whether the change is **compatible**.

A **breaking change** is a change to a contract after which a client written against the old contract can fail. A client is a program, often on a phone you cannot update, that sends requests and reads replies the way the old contract said. Examples: removing a field the client reads, making a new field required in a request the client does not know to send, removing an operation. An **additive change** adds something optional (a new optional response field, a new operation) and breaks nobody. Version numbers follow the same idea: the repository releases `1.0.0`, and a compatible change moves to `1.1.0`.

A third library does the reading: the **Swagger Parser** (`io.swagger.parser.v3:swagger-parser` 2.1.41), which turns the YAML text into Java objects (`OpenAPI`, `PathItem`, `Operation`, `Schema`). It arrives with the validator and the diff as a transitive dependency (`dependency-locking.md`), and the repository's own checks (`ContractRules`, `BreakingChanges`) use it directly.

## Why this repository uses it

The lesson of Module 7 is that the **contract is the source of truth** and the code follows it. If the contract were only a document, it would drift: someone changes the code and forgets the document. These libraries make drift fail the build:
- Every reply of the running service in every test is judged against the document (the validator). A reply the document does not allow fails the test that caused it.
- Every change to the document is compared with the last released one (the diff and the house rules). A change that would break a released client fails `BreakingChangeTest`.

The README says: "`./gradlew check` fails when the document is invalid, when a change would break a client of the released version, and when any reply of the running service does not match the document."

## Where it appears

| Path | What it does with the libraries |
| --- | --- |
| `gradle/libs.versions.toml` | `openapiValidator = "3.0.0"`, `openapiDiff = "2.1.7"` and the entries `openapi-validator` and `openapi-diff`. |
| `build.gradle.kts` | `testImplementation(libs.openapi.validator)` and `testImplementation(libs.openapi.diff)`; the `lintContract` and `diffContract` tasks; the constraints that raise Guava and Commons Configuration. |
| `src/test/java/.../Contract.java` | Builds the validator once and offers `judgeReply` and `judgeRequest`. |
| `src/test/java/.../TestApi.java` | Passes every reply of `send(...)` through `Contract.judgeReply` and fails on any message. |
| `src/test/java/.../contract/OpenApiDocumentTest.java`, `ContractConformanceTest.java` | Use `Contract` directly to show what a bad reply or bad request looks like. |
| `src/test/java/.../contract/DiffContractCli.java` | Calls openapi-diff and prints its verdict next to the house rules'. |
| `src/test/java/.../contract/BreakingChangeTest.java` | Asserts compatibility with both the diff and the house rules. |
| `src/test/java/.../contract/ContractRules.java`, `BreakingChanges.java`, `LintContractCli.java` | The repository's own rules, built on the Swagger Parser. |
| `openapi/` | The documents that are read: `seatwave-api.yaml`, `released/seatwave-api-1.0.0.yaml`, and four examples. |

## How it works underneath

1. The contract is plain text on disk. `Contract` reads it with `Files.readString` once, when the class loads, and hands the text to the validator. The validator parses it, resolves every `$ref` (a pointer such as `#/components/schemas/Hold` that means "use that definition here") and keeps a ready model.
2. To judge a reply, the test builds a small description of it (`SimpleResponse`: status, headers, body text) and calls `validateResponse(path, method, response)`.
3. The validator finds the operation for that path and method. If there is none, that is a message. It finds the response entry for the status code. If the contract lists none for that code and has no `default`, that is a message. Then it checks the media type of the body against the media types listed for that status, and the body against the schema. It also checks that headers the contract marks required are present.
4. The result is a **report**: a list of messages, each with a level (`ERROR`, `WARN`, `INFO`), a key (a stable code such as `validation.response.status.unknown`) and a text. `hasErrors()` is true when any message has the level `ERROR`.
5. The diff works on two whole documents. It parses both, pairs up the operations by path and method, and compares parameters, request bodies and responses, classifying each difference as one of `NO_CHANGES`, `METADATA` (text only), `COMPATIBLE`, `UNKNOWN` or `INCOMPATIBLE`. `isCompatible()` on the whole result is the library's overall answer; it is false when a difference is classed `INCOMPATIBLE`.
6. The house rules in `BreakingChanges` walk the same two parsed documents with a few rules the repository states in plain Java, small enough to read.

Nothing here touches the network. Both libraries read text from memory. The Swagger Parser behind them can fetch remote references, but this repository gives both libraries only text and local paths.

## Walkthrough of this repository's use

### Building the validator, once

```java
public final class Contract {

  /** The path of the contract, set by the build for the test task. */
  public static final Path DOCUMENT =
      Path.of(System.getProperty("seatwave.contract", "openapi/seatwave-api.yaml"));

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForInlineApiSpecification(text(DOCUMENT)).build();

  private Contract() {}
```

- `DOCUMENT` is the path of the contract. `System.getProperty("seatwave.contract", "openapi/seatwave-api.yaml")` reads a system property that `build.gradle.kts` sets in the test task (`gradle.md`), and falls back to the relative path when the class is run another way.
- `OpenApiInteractionValidator.createForInlineApiSpecification(text(DOCUMENT)).build()` creates the validator. "Inline" means: the argument is the **text** of the specification, not an address. `.build()` finishes the builder. Because the field is `static final`, the document is parsed once for the whole test run, not once per test.
- The class Javadoc says why that is safe: the validator is built once and only read.

### Judging a reply

```java
  public static ValidationReport judgeReply(
      String method, String path, int status, Map<String, String> headers, String body) {
    SimpleResponse.Builder response = SimpleResponse.Builder.status(status);
    headers.forEach(response::withHeader);
    if (!body.isEmpty()) {
      response.withBody(body);
    }
    return VALIDATOR.validateResponse(
        path, Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), response.build());
  }
```

- `SimpleResponse.Builder.status(status)` starts a description of a reply with its status code.
- `headers.forEach(response::withHeader)` adds every reply header. `response::withHeader` is a **method reference**, short for "call `withHeader` with each name and value".
- `response.withBody(body)` adds the body text, but only when there is one (some replies, such as 204, have none).
- `VALIDATOR.validateResponse(path, Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), response.build())` asks the question. The path is the path without the query string. The method is turned into the library's enum (`GET`, `POST`, `DELETE`).
- The return value is a `ValidationReport`.

### Judging a request

```java
  public static ValidationReport judgeRequest(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      String body) {
    SimpleRequest.Builder request =
        new SimpleRequest.Builder(Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), path);
    query.forEach(request::withQueryParam);
    headers.forEach(request::withHeader);
    if (!body.isEmpty()) {
      request.withBody(body);
    }
    return VALIDATOR.validateRequest(request.build());
  }
```

The same idea for a request: `SimpleRequest.Builder` takes the method and path, then query parameters, headers and a body, and `validateRequest(...)` returns a report. The tests use it to prove that the contract itself **forbids** a request before they send it to the server (see below).

### Every reply in every test is judged

```java
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

`TestApi.send` sends the request, then passes the real status, headers and body to `Contract.judgeReply`. `assertThat(report.getMessages()).as(...).isEmpty()` fails the test on any message, including warnings. Because every test uses `send` (or the helpers `hold` and `confirm` built on it), every `send` call across the tests is a conformance check. Only `sendUnchecked` skips the judgment, for paths the contract does not describe (`/v2/events`, `/events` of version 0).

### What the messages look like (I ran these)

I ran the validator in a scratch test on the same calls that `OpenApiDocumentTest` and `ContractConformanceTest` make. The reports were:

| Call | Level | Key | Message |
| --- | --- | --- | --- |
| Reply 200 for `GET /v1/events` whose items have no `startsAt` | `ERROR` | `validation.response.body.schema.required` | `required property 'startsAt' not found` |
| Reply 409 for `POST /v1/holds` with `Content-Type: application/json` and body `{"error":"seat not available"}` | `ERROR` | `validation.response.contentType.notAllowed` | `Response Content-Type header 'application/json' does not match any allowed types. Must be one of: [application/problem+json].` |
| Reply 418 for `GET /v1/events` | `ERROR` | `validation.response.status.unknown` | `Response status 418 not defined for path '/v1/events'.` |
| Request `GET /v1/events?limit=501` | `ERROR` | `validation.request.parameter.schema.maximum` | `must have a maximum value of 5E+2` |
| Request `POST /v1/holds` without `Idempotency-Key` | `ERROR` | `validation.request.parameter.header.missing` | `Header parameter 'Idempotency-Key' is required on path '/v1/holds' but not found in request.` |
| Request `POST /v1/holds` with the extra field `price` | `ERROR` | `validation.request.body.schema.additionalProperties` | `property 'price' is not defined in the schema and the schema does not allow additional properties` |
| Request `GET /v1/events?limit=50` | none | none | The report was empty, `hasErrors()` false. |

The tests assert on the message text: `contains("startsAt")` and `contains("418 not defined")` (the second one matches the words "418 not defined" in "Response status 418 not defined for path").

### The contract test in `OpenApiDocumentTest`

```java
  @Test
  void catchesAReplyThatBreaksTheContract() {
    // What a regression would look like: the build fails on each of these.
    String withoutStart = "{\"items\":[{\"eventId\":1,\"name\":\"Arena Night\"}]}";
    String plainError = "{\"error\":\"seat not available\"}";

    assertThat(
            Contract.judgeReply(
                    "GET",
                    "/v1/events",
                    HttpStatus.OK,
                    Map.of("Content-Type", "application/json"),
                    withoutStart)
                .getMessages())
        .anySatisfy(message -> assertThat(message.getMessage()).contains("startsAt"));
    assertThat(
            Contract.judgeReply(
                    "POST",
                    "/v1/holds",
                    HttpStatus.CONFLICT,
                    Map.of("Content-Type", "application/json"),
                    plainError)
                .hasErrors())
        .isTrue();
    assertThat(
            Contract.judgeReply(
                    "GET",
                    "/v1/events",
                    UNDOCUMENTED_STATUS,
                    Map.of("Content-Type", "application/json"),
                    "{}")
                .getMessages())
        .anySatisfy(message -> assertThat(message.getMessage()).contains("418 not defined"));
  }
```

Three bad replies, each of a different kind: a 200 whose items lack a required field, a 409 sent as plain `application/json` with an error shape the contract does not allow, and a status code the contract never mentions. The test passes when the validator **complains** about each one, which proves the validator would catch the same mistake in the service. A judge that never fails would make every other test worthless; this test is the test of the judge.

### The forbidden requests in `ContractConformanceTest`

```java
    for (Forbidden request : forbidden) {
      assertThat(
              Contract.judgeRequest(
                      request.method(),
                      request.path(),
                      request.query(),
                      request.headers(),
                      request.body())
                  .hasErrors())
          .as("the contract forbids %s", request)
          .isTrue();
      TestApi.Response reply =
          api.send(request.method(), pathWithQuery(request), request.headers(), request.body());
      assertThat(reply.status()).as("the server refuses %s", request).isEqualTo(request.status());
      assertThat(reply.header("Content-Type")).isEqualTo("application/problem+json");
    }
    assertThat(api.count("SELECT COUNT(*) FROM idempotency_keys")).isZero();
  }
```

For each of 14 requests, the test first asks the validator whether the contract forbids the request (`hasErrors()` must be true), and then sends it to the real server, which must refuse it with the status the table says and the media type `application/problem+json`. So the **server** is held to the **contract's** rules. The last line checks that none of the 14 refusals left a row in the key table.

### The comparison with openapi-diff

```java
  public static ChangedOpenApi compare(Path released, Path candidate) {
    return OpenApiCompare.fromContents(Contract.text(released), Contract.text(candidate));
  }

  private static String words(DiffResult result) {
    return result.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
```

`OpenApiCompare.fromContents(String, String)` parses the two documents and returns a `ChangedOpenApi`, the whole difference. The method is `public static` because both the command and `BreakingChangeTest` call it.

```java
    ChangedOpenApi diff = compare(Path.of(arguments[0]), Path.of(arguments[1]));
    System.out.printf(Locale.ROOT, "released:  %s%ncandidate: %s%n", arguments[0], arguments[1]);
    System.out.printf(
        Locale.ROOT,
        "operations: %d new, %d removed, %d changed%n",
        diff.getNewEndpoints().size(),
        diff.getMissingEndpoints().size(),
        diff.getChangedOperations().size());
    diff.getMissingEndpoints()
        .forEach(endpoint ->
            System.out.printf(
                Locale.ROOT, "  removed  %s %s%n", endpoint.getMethod(), endpoint.getPathUrl()));
    for (ChangedOperation operation : diff.getChangedOperations()) {
      System.out.printf(
          Locale.ROOT,
          "  %-12s %-6s %-28s request %-12s responses %s%n",
          words(operation.isCoreChanged()),
          operation.getHttpMethod(),
          operation.getPathUrl(),
          words(operation.resultRequestBody()),
          words(operation.resultApiResponses()));
    }
    System.out.println("openapi-diff: " + (diff.isCompatible() ? "compatible" : "breaking"));
```

- `diff.getNewEndpoints()`, `diff.getMissingEndpoints()` and `diff.getChangedOperations()` are lists. The first line of output counts them: `operations: 0 new, 0 removed, 1 changed`.
- For every removed operation the command prints `removed  METHOD path`.
- For every changed operation it prints a row: `operation.isCoreChanged()` (a `DiffResult` for the operation's own attributes), `getHttpMethod()`, `getPathUrl()`, `resultRequestBody()` (the verdict on the request body) and `resultApiResponses()` (the verdict on the replies). The `words(...)` helper at the end of the file turns the enum name into lower-case words, so `INCOMPATIBLE` prints as `incompatible` and `NO_CHANGES` as `no changes`.
- `diff.isCompatible()` is the library's overall answer, printed as `openapi-diff: compatible` or `openapi-diff: breaking`.

I ran the command three times:

| Candidate | `operations` line | `openapi-diff` | `house rules` | Verdict |
| --- | --- | --- | --- | --- |
| `openapi/seatwave-api.yaml` | `0 new, 0 removed, 0 changed` | compatible | compatible | compatible, every released client still works |
| `openapi/examples/seatwave-api-1.1.0-additive.yaml` (adds an optional `venue` field to the response) | `0 new, 0 removed, 0 changed` | compatible | compatible | compatible |
| `openapi/examples/seatwave-api-1.1.0-breaking.yaml` (renames response field `state` to `status` and makes request field `channel` required) | `0 new, 0 removed, 1 changed` | breaking | 3 breaking change(s) | BREAKING, released clients would fail |

For the breaking example, the library listed one changed operation, `POST /v1/holds`, with the request verdict `incompatible` and the response verdict `no changes`. The renamed response field of `GET /v1/events/{eventId}/seats` was **not** in the library's list: the three lines the house rules printed (`field state is no longer always present`, `field state was removed`, `field channel is newly required`) are what reported it. I did not investigate why openapi-diff does not list the seats operation. It is the reason the repository runs both checks, and the test requires both.

### The extra breaking-change rules the tests add

```java
/**
 * The house breaking-change check, next to openapi-diff: a candidate contract breaks a released one
 * when a client written against the released one could fail. The rules are the ones Module 7
 * teaches, small enough to read:
 *
 * <ul>
 *   <li>no operation and no documented status code disappears;
 *   <li>no response field disappears, changes type or stops being required, and no response gains
 *       an allowed value (a client that switches over the known values has no branch for it);
 *   <li>no request field disappears (a client sends it, and unknown fields are refused) or changes
 *       type, no new request field or parameter is required, and no allowed value is removed.
 * </ul>
 *
 * <p>A removed response value is not on the list: a client never fails because a value stops
 * arriving. Each media type is compared with the same media type of the candidate, by name.
 */
public final class BreakingChanges {
```

The class Javadoc lists the rules. They are the ones Module 7 teaches. Written as a table, with the line of code that implements each and the test that exercises it:

| Rule | Message the code prints | Where | Exercised by |
| --- | --- | --- | --- |
| An operation disappears | `GET /v1/bookings/{bookingId}: the operation was removed` | `find`, line 58 | `refusesARemovedOperationAndARemovedAllowedValue` |
| A documented status code disappears | `POST /v1/holds: response 422 was removed` | `compare`, line 98 | `refusesARemovedStatusARemovedRequestFieldAndANewRequiredParameter` |
| A response field or the response type changes or disappears | `field state was removed`, `type changed from X to Y` | `compareResponse`, lines 111 to 135 | `refusesARenamedResponseFieldAndANewRequiredRequestField` |
| A response field stops being required (a client can no longer count on it) | `field state is no longer always present` | `compareResponse`, line 117 | same test (the rename removes `state` from `required`) |
| A response gains an allowed value (a client that switches over the known values has no branch for it) | `the value sold is new, so clients may not know it` | `compareResponse`, line 123 | `refusesANewResponseValueButAcceptsARemovedOne` |
| A new required request field | `field channel is newly required` | `compareRequest`, line 149 | same test as the rename |
| A request field disappears or changes type (clients that send it would be refused, because unknown fields are refused) | `field customer was removed, so clients that send it fail` | `compareRequest`, line 161 | `refusesARemovedStatusARemovedRequestFieldAndANewRequiredParameter` |
| A new required parameter | `new required parameter X-Client` | `compare`, line 72 | the same test |
| A value is removed from a request's allowed set (an `enum`) | `the value held is no longer allowed` | `compareRequest`, line 154 | `refusesARemovedOperationAndARemovedAllowedValue` |

Two choices in the list are deliberate. A value removed from a **response** enum is not breaking: a client never fails because a value stops arriving, and openapi-diff judges it the same way (`refusesANewResponseValueButAcceptsARemovedOne` checks both). And every schema is compared media type by media type, by name (`compareContent`), so a reply that lists two media types is never judged by whichever one the document happens to list first (`comparesEachMediaTypeWithTheSameOneByName`).

`BreakingChangeTest` also runs the rules on the current contract against the released one and on the additive example, and expects an empty list both times. The full walkthrough of the file is in `tests-contract-walkthrough.md`.

### The parse-then-check rules in `ContractRules`

```java
  public static List<String> check(String text) {
    Objects.requireNonNull(text, "text");
    ParseOptions options = new ParseOptions();
    // Inline every reference, so each rule sees the final shape a client sees.
    options.setResolveFully(true);
    SwaggerParseResult parsed = new OpenAPIParser().readContents(text, null, options);
    List<String> problems = new ArrayList<>();
    Optional.ofNullable(parsed.getMessages())
        .ifPresent(messages -> messages.forEach(message -> problems.add("parser: " + message)));
    OpenAPI document = parsed.getOpenAPI();
    if (document == null) {
      problems.add("parser: the document could not be read at all");
      return problems;
    }
    if (document.getOpenapi() == null || !document.getOpenapi().startsWith("3.1.")) {
      problems.add("rule: the document must be OpenAPI 3.1, but says " + document.getOpenapi());
    }
    Set<String> operationIds = new HashSet<>();
    Optional.ofNullable(document.getPaths())
        .ifPresent(paths ->
            paths.forEach((path, item) ->
                item.readOperationsMap()
                    .forEach((method, operation) ->
                        checkOperation(path, method, operation, operationIds, problems))));
    return problems;
  }
```

`ContractRules.check` is the **lint**: checks on one document, without a released one. It reads the text with the Swagger Parser (`new OpenAPIParser().readContents(text, null, options)`) with `setResolveFully(true)`, which means every `$ref` is replaced by the definition it points at, so each rule sees the final shape a client sees. Parser messages come first, then the house rules: the document must be OpenAPI 3.1, and every operation must be checked by `checkOperation`. The rules there are: a unique `operationId`; every 4xx and 5xx response is `application/problem+json` with `type`, `title` and `status` required; every `POST` with a body requires an `Idempotency-Key` header; every `GET` that returns a page has a `limit` with a maximum of at most 500 and a `cursor` parameter; and every name in a schema's `required` list is defined in its `properties` (the parser accepts a required name that is never defined, but a client cannot know what to send). Deprecated operations (version 0) are skipped on purpose.

## Try it yourself

All ran in a scratch copy on 11 November 2026. The contract tools exit with 3 when they find a problem, and Gradle then prints its own error; the lines of the tool come first.

1. Lint the current and the released contract:

```
./gradlew --offline -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml'
```

   Output: `openapi/seatwave-api.yaml: valid, every rule passed` and `openapi/released/seatwave-api-1.0.0.yaml: valid, every rule passed`.

2. Lint the deliberately broken example:

```
./gradlew --offline -q lintContract --args='openapi/examples/holds-entry-broken.yaml'
```

   Output: `openapi/examples/holds-entry-broken.yaml: 5 problem(s)`, then two lines starting `parser:` (a missing `Hold` schema, reported twice) and three starting `rule:` (`POST /v1/holds 409 must be application/problem+json Problem`, `POST /v1/holds creates something, so it must require Idempotency-Key` and `POST /v1/holds request: required field customer is not defined`), then Gradle's `finished with non-zero exit value 3`. Five lines for the draft's four mistakes.

3. Diff the released contract against the breaking example:

```
./gradlew --offline -q diffContract --args='openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-breaking.yaml'
```

   Output: the line `openapi-diff: breaking`, three house-rule lines and `verdict: BREAKING, released clients would fail`.

4. Diff the released contract against the additive example:

```
./gradlew --offline -q diffContract --args='openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-additive.yaml'
```

   Output: `operations: 0 new, 0 removed, 0 changed`, `openapi-diff: compatible`, `house rules: compatible` and `verdict: compatible, every released client still works`.

5. Run the contract tests only:

```
./gradlew --offline cleanTest test --tests '*contract*'
```

   Output: 18 `PASSED` lines: four from `BreakingChangeTest`, nine from `ContractConformanceTest` and five from `OpenApiDocumentTest`.

6. Break a reply on purpose, in a scratch copy: in `src/main/java`, change something the contract fixes (for example the `Content-Type` of a problem reply), run the tests, and read the failure: the test that sent the request fails with the sentence `the contract's judgment of POST /v1/holds -> 409 ...` followed by the validator's message. I did not run this one.

## Mistakes people make

- **Trusting a contract nobody checks.** Without these libraries the document and the code would drift apart.
- **Judging only the happy path.** The tests judge every reply, including the 4xx and 5xx ones, because the error shape is part of the contract.
- **A validator that cannot fail.** `catchesAReplyThatBreaksTheContract` proves the judge fails on three kinds of wrong reply.
- **Believing one tool is enough.** In the breaking example, openapi-diff did not list the seats operation. The house rules did. Both run, and both must pass.
- **Calling every change breaking, or none.** An optional new field is additive. A new required request field or a renamed response field is breaking.
- **Editing the released contract.** `openapi/released/seatwave-api-1.0.0.yaml` is the record of what clients have. A new version goes into a new file.
- **Forgetting that the diff compares files, not behavior.** A service that stopped sending a documented field would be caught by the validator, not by the diff.

## Check yourself

1. *What does it mean to validate a reply against a contract?* To check the real status code, headers and body against what the contract lists for that path, method and status.
2. *What is a breaking change?* A change after which a client written against the old contract can fail.
3. *Name one change that is breaking and one that is additive.* Breaking: making a request field required, renaming a response field, removing an operation. Additive: a new optional response field.
4. *Why are both openapi-diff and the house rules run?* In the breaking example the library did not list the seats operation, and the house rules reported the rename; each can miss what the other catches.
5. *Why do the validator and diff only appear on the test classpath?* The service never ships them; the tasks `lintContract` and `diffContract` run from the test classpath for the same reason.

## Further reading

- The OpenAPI Specification 3.1.x: <https://spec.openapis.org/oas/v3.1.0.html>
- JSON Schema (the schema language of OpenAPI 3.1): <https://json-schema.org/specification>
- Swagger Request Validator (the validator, 3.0.0): <https://bitbucket.org/atlassian/swagger-request-validator/src/master/>
- openapi-diff (2.1.7): <https://github.com/OpenAPITools/openapi-diff>
- Swagger Parser: <https://github.com/swagger-api/swagger-parser>
- Problem Details for HTTP APIs, RFC 9457: <https://www.rfc-editor.org/rfc/rfc9457>
