# Walkthrough of the `contract` test package

## What it is

The `contract` package holds the tests and tools that guard the OpenAPI contract. There are seven files in `src/test/java/io/plainround/seatwave/api/contract/`:

| File | Kind | What it does |
| --- | --- | --- |
| `ContractRules.java` | tool | The lint: parser checks plus the house rules on one document. |
| `LintContractCli.java` | tool | The `lintContract` command-line task: runs `ContractRules` on files. |
| `BreakingChanges.java` | tool | The house breaking-change check: released contract against a candidate. |
| `DiffContractCli.java` | tool | The `diffContract` task: openapi-diff plus the house rules, one verdict. |
| `OpenApiDocumentTest.java` | test | The document is valid; the code's limits and the document's agree; the validator catches bad replies. |
| `BreakingChangeTest.java` | test | The current contract breaks no client of the released one. |
| `ContractConformanceTest.java` | test | Real requests against the running service; every reply is judged by the document. |

The two libraries behind them (the validator and openapi-diff) are explained in `openapi-validator-and-diff.md`. This file goes through the code line by line. The helpers these files call (`Contract`, `TestApi`, `HttpStatus`) are in `tests-other-packages-walkthrough.md`.

## Why this repository uses it

Module 7 teaches that the contract comes first. These files are how the build enforces it: a document that breaks a house rule, a change that would break a released client, or a reply that does not match the document fails `./gradlew check`.

## Where it appears

All paths are under `src/test/java/io/plainround/seatwave/api/contract/`. The tools are run by the Gradle tasks `lintContract` and `diffContract` (`gradle.md`). The tests are run by `./gradlew test`.

## How it works underneath

- The tools are ordinary Java classes in the **test** source set, because the OpenAPI libraries are test libraries. The Gradle tasks run them from the test runtime classpath.
- The tests run in JUnit (`junit.md`) and check with AssertJ (`assertj.md`).
- The tests read the contract path from a system property that `build.gradle.kts` sets, so tests and clients read the same file.
- A few Java words used all through this file. A **record** is a short class that only holds values and gets `equals`, `hashCode` and accessor methods for free. A **lambda** (`x -> x + 1`) is a small unnamed function. An **`Optional`** is a box that holds one value or nothing. A **stream** is a chain of steps over a list (`filter`, `map`, `anyMatch`). `java.md` explains each in full.

## Walkthrough of this repository's use

### `ContractRules.java`

#### Imports and the class header

```java
package io.plainround.seatwave.api.contract;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The checks a SeatWave contract must pass before any code is written: the OpenAPI parser's own
 * validation (structure, types, references), then the house rules this module teaches: errors are
 * problem details, writes require an Idempotency-Key, pages have a ceiling and a cursor, and every
 * required field is defined. Used by the build's tests and by the {@code lintContract} task.
 */
public final class ContractRules {

  /** The largest page any collection may promise (the contract's Limit maximum). */
  public static final int MAX_PAGE_LIMIT = 500;

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final int FIRST_ERROR_STATUS = 400;

  private ContractRules() {}
```

- Lines 3 to 12 import the Swagger Parser and its object model. `OpenAPIParser` reads text. `OpenAPI` is the whole document as an object. `Operation` is one method on one path. `PathItem` is one path with its methods. `Content` is the map from media type to schema, `Schema` is a JSON Schema, `Parameter` is a query or header parameter and `ApiResponse` is one entry under `responses`. `ParseOptions` holds the reader's settings and `SwaggerParseResult` is what it returns.
- Lines 13 to 20 import ordinary JDK classes: `BigDecimal` (exact decimal numbers), lists, sets, maps, `Objects` and `Optional`.
- The Javadoc (lines 22 to 27) is the class's manual. It names the house rules: errors are problem details, writes require an `Idempotency-Key`, pages have a ceiling and a cursor, and every required field is defined. `{@code lintContract}` marks a code word. It says the class is used both by the build's tests and by the `lintContract` task.
- `public final class ContractRules` is a class nobody can extend. `private ContractRules() {}` (line 36) is a constructor nobody can call: the class only has static methods, so no instance is ever made.
- `MAX_PAGE_LIMIT = 500` is the largest page any collection may promise. A test compares it with the code's own constant (`OpenApiDocumentTest.publishesTheSameLimitsTheCodeEnforces`).
- `PROBLEM_JSON` is the media type of every error body (RFC 9457). `FIRST_ERROR_STATUS = 400` is the first status number that counts as an error.

#### `check`: parse the document, then apply the rules

```java
  /**
   * Checks one contract document.
   *
   * @param text the document, YAML or JSON
   * @return one line per problem; empty when the document passes
   */
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

- The method takes the document text and returns a `List<String>`, one line per problem. An empty list means the document passes.
- `Objects.requireNonNull(text, "text")` throws a clear error if the caller passes `null`.
- `new ParseOptions()` creates the reader settings. `options.setResolveFully(true)` makes the parser replace every `$ref` with the definition it points at. The comment on line 46 gives the reason: each rule then sees the final shape a client sees.
- `new OpenAPIParser().readContents(text, null, options)` reads the document. The second argument (`null`) is a list of authorization values for remote files, and there are none.
- `Optional.ofNullable(parsed.getMessages()).ifPresent(messages -> messages.forEach(message -> problems.add("parser: " + message)))` copies the parser's own complaints into the list with the prefix `parser: `. `Optional.ofNullable` wraps a value that may be `null`; `ifPresent` runs the lambda only when it is there.
- `parsed.getOpenAPI()` is the document object. If it is `null` the text could not be read at all, so the method adds that message and returns early.
- Line 58 checks the version: `document.getOpenapi()` is the text after `openapi:` at the top of the file. It must start with `3.1.`. If it is missing or says something else, the problem names what it found.
- `Set<String> operationIds = new HashSet<>()` remembers the operation ids seen so far. A set refuses duplicates, which is how the next method tests uniqueness.
- Lines 62 to 67 go through every path and every method on it. `item.readOperationsMap()` returns a map from the method (`GET`, `POST`) to its `Operation`. For each pair, `checkOperation` runs. The nesting of lambdas is `paths.forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> checkOperation(...)))`.

#### `checkOperation`: the rules for one operation

```java
  private static void checkOperation(
      String path,
      PathItem.HttpMethod method,
      Operation operation,
      Set<String> operationIds,
      List<String> problems) {
    String name = method + " " + path;
    if (operation.getOperationId() == null || !operationIds.add(operation.getOperationId())) {
      problems.add("rule: " + name + " needs a unique operationId");
    }
    if (Boolean.TRUE.equals(operation.getDeprecated())) {
      // Version 0 keeps its old shapes on purpose; the rules below are for what is current.
      return;
    }
    Map<String, ApiResponse> responses =
        Optional.ofNullable(operation.getResponses()).map(Map::copyOf).orElse(Map.of());
    responses.forEach((status, response) -> {
      boolean isError =
          status.matches("[0-9]{3}") && Integer.parseInt(status) >= FIRST_ERROR_STATUS;
      if (isError && !isProblem(response.getContent())) {
        problems.add("rule: " + name + " " + status + " must be application/problem+json Problem");
      }
    });
    if (method == PathItem.HttpMethod.POST
        && operation.getRequestBody() != null
        && !requiresHeader(operation, "Idempotency-Key")) {
      problems.add("rule: " + name + " creates something, so it must require Idempotency-Key");
    }
    if (method == PathItem.HttpMethod.GET && returnsPage(responses)) {
      checkPagination(name, operation, problems);
    }
    Optional.ofNullable(operation.getRequestBody())
        .ifPresent(body -> checkRequiredFields(name + " request", body.getContent(), problems));
    responses.forEach((status, response) ->
        checkRequiredFields(name + " " + status, response.getContent(), problems));
  }

  private static void checkRequiredFields(String where, Content content, List<String> problems) {
    Optional.ofNullable(content)
        .ifPresent(mediaTypes ->
            mediaTypes
                .values()
                .forEach(mediaType -> checkRequiredFields(where, mediaType.getSchema(), problems)));
  }

  private static void checkRequiredFields(String where, Schema<?> schema, List<String> problems) {
    if (schema == null) {
      return;
    }
    // The parser accepts a required name with no definition; a client cannot know what to send.
    Set<String> defined =
        schema.getProperties() == null ? Set.of() : schema.getProperties().keySet();
    for (String required : Objects.requireNonNullElse(schema.getRequired(), List.<String>of())) {
      if (!defined.contains(required)) {
        problems.add("rule: " + where + ": required field " + required + " is not defined");
      }
    }
    if (schema.getProperties() != null) {
      schema
          .getProperties()
          .forEach(
              (field, property) -> checkRequiredFields(where + "." + field, property, problems));
    }
    checkRequiredFields(where + "[]", schema.getItems(), problems);
  }
```

- `String name = method + " " + path` builds a label such as `POST /v1/holds`, used in every message.
- Rule 1, line 78: `operation.getOperationId() == null || !operationIds.add(operation.getOperationId())`. A set's `add` returns `false` when the id was already there. So the message `needs a unique operationId` appears when there is no id or when it repeats.
- Lines 81 to 84: `Boolean.TRUE.equals(operation.getDeprecated())` is the null-safe way to ask "is it deprecated?" (the field may be `null`). A deprecated operation is a version 0 path that keeps its old shapes on purpose (the comment on line 82), so the remaining rules are skipped with `return`.
- Lines 85 to 86 copy the responses into a map that cannot be changed: `Optional.ofNullable(operation.getResponses()).map(Map::copyOf).orElse(Map.of())`.
- Lines 87 to 93: for each response, `status.matches("[0-9]{3}")` is true when the key is a three-digit number (a key such as `default` is not), and `Integer.parseInt(status) >= FIRST_ERROR_STATUS` is true for 400 and above. Rule 2: every error response must be a problem (`isProblem`), or the message `must be application/problem+json Problem` appears.
- Lines 94 to 98, Rule 3: a `POST` that has a request body must require the `Idempotency-Key` header, or the message says `creates something, so it must require Idempotency-Key`.
- Lines 99 to 101, Rule 4: a `GET` that returns a page (`returnsPage`) is checked by `checkPagination`.
- Lines 102 to 105, Rule 5: the request body and every response are handed to `checkRequiredFields`, with a label such as `POST /v1/holds request` or `POST /v1/holds 201`.
- The first `checkRequiredFields` takes a `Content` (the map from media type to schema). If there is one, it checks the schema of each media type.
- The second `checkRequiredFields` takes one schema. A `null` schema (for example a reference to a schema that does not exist, which the parser has already reported) is skipped. `defined` is the set of names in `properties`, or an empty set when there are none. Every name in `required` must be in that set, or the message is `required field NAME is not defined`. The comment says why the rule exists: the parser accepts a required name with no definition, but a client cannot know what to send. The method then calls itself on each property and on the array element schema (`getItems()`, label `[]`), so nested objects are checked too. This is the rule that finds the fourth mistake of the Lab 1 draft.

#### `checkPagination`, `returnsPage`

```java
  private static void checkPagination(String name, Operation operation, List<String> problems) {
    Optional<Parameter> limit = parameter(operation, "limit");
    BigDecimal maximum =
        limit.map(Parameter::getSchema).map(schema -> schema.getMaximum()).orElse(BigDecimal.ZERO);
    if (limit.isEmpty() || maximum.signum() <= 0 || maximum.intValue() > MAX_PAGE_LIMIT) {
      problems.add("rule: " + name + " returns a page, so limit needs a maximum of at most 500");
    }
    if (parameter(operation, "cursor").isEmpty()) {
      problems.add("rule: " + name + " returns a page, so it needs a cursor parameter");
    }
  }

  private static boolean returnsPage(Map<String, ApiResponse> responses) {
    return Optional.ofNullable(responses.get("200"))
        .map(ApiResponse::getContent)
        .map(content -> content.get("application/json"))
        .map(mediaType -> mediaType.getSchema())
        .map(
            schema -> schema.getProperties() != null && schema.getProperties().containsKey("items"))
        .orElse(false);
  }
```

- `parameter(operation, "limit")` finds the query parameter called `limit`. `limit.map(Parameter::getSchema).map(schema -> schema.getMaximum()).orElse(BigDecimal.ZERO)` reads its schema's `maximum`, or `0` when there is none. `Parameter::getSchema` is a method reference.
- Line 141: the rule fails when there is no `limit`, or its maximum is zero or below (`maximum.signum() <= 0`; `signum` is -1, 0 or 1), or it is above 500. The message says `limit needs a maximum of at most 500`.
- Lines 144 to 146: a page also needs a `cursor` query parameter.
- `returnsPage` answers "does the `200` reply have a JSON object with a property called `items`?" by walking down `responses.get("200")`, its `content`, the `application/json` entry, its `schema`, and the schema's `properties`. At any missing step the `Optional` turns into `false`. That is how the rule finds the operations that return a page without a list of names.

#### `isProblem`, `requiresHeader`, `parameter`

```java
  private static boolean isProblem(Content content) {
    if (content == null || content.size() != 1 || !content.containsKey(PROBLEM_JSON)) {
      return false;
    }
    Schema<?> schema = content.get(PROBLEM_JSON).getSchema();
    List<String> required =
        schema == null ? List.of() : Objects.requireNonNullElse(schema.getRequired(), List.of());
    return required.containsAll(List.of("type", "title", "status"));
  }

  private static boolean requiresHeader(Operation operation, String header) {
    return Objects.requireNonNullElse(operation.getParameters(), List.<Parameter>of()).stream()
        .anyMatch(parameter ->
            "header".equals(parameter.getIn())
                && header.equalsIgnoreCase(parameter.getName())
                && Boolean.TRUE.equals(parameter.getRequired()));
  }

  private static Optional<Parameter> parameter(Operation operation, String name) {
    return Objects.requireNonNullElse(operation.getParameters(), List.<Parameter>of()).stream()
        .filter(parameter -> "query".equals(parameter.getIn()) && name.equals(parameter.getName()))
        .findFirst();
  }
}
```

- `isProblem` is true only when the response has exactly one media type, `application/problem+json`, and the schema of that body lists `type`, `title` and `status` as required. `Schema<?>` is a schema of any kind (the `?` is a wildcard). `Objects.requireNonNullElse(schema.getRequired(), List.of())` turns a missing list into an empty one. `required.containsAll(List.of("type", "title", "status"))` is the final test.
- `requiresHeader` looks through the operation's parameters for one whose `in` is `header`, whose name equals the given name ignoring case (header names are case-insensitive), and which is marked `required`. `List.<Parameter>of()` is an empty list with its type spelled out for the compiler. `.stream().anyMatch(...)` is true when at least one parameter fits.
- `parameter` finds the first query parameter with a given name, as an `Optional`.

### `LintContractCli.java`

```java
package io.plainround.seatwave.api.contract;

import io.plainround.seatwave.api.Contract;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code lintContract} task: checks contract files and prints every problem. Exit status 0
 * means every file passed, 3 means a file has problems, 2 bad usage. It runs from the test
 * classpath, where the OpenAPI parser lives, so the service never ships it.
 */
public final class LintContractCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_PROBLEMS = 3;

  private LintContractCli() {}

  /**
   * Checks the files named on the command line.
   *
   * @param arguments one or more contract files
   */
  public static void main(String[] arguments) {
    if (arguments.length == 0) {
      System.err.println("usage: lintContract <openapi.yaml> [more files]");
      System.exit(EXIT_BAD_USAGE);
    }
    boolean isClean = true;
    for (String file : arguments) {
      List<String> problems = ContractRules.check(Contract.text(Path.of(file)));
      System.out.println(
          file
              + ": "
              + (problems.isEmpty()
                  ? "valid, every rule passed"
                  : problems.size() + " problem(s)"));
      problems.forEach(problem -> System.out.println("  " + problem));
      isClean &= problems.isEmpty();
    }
    System.exit(isClean ? 0 : EXIT_PROBLEMS);
  }
}
```

- The class has a `main` method, the place where a Java program starts. The Javadoc states the exit codes: 0 means every file passed, 3 means a file has problems, 2 means bad usage.
- `arguments` are the words after the program name. With no argument the command prints the usage line to standard error (`System.err`) and exits with 2.
- For each file name, `Contract.text(Path.of(file))` reads the file, `ContractRules.check(...)` returns the problems, and one line is printed: the file name, a colon, and either `valid, every rule passed` or `N problem(s)`. The `? :` is the conditional operator: `a ? b : c` means "if a then b else c". Then every problem is printed with two spaces in front.
- `isClean &= problems.isEmpty()` keeps `isClean` true only while every file so far was clean (`&=` is "and, then store").
- `System.exit(isClean ? 0 : EXIT_PROBLEMS)` ends the program with the code. Gradle shows a non-zero code as a failure (`gradle.md`). Real output: `openapi/seatwave-api.yaml: valid, every rule passed`.

### `BreakingChanges.java`

#### Header and the rules in the Javadoc

```java
package io.plainround.seatwave.api.contract;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

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

  private BreakingChanges() {}
```

- The imports are the same Swagger Parser classes as in `ContractRules`, plus `Content` (a `content` map), `MediaType` (one entry of it) and `BiConsumer` (a function that takes two values and returns nothing).
- The Javadoc names the three rule groups: operations and status codes must not disappear; response fields must not disappear, change type or stop being required, and a response must not gain an allowed value; request fields must not disappear or change type, no new request field or parameter may be required, and no allowed value may be removed. The reasons are in the text: a client that switches over the values it knows has no branch for a new one; a client sends the old field, and the service refuses unknown fields.
- The last paragraph states two choices. A value removed from a response is not breaking, because a client never fails when a value stops arriving. And each media type is compared with the media type of the same name in the candidate.
- `<ul>` and `<li>` are HTML list tags that Javadoc turns into a bulleted list.

#### `find`: walk the released document

```java
  /**
   * Lists every breaking change.
   *
   * @param released the contract clients use now
   * @param candidate the proposed next one
   * @return one line per breaking change; empty when every released client still works
   */
  public static List<String> find(String released, String candidate) {
    OpenAPI before = parse(released);
    OpenAPI after = parse(candidate);
    List<String> breaks = new ArrayList<>();
    before
        .getPaths()
        .forEach((path, item) ->
            item.readOperationsMap()
                .forEach((method, operation) -> {
                  Optional<Operation> next = operation(after, path, method);
                  if (next.isEmpty()) {
                    breaks.add(method + " " + path + ": the operation was removed");
                  } else {
                    compare(method + " " + path, operation, next.get(), breaks);
                  }
                }));
    return breaks;
  }
```

- `find(released, candidate)` returns a list of breaking changes, empty when every released client still works.
- Both texts are parsed into `OpenAPI` objects by `parse` (below). `before` is the released one, `after` the candidate.
- For each path and each method in the **released** document, `operation(after, path, method)` looks for the same operation in the candidate. If it is missing, the message `the operation was removed` is added. If it is there, `compare` looks at the two operations. Only operations that existed before matter: a new operation in the candidate breaks nobody, so it is never looked at.

#### `compare`: one operation, old against new

```java
  private static void compare(String name, Operation before, Operation after, List<String> breaks) {
    for (Parameter parameter : listOf(after.getParameters())) {
      boolean wasThere =
          listOf(before.getParameters()).stream()
              .anyMatch(old -> old.getName().equals(parameter.getName()));
      if (Boolean.TRUE.equals(parameter.getRequired()) && !wasThere) {
        breaks.add(name + ": new required parameter " + parameter.getName());
      }
    }
    for (Parameter parameter : listOf(before.getParameters())) {
      listOf(after.getParameters()).stream()
          .filter(next -> next.getName().equals(parameter.getName()))
          .findFirst()
          .ifPresent(next ->
              compareRequest(
                  name + " parameter " + parameter.getName(),
                  parameter.getSchema(),
                  next.getSchema(),
                  breaks));
    }
```

- First loop (lines 67 to 74): for each parameter of the candidate, `wasThere` is true if a parameter of the same name existed before. A **required** parameter that is new breaks old clients, which never send it. The message is `new required parameter NAME`.
- Second loop (lines 75 to 85): for each old parameter, find the candidate's parameter with the same name (`findFirst`) and, if it exists, compare the two schemas as a **request** (`compareRequest`), because a client sends parameters. The label passed in names the parameter: `GET /v1/events/{eventId}/seats parameter state`.

```java
    if (before.getRequestBody() != null && after.getRequestBody() != null) {
      compareContent(
          before.getRequestBody().getContent(),
          after.getRequestBody().getContent(),
          (oldSchema, newSchema) ->
              compareRequest(name + " request", oldSchema, newSchema, breaks));
    }
```

- The request body: when both operations have one, `compareContent` (in the helpers) pairs each media type of the old body with the media type of the same name in the new one, and calls the function it is given on each pair of schemas. The function is a **lambda**, `(oldSchema, newSchema) -> compareRequest(name + " request", oldSchema, newSchema, breaks)`: a short unnamed function, here one that compares two request schemas. If the candidate has no body, nothing is compared (that case, a removed body, is not a rule in this file).

```java
    Map<String, ApiResponse> newResponses = mapOf(after.getResponses());
    mapOf(before.getResponses())
        .forEach((status, response) -> {
          ApiResponse next = newResponses.get(status);
          if (next == null) {
            breaks.add(name + ": response " + status + " was removed");
            return;
          }
          compareContent(
              response.getContent(),
              next.getContent(),
              (oldSchema, newSchema) ->
                  compareResponse(name + " " + status, oldSchema, newSchema, breaks));
        });
  }
```

- The responses: `newResponses` is the candidate's map from status code to response (`mapOf` makes a missing map empty).
- For each status code the released document documents: if the candidate does not, the message is `response STATUS was removed`. Otherwise `compareContent` pairs the media types by name, and `compareResponse` compares each pair of schemas. The `return` inside the lambda leaves only the current iteration.

#### `compareResponse`: what a client reads

```java
  private static void compareResponse(
      String where, Schema<?> before, Schema<?> after, List<String> breaks) {
    if (!Objects.equals(typeOf(before), typeOf(after))) {
      breaks.add(where + ": type changed from " + typeOf(before) + " to " + typeOf(after));
      return;
    }
    for (String required : listOf(before.getRequired())) {
      if (!listOf(after.getRequired()).contains(required)) {
        breaks.add(where + ": field " + required + " is no longer always present");
      }
    }
    if (before.getEnum() != null) {
      for (Object allowed : listOf(after.getEnum())) {
        if (!before.getEnum().contains(allowed)) {
          breaks.add(where + ": the value " + allowed + " is new, so clients may not know it");
        }
      }
    }
    mapOf(before.getProperties())
        .forEach((field, schema) -> {
          Schema<?> next = mapOf(after.getProperties()).get(field);
          if (next == null) {
            breaks.add(where + ": field " + field + " was removed");
          } else {
            compareResponse(where + "." + field, schema, next, breaks);
          }
        });
    if (before.getItems() != null && after.getItems() != null) {
      compareResponse(where + "[]", before.getItems(), after.getItems(), breaks);
    }
  }
```

- `typeOf` (below) gives the type name of each schema. If they differ (an integer became a string), the message is `type changed from X to Y` and the method stops: nothing below makes sense.
- For every field that was `required` in the old schema, the candidate must still list it as required. A client may rely on a field always being present, so `field NAME is no longer always present` is breaking.
- If the old schema lists allowed values (an `enum`), every value of the candidate's enum must be one of them, or the message is `the value VALUE is new, so clients may not know it`. A client written against the old list may have a branch for each value it knows and none for a new one. The opposite direction is fine for a response: a value that stops arriving never makes a client fail, so it is not checked.
- For every old property: if the candidate no longer has it, the message is `field NAME was removed`. If it has, the method calls itself on the field's schema with a longer label (`where + "." + field`). That is **recursion**: the same check goes down through nested objects.
- Lines 136 to 138 do the same for arrays: `getItems()` is the schema of one array element, and the label gets `[]` (`200.items[]`).

#### `compareRequest`: what a client sends

```java
  private static void compareRequest(
      String where, Schema<?> before, Schema<?> after, List<String> breaks) {
    if (!Objects.equals(typeOf(before), typeOf(after))) {
      breaks.add(where + ": type changed from " + typeOf(before) + " to " + typeOf(after));
      return;
    }
    for (String required : listOf(after.getRequired())) {
      if (!listOf(before.getRequired()).contains(required)) {
        breaks.add(where + ": field " + required + " is newly required");
      }
    }
    for (Object allowed : listOf(before.getEnum())) {
      if (after.getEnum() != null && !after.getEnum().contains(allowed)) {
        breaks.add(where + ": the value " + allowed + " is no longer allowed");
      }
    }
    mapOf(before.getProperties())
        .forEach((field, schema) -> {
          Schema<?> next = mapOf(after.getProperties()).get(field);
          if (next == null) {
            breaks.add(where + ": field " + field + " was removed, so clients that send it fail");
          } else {
            compareRequest(where + "." + field, schema, next, breaks);
          }
        });
  }
```

- A changed type is breaking here as well.
- For every field the **candidate** requires that the released schema did not, the message is `field NAME is newly required`. This is the mirror image of the response rule: clients send what the old document said, so a new mandatory field makes their requests invalid.
- Lines 152 to 156: for every allowed value in the old `enum`, if the candidate has an enum and it no longer lists the value, the message is `the value VALUE is no longer allowed`. `Object allowed` is used because an enum list can hold any kind of value.
- Lines 157 to 165: every old field must still exist, or `field NAME was removed, so clients that send it fail`. The explanation in the message is the key fact of this API: the service refuses unknown fields, so a client that still sends the old field would be rejected. If the field exists, the check recurses into it.

#### Helpers

```java
  private static Optional<Operation> operation(
      OpenAPI document, String path, PathItem.HttpMethod method) {
    return Optional.ofNullable(document.getPaths().get(path))
        .map(item -> item.readOperationsMap().get(method));
  }

  private static void compareContent(
      Content before, Content after, BiConsumer<Schema<?>, Schema<?>> comparison) {
    // A client reads the media type it asked for, so each one is compared with its namesake; a
    // reply with two media types must not be judged by whichever the document lists first.
    mapOf(before)
        .forEach((mediaType, released) -> {
          MediaType candidate = mapOf(after).get(mediaType);
          if (candidate != null && released.getSchema() != null && candidate.getSchema() != null) {
            comparison.accept(released.getSchema(), candidate.getSchema());
          }
        });
  }
```

- `operation(document, path, method)` returns the operation of the same path and method as an `Optional`, empty when the path or method is missing.
- `compareContent(before, after, comparison)` walks the released `content` map. For each media type (for example `application/json`), `mapOf(after).get(mediaType)` looks up the media type **of the same name** in the candidate. When both exist and both have a schema, `comparison.accept(old, new)` runs the comparison it was given. The comment says why the lookup is by name: a client reads the media type it asked for, and a reply with two media types must not be judged by whichever the document lists first. (An earlier version took the first entry of each map, so a candidate that put an unchanged media type first hid a change in `application/json`; `comparesEachMediaTypeWithTheSameOneByName` is that case.) A media type that only the candidate has is new and harmless, so it is not looked at.

```java
  private static String typeOf(Schema<?> schema) {
    // OpenAPI 3.1 writes the type as a set ("types"); 3.0 as one string ("type").
    return schema.getTypes() != null
        ? String.join(",", schema.getTypes())
        : String.valueOf(schema.getType());
  }

  private static OpenAPI parse(String text) {
    ParseOptions options = new ParseOptions();
    options.setResolveFully(true);
    OpenAPI document = new OpenAPIParser().readContents(text, null, options).getOpenAPI();
    if (document == null) {
      throw new IllegalArgumentException("not an OpenAPI document");
    }
    return document;
  }

  private static <T> List<T> listOf(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static <V> Map<String, V> mapOf(Map<String, V> values) {
    return values == null ? Map.of() : values;
  }
}
```

- `typeOf`: the comment says it. OpenAPI 3.1 writes a type as a set of names (`types`, for example `[string, "null"]`), OpenAPI 3.0 as one string (`type`). The method joins the set with commas, or falls back to `String.valueOf(schema.getType())`.
- `parse`: the same parse as in `ContractRules`, with `setResolveFully(true)`, but it **throws** `IllegalArgumentException("not an OpenAPI document")` when nothing could be read, because a breaking-change check on an unreadable document would be meaningless.
- `listOf` and `mapOf` turn a `null` list or map into an empty one, so the loops above need no null checks. `<T>` and `<V>` are type parameters: the helpers work for any element type.

### `BreakingChangeTest.java`

#### Setting up

```java
package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** No change to the contract may break a client of the released one: the build checks it. */
class BreakingChangeTest {

  private static final Path RELEASED =
      Path.of(
          System.getProperty(
              "seatwave.releasedContract", "openapi/released/seatwave-api-1.0.0.yaml"));

  @Test
  void keepsEveryClientOfTheReleasedContractWorking() {
    assertThat(DiffContractCli.compare(RELEASED, Contract.DOCUMENT).isCompatible()).isTrue();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(Contract.DOCUMENT)))
        .isEmpty();
  }
```

- The imports add `IOException`, `UncheckedIOException`, `StandardCharsets` and `Files` for the helper `writeTo` at the end, which writes a candidate made in memory to a file for openapi-diff.
- The class is package-private (`class BreakingChangeTest`, no `public`). The Javadoc is its one-sentence requirement.
- `RELEASED` is the path of the released contract. `System.getProperty("seatwave.releasedContract", "openapi/released/seatwave-api-1.0.0.yaml")` reads the property that `build.gradle.kts` sets, and has the same file as the fallback.
- Test 1 (`keepsEveryClientOfTheReleasedContractWorking`): the current contract (`Contract.DOCUMENT`) is compared with the released one by openapi-diff (`DiffContractCli.compare(...).isCompatible()` must be true) **and** by the house rules (`BreakingChanges.find(...)` must return an empty list). Two independent checks must both agree.

#### The additive case

```java
  @Test
  void acceptsAnAdditiveChange() {
    Path additive = Path.of("openapi/examples/seatwave-api-1.1.0-additive.yaml");

    assertThat(DiffContractCli.compare(RELEASED, additive).isCompatible()).isTrue();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(additive))).isEmpty();
  }
```

Test 2: `openapi/examples/seatwave-api-1.1.0-additive.yaml` adds an optional `venue` field to a response. Both checks must call it compatible. This keeps the checks honest in the other direction: a check that flags everything would also pass the breaking cases.

#### The breaking case

```java
  @Test
  void refusesARenamedResponseFieldAndANewRequiredRequestField() {
    Path breaking = Path.of("openapi/examples/seatwave-api-1.1.0-breaking.yaml");

    assertThat(DiffContractCli.compare(RELEASED, breaking).isCompatible()).isFalse();
    assertThat(BreakingChanges.find(Contract.text(RELEASED), Contract.text(breaking)))
        .anySatisfy(change -> assertThat(change).contains("field state was removed"))
        .anySatisfy(change -> assertThat(change).contains("field channel is newly required"));
  }
```

Test 3: the file `seatwave-api-1.1.0-breaking.yaml` renames the response field `state` to `status` and adds a required request field `channel`. The openapi-diff verdict must be **not** compatible (`isFalse()`), and the house rules must produce lines that contain `field state was removed` and `field channel is newly required`. `anySatisfy` says "at least one line of the list fits" (`assertj.md`).

#### A candidate made on the fly

```java
  @Test
  void refusesARemovedOperationAndARemovedAllowedValue() {
    String released = Contract.text(RELEASED);
    String candidate =
        released
            .replace("  /v1/bookings/{bookingId}:", "  /v1/bookings-by-id/{bookingId}:")
            .replace("enum: [available, held, sold]", "enum: [available, sold]");

    assertThat(BreakingChanges.find(released, candidate))
        .anySatisfy(change ->
            assertThat(change).contains("GET /v1/bookings/{bookingId}: the operation was removed"))
        .anySatisfy(change -> assertThat(change).contains("the value held is no longer allowed"));
  }
```

Test 4 builds a candidate from the released text in memory with `String.replace`:
- It renames the path `/v1/bookings/{bookingId}:` to `/v1/bookings-by-id/{bookingId}:`, which removes the old operation.
- It removes `held` from the enum of seat states, `enum: [available, held, sold]` to `enum: [available, sold]`.

`BreakingChanges.find(released, candidate)` must report `GET /v1/bookings/{bookingId}: the operation was removed` and `the value held is no longer allowed`. The `held` value is found through the `state` query parameter of the seats operation, which uses the enum as its schema. This test never calls openapi-diff: it checks the house rules only.

#### Three rules that had no test

```java
  @Test
  void refusesARemovedStatusARemovedRequestFieldAndANewRequiredParameter() {
    String released = Contract.text(RELEASED);
    String candidate =
        replaceOnce(
            replaceOnce(
                replaceOnce(
                    replaceOnce(
                        released,
                        "        '422':\n"
                            + "          $ref: '#/components/responses/KeyReused'\n"
                            + "        '503':\n"
                            + "          $ref: '#/components/responses/Unavailable'\n"
                            + "  /v1/holds/{holdId}:\n",
                        "        '503':\n"
                            + "          $ref: '#/components/responses/Unavailable'\n"
                            + "  /v1/holds/{holdId}:\n"),
                    "    HoldRequest:\n"
                        + "      type: object\n"
                        + "      additionalProperties: false\n"
                        + "      required: [eventId, seat, customer]\n",
                    "    HoldRequest:\n"
                        + "      type: object\n"
                        + "      additionalProperties: false\n"
                        + "      required: [eventId, seat]\n"),
                "        customer:\n"
                    + "          type: string\n"
                    + "          pattern: '^[A-Za-z0-9_-]{1,64}$'\n",
                ""),
            "      summary: Read a hold and its state.\n"
                + "      parameters:\n"
                + "        - $ref: '#/components/parameters/HoldId'\n",
            "      summary: Read a hold and its state.\n"
                + "      parameters:\n"
                + "        - $ref: '#/components/parameters/HoldId'\n"
                + "        - name: X-Client\n"
                + "          in: header\n"
                + "          required: true\n"
                + "          schema:\n"
                + "            type: string\n");

    assertThat(BreakingChanges.find(released, candidate))
        .contains(
            "POST /v1/holds: response 422 was removed",
            "POST /v1/holds request: field customer was removed, so clients that send it fail",
            "GET /v1/holds/{holdId}: new required parameter X-Client");
  }
```

Test 5 makes one candidate with four edits, each by `replaceOnce` (a helper below that also checks the text to replace is there exactly once). The calls are nested: the innermost edit runs first, and each result is the text of the next.
- It removes the `'422'` entry of `POST /v1/holds`, the lines just before the path `/v1/holds/{holdId}:`. Each string is one YAML line ending in `\n`, joined with `+`.
- It removes `customer` from the `required` list of `HoldRequest`, and then its property (an empty replacement deletes the three lines). The `HoldRequest:` lines are part of the text to replace because the line `required: [eventId, seat, customer]` also appears in the version 0 form body; `replaceOnce` would refuse an edit that is not unique.
- It adds a required header parameter, `X-Client`, to `GET /v1/holds/{holdId}`.

`.contains(...)` with three lines checks that the list holds all three messages, word for word: `response 422 was removed`, `field customer was removed, so clients that send it fail` and `new required parameter X-Client`. These are the three rules of the class Javadoc that no test exercised before.

#### Allowed values in a response, and media types by name

```java
  @Test
  void refusesANewResponseValueButAcceptsARemovedOne() {
    String released = Contract.text(RELEASED);
    String holdStates = "enum: [held, confirming, confirmed, cancelled, expired]";
    String added =
        replaceOnce(
            released, holdStates, "enum: [held, confirming, confirmed, cancelled, expired, sold]");
    String removed =
        replaceOnce(released, holdStates, "enum: [held, confirming, confirmed, cancelled]");

    assertThat(BreakingChanges.find(released, added))
        .anySatisfy(change -> assertThat(change).contains(".state: the value sold is new"));
    // A client never fails because a value stops arriving; openapi-diff agrees.
    assertThat(BreakingChanges.find(released, removed)).isEmpty();
    assertThat(DiffContractCli.compare(RELEASED, writeTo(removed)).isCompatible()).isTrue();
  }
```

Test 6 changes the allowed values of a hold's `state`, a field that only replies carry.
- `added` puts a new value, `sold`, in the list. The house rules must report `.state: the value sold is new`: a client that switches over the five known states has no branch for a sixth.
- `removed` takes `expired` out. The comment says why that is not breaking: a client never fails because a value stops arriving. The house rules must report nothing, and openapi-diff, which reads files, must call it compatible too (`writeTo` writes the text to a temporary file first).

```java
  @Test
  void comparesEachMediaTypeWithTheSameOneByName() {
    String released = Contract.text(RELEASED);
    // The candidate lists an unchanged media type first and drops fields from application/json.
    String candidate =
        replaceOnce(
            released,
            "          content:\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                $ref: '#/components/schemas/Hold'\n"
                + "        '400':\n"
                + "          $ref: '#/components/responses/BadRequest'\n"
                + "        '404':\n"
                + "          $ref: '#/components/responses/NotFound'\n"
                + "        '409':\n",
            "          content:\n"
                + "            application/vnd.seatwave+json:\n"
                + "              schema:\n"
                + "                $ref: '#/components/schemas/Hold'\n"
                + "            application/json:\n"
                + "              schema:\n"
                + "                type: object\n"
                + "                required: [holdId]\n"
                + "                properties:\n"
                + "                  holdId:\n"
                + "                    type: string\n"
                + "        '400':\n"
                + "          $ref: '#/components/responses/BadRequest'\n"
                + "        '404':\n"
                + "          $ref: '#/components/responses/NotFound'\n"
                + "        '409':\n");

    assertThat(BreakingChanges.find(released, candidate))
        .contains("POST /v1/holds 201: field eventId is no longer always present");
  }
```

Test 7: the `201` reply of `POST /v1/holds` gets a second media type, `application/vnd.seatwave+json`, listed **first** and unchanged, and its `application/json` schema loses every field but `holdId`. The comment states the setup. A check that compared only the first media type of each side would compare the released `application/json` with the unchanged `vnd` entry and see nothing. Comparing by name, the house rules must find that `eventId` (among others) is no longer always present.

#### The helpers

```java
  private static String replaceOnce(String text, String target, String replacement) {
    // A test edit that matches nothing would test the released document against itself.
    assertThat(text).containsOnlyOnce(target);
    return text.replace(target, replacement);
  }

  private static Path writeTo(String document) {
    try {
      Path file = Files.createTempFile("seatwave-candidate-", ".yaml");
      file.toFile().deleteOnExit();
      return Files.writeString(file, document, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
```

- `replaceOnce(text, target, replacement)` first asserts `containsOnlyOnce(target)`: the text to replace is there, exactly once. The comment says why: an edit that matched nothing would leave the released document unchanged, and the test would compare it with itself and prove nothing.
- `writeTo(document)` writes a candidate to a new temporary file (`Files.createTempFile`) in UTF-8, marks it to be deleted when the JVM ends (`deleteOnExit`), and returns its path, because `DiffContractCli.compare` takes paths. An `IOException` is a failure of the machine, not of the test's subject, so it is rethrown unchecked with the original as its cause.

### `OpenApiDocumentTest.java`

#### The document passes the checks

```java
package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The contract is checked before the code: if the document is invalid, the build fails here. */
class OpenApiDocumentTest {

  // 418 is a real status code (RFC 9110 reserves it) that the contract never mentions.
  private static final int UNDOCUMENTED_STATUS = 418;

  private final String document = Contract.text(Contract.DOCUMENT);

  @Test
  void passesTheParserAndEveryHouseRule() {
    assertThat(ContractRules.check(document)).isEmpty();
  }
```

- The Javadoc: "The contract is checked before the code: if the document is invalid, the build fails here."
- `UNDOCUMENTED_STATUS = 418` is a real HTTP status (RFC 9110 reserves it) that the contract never mentions. The comment explains the choice.
- `private final String document = Contract.text(Contract.DOCUMENT)` reads the contract once for each test object (JUnit creates one object per test).
- Test 1: `ContractRules.check(document)` must be empty: the parser accepts the document and every house rule passes.

#### The numbers in the document agree with the code

```java
  @Test
  void publishesTheSameLimitsTheCodeEnforces() {
    // The code's constants and the document's numbers must agree, or one of them lies.
    assertThat(document)
        .contains("maximum: " + ContractLimits.MAX_PAGE_LIMIT)
        .contains("default: " + ContractLimits.DEFAULT_PAGE_LIMIT)
        .contains("Hold one seat for " + ContractLimits.HOLD_DURATION.toSeconds() + " seconds")
        .contains("kept for " + ContractLimits.KEY_RETENTION.toHours() + " hours");
    assertThat(document)
        .contains(String.format(Locale.ROOT, "over %,d bytes", ContractLimits.MAX_BODY_BYTES));
    assertThat(ContractRules.MAX_PAGE_LIMIT).isEqualTo(ContractLimits.MAX_PAGE_LIMIT);
  }
```

The contract says things in prose and numbers: how big a page may be, how long a hold lasts, how long a key is kept, how large a body may be. The code has the same numbers as constants in `ContractLimits`. This test builds the sentence from the **constant** and looks for it in the **document**:
- `"maximum: " + ContractLimits.MAX_PAGE_LIMIT` finds `maximum: 500`. `default: ` finds the default page size.
- `"Hold one seat for " + ContractLimits.HOLD_DURATION.toSeconds() + " seconds"` finds the text of the hold operation.
- `"kept for " + ContractLimits.KEY_RETENTION.toHours() + " hours"` finds the key retention sentence.
- `String.format(Locale.ROOT, "over %,d bytes", ContractLimits.MAX_BODY_BYTES)` produces `over 1,024 bytes`; `%,d` writes the number with a thousands separator, and `Locale.ROOT` fixes the separator to a comma on every machine.
- The last line makes sure the lint's own constant (`ContractRules.MAX_PAGE_LIMIT`) and the code's (`ContractLimits.MAX_PAGE_LIMIT`) are equal. The comment says why: "The code's constants and the document's numbers must agree, or one of them lies."

#### The validator catches a bad reply

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

Three bad replies; the build must notice each. `Contract.judgeReply(method, path, status, headers, body)` returns a report (`openapi-validator-and-diff.md` shows the real messages).
1. A `200` for `GET /v1/events` whose item has no `startsAt`. The message must contain `startsAt` (`getMessages()` is the list; `anySatisfy` checks one of them).
2. A `409` for `POST /v1/holds` sent as plain `application/json` with an old error shape. `hasErrors()` must be true: the contract demands `application/problem+json`.
3. A `418` for `GET /v1/events`. The message must contain `418 not defined`.

#### The lint finds the mistakes in a broken example, and accepts the fixed one

```java
  @Test
  void findsTheMistakesInABrokenHoldsEntry() {
    String broken = Contract.text(Path.of("openapi/examples/holds-entry-broken.yaml"));

    assertThat(ContractRules.check(broken))
        .anySatisfy(problem -> assertThat(problem).contains("Idempotency-Key"))
        .anySatisfy(problem -> assertThat(problem).contains("409 must be application/problem+json"))
        .anySatisfy(problem -> assertThat(problem).startsWith("parser:"))
        .anySatisfy(problem ->
            assertThat(problem)
                .isEqualTo("rule: POST /v1/holds request: required field customer is not defined"));
  }

  @Test
  void acceptsTheLabSolutionForTheHoldsEntry() {
    assertThat(ContractRules.check(Contract.text(Path.of("openapi/examples/holds-entry.yaml"))))
        .isEmpty();
  }
}
```

- `openapi/examples/holds-entry-broken.yaml` is a lab file with four deliberate mistakes. The lint must say that `Idempotency-Key` is missing, that the 409 is not a problem body, that the parser itself complained (a line that starts with `parser:`, for the missing `Hold` schema), and, word for word, that `customer` is required but never defined: `rule: POST /v1/holds request: required field customer is not defined`.
- `openapi/examples/holds-entry.yaml` is the lab solution. The lint must return an empty list.

### `DiffContractCli.java`

#### Header and `main`

```java
package io.plainround.seatwave.api.contract;

import io.plainround.seatwave.api.Contract;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.DiffResult;

/**
 * The {@code diffContract} task: compares a released contract with a candidate and says whether
 * every client of the released one still works. Exit status 0 means compatible, 3 means a breaking
 * change, 2 bad usage.
 */
public final class DiffContractCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_BREAKING = 3;

  private DiffContractCli() {}

  /**
   * Compares two contract files.
   *
   * @param arguments the released file, then the candidate
   */
  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      System.err.println("usage: diffContract <released.yaml> <candidate.yaml>");
      System.exit(EXIT_BAD_USAGE);
    }
```

- The imports bring in openapi-diff: `OpenApiCompare` (the entry point), `ChangedOpenApi` (the result), `ChangedOperation` (one changed operation) and `DiffResult` (the verdict enum).
- The Javadoc gives the exit codes: 0 compatible, 3 breaking change, 2 bad usage.
- `main` requires exactly two arguments (`arguments.length != 2`) and prints `usage: diffContract <released.yaml> <candidate.yaml>` to standard error otherwise.

#### Running the diff and printing it

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

- `compare(Path.of(arguments[0]), Path.of(arguments[1]))` runs the library (below).
- `System.out.printf(Locale.ROOT, "released:  %s%ncandidate: %s%n", ...)` prints two lines. `printf` fills the `%s` slots with the arguments and `%n` ends a line. `Locale.ROOT` keeps number and text formatting the same on every machine.
- The next `printf` prints `operations: N new, N removed, N changed` with `%d` slots for whole numbers.
- `getMissingEndpoints()` are operations that existed and are gone. For each, `removed  METHOD path` is printed.
- For each changed operation one row is printed with `%-12s %-6s %-28s`: the minus sign means left-aligned, and the number is the column width. This is why the real output lines up in columns.
- `diff.isCompatible() ? "compatible" : "breaking"` prints the library's verdict.

#### The house rules and the combined verdict

```java
    List<String> breaks =
        BreakingChanges.find(
            Contract.text(Path.of(arguments[0])), Contract.text(Path.of(arguments[1])));
    System.out.println(
        "house rules: "
            + (breaks.isEmpty() ? "compatible" : breaks.size() + " breaking change(s)"));
    breaks.forEach(change -> System.out.println("  " + change));
    boolean isCompatible = diff.isCompatible() && breaks.isEmpty();
    System.out.println(
        isCompatible
            ? "verdict: compatible, every released client still works"
            : "verdict: BREAKING, released clients would fail");
    System.exit(isCompatible ? 0 : EXIT_BREAKING);
  }
```

- `BreakingChanges.find(...)` runs the house rules on the same two files, read with `Contract.text`.
- `house rules: compatible` or `house rules: N breaking change(s)` follows, then each change is printed with two spaces in front.
- `boolean isCompatible = diff.isCompatible() && breaks.isEmpty()`: the combined verdict is compatible only when **both** agree. The final line is `verdict: compatible, every released client still works` or `verdict: BREAKING, released clients would fail`.
- `System.exit(isCompatible ? 0 : EXIT_BREAKING)` ends the program with 0 or 3.

#### `compare` and `words`

```java
  /**
   * Compares two contract files.
   *
   * @param released the contract clients use now
   * @param candidate the proposed next contract
   * @return the difference
   */
  public static ChangedOpenApi compare(Path released, Path candidate) {
    return OpenApiCompare.fromContents(Contract.text(released), Contract.text(candidate));
  }

  private static String words(DiffResult result) {
    return result.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
```

- `compare` is `public static` because `BreakingChangeTest` calls it too. `OpenApiCompare.fromContents(Contract.text(released), Contract.text(candidate))` reads both files and compares them.
- `words` turns the enum constant `NO_CHANGES` into `no changes` (`name()`, lower case, underscore replaced by a space).

### `ContractConformanceTest.java`

#### Imports, constants, lifecycle and the table record

```java
package io.plainround.seatwave.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.api.Contract;
import io.plainround.seatwave.api.ContractLimits;
import io.plainround.seatwave.api.HttpStatus;
import io.plainround.seatwave.api.TestApi;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract test: real requests against the running service, every reply judged against the
 * OpenAPI document (status code, required fields, the error body's shape, the media type). Every
 * call below goes through {@link TestApi#send}, which fails the test on any mismatch.
 */
class ContractConformanceTest {

  private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");
  private static final Map<String, String> NO_HEADERS = Map.of();

  private TestApi api;

  @BeforeEach
  void startApi() throws Exception {
    api = TestApi.start();
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }

  /** One request the contract forbids, and the status the server must answer it with. */
  private record Forbidden(
      String method,
      String path,
      Map<String, String> query,
      Map<String, String> headers,
      String body,
      int status) {}
```

- The Javadoc describes the test: real requests against the running service, every reply judged against the OpenAPI document (status code, required fields, the error body's shape, the media type). "Every call below goes through `TestApi#send`, which fails the test on any mismatch."
- `JSON` is the header map for a JSON body; `NO_HEADERS` is an empty map.
- `@BeforeEach startApi()` starts a new API with `TestApi.start()`. `@AfterEach stopApi()` closes it (`junit.md`).
- `private record Forbidden(...)` declares a record with six fields: the method, path, query parameters, headers, body and the status the server must answer with. It is a row of a table of test cases, nested in the test class and private to it.

#### Test 1: the server refuses what the contract forbids

```java
  @Test
  void refusesEveryRequestTheContractForbidsBeforeDoingAnyWork() throws Exception {
    List<Forbidden> forbidden =
        List.of(
            new Forbidden(
                "GET", "/v1/events", Map.of("limit", "0"), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events",
                Map.of("limit", "501"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events",
                Map.of("cursor", "not a cursor!"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET", "/v1/events/0/seats", Map.of(), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events/1/seats",
                Map.of("section", "Z"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET",
                "/v1/events/1/seats",
                Map.of("state", "free"),
                NO_HEADERS,
                "",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "GET", "/v1/holds/not-a-uuid", Map.of(), NO_HEADERS, "", HttpStatus.BAD_REQUEST),
            new Forbidden(
```

The list holds 14 forbidden requests (lines 50 to 134). The first group, lines 52 to 85, is about reads:

| # | Request | Why the contract forbids it |
| --- | --- | --- |
| 1 | `GET /v1/events?limit=0` | `limit` has a minimum of 1. |
| 2 | `GET /v1/events?limit=501` | `limit` has a maximum of 500. |
| 3 | `GET /v1/events?cursor=not a cursor!` | A cursor must match `^[A-Za-z0-9_-]{1,200}$`; spaces and `!` are outside that. |
| 4 | `GET /v1/events/0/seats` | An event id starts at 1. |
| 5 | `GET /v1/events/1/seats?section=Z` | Sections are the letters A to T. |
| 6 | `GET /v1/events/1/seats?state=free` | `state` is one of `available`, `held`, `sold`. |
| 7 | `GET /v1/holds/not-a-uuid` | A hold id is a UUID. |

Each `new Forbidden("GET", "/v1/events", Map.of("limit", "0"), NO_HEADERS, "", HttpStatus.BAD_REQUEST)` reads: method, path, query, headers, body, expected status. The formatter (`spotless-google-java-format.md`) puts a short call on one line and splits a long one into one argument per line, which is why some entries are three lines and some are eight.

```java
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                JSON,
                TestApi.holdBody("A-1", "fan_1"),
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-1"),
                "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":\"fan_1\",\"price\":0}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-2"),
                "{\"eventId\":-1,\"seat\":\"A-0\",\"customer\":\"\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                Map.of("Content-Type", "application/json", "Idempotency-Key", "no-quotes"),
                TestApi.holdBody("A-1", "fan_1"),
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-4"),
                "{\"eventId\":\"1\",\"seat\":\"A-1\",\"customer\":\"fan_1\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/holds",
                Map.of(),
                TestApi.jsonWithKey("k-5"),
                "{\"eventId\":1.5,\"seat\":\"A-1\",\"customer\":\"fan_1\"}",
                HttpStatus.BAD_REQUEST),
            new Forbidden(
                "POST",
                "/v1/bookings",
                Map.of(),
                TestApi.jsonWithKey("k-3"),
                "{\"holdId\":\"NOT-A-UUID\"}",
                HttpStatus.BAD_REQUEST));
```

The second group is about writes:

| # | Request | Why the contract forbids it |
| --- | --- | --- |
| 8 | `POST /v1/holds` with a body and no `Idempotency-Key` | The key is required. |
| 9 | `POST /v1/holds` with a valid key and the extra field `"price":0` | The service refuses unknown fields: the client does not set prices. |
| 10 | A body with `"eventId":-1`, `"seat":"A-0"`, `"customer":""` | Three invalid fields at once. |
| 11 | The key `no-quotes` | The contract requires a quoted string, and `no-quotes` is not quoted. |
| 12 | `"eventId":"1"` (a string) | The id must be an integer, and no coercion is done. |
| 13 | `"eventId":1.5` | A fractional number is not an integer. |
| 14 | `POST /v1/bookings` with `{"holdId":"NOT-A-UUID"}` | A hold id is a UUID. |

All 14 expect `HttpStatus.BAD_REQUEST` (400). `TestApi.jsonWithKey("k-1")` builds the content-type and key headers, with the quotes the contract demands.

#### Test 1, the loop that checks both sides

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

For each row:
- `Contract.judgeRequest(...)` asks the validator whether the **contract** forbids it. `.hasErrors()` must be true. `.as("the contract forbids %s", request)` puts the row in the failure message. This proves the table is right: a row the contract allows would fail here, so the table cannot drift from the document.
- `api.send(...)` sends the request to the **server** through `TestApi`, which also judges the reply against the contract. `pathWithQuery(request)` builds the URL (below).
- The status must equal the row's, and the reply's `Content-Type` must be `application/problem+json`.
- After the loop, `api.count("SELECT COUNT(*) FROM idempotency_keys")` must be zero (`isZero()`): none of the 14 refusals stored a key. The server rejects before doing any work.

#### Test 2: every invalid field in one problem

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

A single request with no key and three bad fields (`eventId` 0, `seat` `Z-9`, `customer` `a b`) gets one problem body whose `errors` list names **all four**. The test checks that the body contains `"pointer":"Idempotency-Key"`, `"pointer":"/eventId"`, `"pointer":"/seat"` and `"pointer":"/customer"`, and `"instance":"urn:uuid:` (the id of this occurrence of the problem). A **pointer** says where the fault is: a header name, or a JSON Pointer such as `/seat`.

#### Test 3: a number where the contract wants text

```java
  @Test
  void refusesANumberSentForTheCustomerAndHoldsNothing() throws Exception {
    TestApi.Response reply =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("number-customer"),
            "{\"eventId\":1,\"seat\":\"A-1\",\"customer\":5}");

    assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(reply.body())
        .contains("{\"pointer\":\"/customer\",\"detail\":\"has the wrong type\"}");
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isZero();
  }
```

A hold whose `customer` is the number `5` instead of a string. The contract says `customer` is a string, so the reply must be `400`, and its `errors` list must hold exactly `{"pointer":"/customer","detail":"has the wrong type"}`. The last line asks the database directly: no seat may be `HELD`, because a refused request does no work. (Before the fix in `JsonCodec`, this request was answered `201` and the hold was stored for the customer `"5"`.)

#### Test 4: a bad name and a bad value in one reply

```java
  @Test
  void reportsAnUnknownParameterAndABadValueInOneProblem() throws Exception {
    TestApi.Response reply = api.send("GET", "/v1/events?limit=0&foo=1", NO_HEADERS, "");

    assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(reply.body())
        .contains("\"detail\":\"2 fields are invalid\"")
        .contains("\"pointer\":\"limit\"")
        .contains("\"pointer\":\"foo\"");
  }
```

`GET /v1/events?limit=0&foo=1` has two faults: `foo` is not a parameter of this endpoint, and `limit=0` is below the minimum of 1. The reply must be one `400` whose `detail` is `2 fields are invalid` and whose `errors` name both `limit` and `foo`. An unknown name used to stop the value checks, so only `foo` was reported.

#### Test 5: a body that is too large or not JSON

```java
  @Test
  void refusesABodyThatIsTooLargeOrNotJson() throws Exception {
    TestApi.Response tooLarge =
        api.send(
            "POST",
            "/v1/holds",
            TestApi.jsonWithKey("big"),
            "{\"x\":\"" + "a".repeat(ContractLimits.MAX_BODY_BYTES) + "\"}");
    TestApi.Response notJson =
        api.send(
            "POST",
            "/v1/holds",
            Map.of("Content-Type", "text/plain", "Idempotency-Key", "\"text\""),
            "eventId=1");
    TestApi.Response malformed =
        api.send("POST", "/v1/holds", TestApi.jsonWithKey("bad"), "{\"eventId\":");

    assertThat(tooLarge.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(notJson.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(malformed.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
```

- `"a".repeat(ContractLimits.MAX_BODY_BYTES)` builds a body larger than the limit (1,024 bytes plus the JSON around it). The status must be `413` (`CONTENT_TOO_LARGE`).
- A body `eventId=1` sent as `Content-Type: text/plain` gets `415` (`UNSUPPORTED_MEDIA_TYPE`).
- A body cut off in the middle (`{"eventId":`) gets `400`.

All three replies are judged by the contract too, because they go through `send`.

#### Test 6: paging through events with the returned cursor

```java
  @Test
  void pagesThroughTheEventsWithTheCursorItReturns() throws Exception {
    TestApi.Response first = api.send("GET", "/v1/events?limit=2", NO_HEADERS, "");
    String cursor = first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
    TestApi.Response last = api.send("GET", "/v1/events?limit=2&cursor=" + cursor, NO_HEADERS, "");

    assertThat(first.body())
        .contains("\"eventId\":1")
        .contains("\"eventId\":2")
        .contains("nextCursor");
    assertThat(last.body()).contains("\"eventId\":3").doesNotContain("nextCursor");
  }
```

- `GET /v1/events?limit=2` returns two events and a `nextCursor`.
- `first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1")` cuts the cursor out of the body with a regular expression. `.*` is "anything", the part in round brackets captures the text between the quotes after `nextCursor`, and `$1` is that capture.
- The second request sends that cursor back. The first body contains events 1 and 2 and `nextCursor`; the last contains event 3 and no `nextCursor`. The small seed has three events, so two pages end it.

#### Test 7: filters

```java
  @Test
  void filtersSeatsBySectionAndState() throws Exception {
    api.hold("B-3", "filter");

    TestApi.Response held = api.send("GET", "/v1/events/1/seats?state=held", NO_HEADERS, "");
    TestApi.Response sectionC =
        api.send("GET", "/v1/events/1/seats?section=C&limit=500", NO_HEADERS, "");

    assertThat(held.body())
        .isEqualTo("{\"items\":[{\"seat\":\"B-3\",\"section\":\"B\",\"state\":\"held\"}]}");
    assertThat(sectionC.body()).contains("\"C-1\"").contains("\"C-20\"").doesNotContain("\"B-");
  }
```

- `api.hold("B-3", "filter")` holds seat B-3. `?state=held` must list exactly that seat: the whole body is compared with `isEqualTo("{\"items\":[{\"seat\":\"B-3\",\"section\":\"B\",\"state\":\"held\"}]}")`.
- `?section=C&limit=500` must list C-1 to C-20 and none of section B (`doesNotContain("\"B-")`).

#### Test 8: a cursor belongs to one query

```java
  @Test
  void refusesACursorUsedWithOtherFiltersOrAnotherEvent() throws Exception {
    TestApi.Response first =
        api.send("GET", "/v1/events/1/seats?section=A&limit=5", NO_HEADERS, "");
    String cursor = first.body().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

    TestApi.Response otherFilter =
        api.send("GET", "/v1/events/1/seats?section=B&limit=5&cursor=" + cursor, NO_HEADERS, "");
    TestApi.Response eventsCursor = api.send("GET", "/v1/events?cursor=" + cursor, NO_HEADERS, "");

    assertThat(otherFilter.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(otherFilter.body()).contains("another event or filter");
    assertThat(eventsCursor.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
```

A cursor made for `section=A` is reused with `section=B` and with another path (`/v1/events`). Both must be refused with `400`, and the first message must say `another event or filter`. A cursor that could be reused on any query would let a client skip or repeat rows.

#### Test 9: unknown ids

```java
  @Test
  void answersUnknownEventsHoldsAndBookingsWithNotFound() throws Exception {
    String unknown = "00000000-0000-4000-8000-000000000000";

    assertThat(api.send("GET", "/v1/events/999/seats", NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("GET", "/v1/holds/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("DELETE", "/v1/holds/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.send("GET", "/v1/bookings/" + unknown, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(api.hold("Q-1", "no-such-seat").status()).isEqualTo(HttpStatus.NOT_FOUND);
  }
```

An unknown event (`999`), hold (a UUID of all zeros), booking, a `DELETE` of an unknown hold, and holding a seat that does not exist (`Q-1`) must all answer `404`. Each reply is judged by the contract.

#### Test 10: showing a hold and a booking, and cancelling twice

```java
  @Test
  void showsAHoldAndABookingAndCancelsIdempotently() throws Exception {
    TestApi.Response hold = api.hold("A-5", "show");
    String holdPath = hold.header("Location");
    TestApi.Response booking =
        api.confirm(holdPath.substring("/v1/holds/".length()), "show-confirm");

    assertThat(api.send("GET", holdPath, NO_HEADERS, "").body())
        .contains("\"state\":\"confirmed\"");
    assertThat(api.send("GET", booking.header("Location"), NO_HEADERS, "").body())
        .contains("\"amount\":\"80.00\"")
        .contains("\"currency\":\"USD\"");
    assertThat(api.send("DELETE", holdPath, NO_HEADERS, "").status())
        .isEqualTo(HttpStatus.CONFLICT);

    String other = api.hold("A-6", "cancel-twice").header("Location");
    assertThat(api.send("DELETE", other, NO_HEADERS, "").status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(api.send("DELETE", other, NO_HEADERS, "").status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(api.send("GET", other, NO_HEADERS, "").body()).contains("\"state\":\"cancelled\"");
    assertThat(
            api.count(
                "SELECT COUNT(*) FROM seats WHERE seat_label = 'A-6' AND status = 'AVAILABLE'"))
        .isEqualTo(1);
  }
```

- `api.hold("A-5", "show")` holds a seat; its `Location` header is the hold's path. `api.confirm(holdPath.substring("/v1/holds/".length()), "show-confirm")` cuts the id from the path and confirms it.
- `GET holdPath` now says `"state":"confirmed"`. `GET` of the booking's `Location` shows `"amount":"80.00"` and `"currency":"USD"`.
- `DELETE` of a confirmed hold is refused with `409`: a paid booking cannot be cancelled by cancelling the hold.
- A second hold on `A-6` is cancelled twice. Both `DELETE` calls answer `204` (no content): cancelling is **idempotent**, repeating it changes nothing. A `GET` then shows `"state":"cancelled"`.
- The last assertion reads the database: the seat `A-6` is `AVAILABLE` again, exactly once (`SELECT COUNT(*) FROM seats WHERE seat_label = 'A-6' AND status = 'AVAILABLE'` is 1).

#### Test 11: health and metrics

```java
  @Test
  void reportsHealthAndMetrics() throws Exception {
    api.hold("A-7", "metrics");
    api.hold("A-7", "metrics");

    TestApi.Response health = api.send("GET", "/health", NO_HEADERS, "");
    TestApi.Response metrics = api.send("GET", "/metrics", NO_HEADERS, "");

    assertThat(health.body()).isEqualTo("{\"status\":\"up\"}");
    assertThat(metrics.body())
        .contains("seatwave_requests_total{route=\"POST /v1/holds\",status=\"201\"} 2")
        .contains(
            "seatwave_idempotency_outcomes_total{endpoint=\"POST /v1/holds\",outcome=\"replayed\"} 1")
        .contains("seatwave_idempotency_keys_stored 1")
        .contains(
            "seatwave_request_duration_milliseconds_bucket{route=\"POST /v1/holds\",le=\"+Inf\"} 2");
  }
```

- Two holds with the same key `metrics`: the second is a replay.
- `GET /health` must be exactly `{"status":"up"}`.
- `GET /metrics` must contain four lines in the Prometheus text format: two requests to `POST /v1/holds` with status 201; one idempotency outcome `replayed` for that endpoint; one stored key; and the latency histogram bucket `+Inf` with 2 observations. The last bucket always counts everything.

#### The helper `pathWithQuery`

```java
  private static String pathWithQuery(Forbidden request) {
    if (request.query().isEmpty()) {
      return request.path();
    }
    StringBuilder query = new StringBuilder(request.path()).append('?');
    request
        .query()
        .forEach((name, value) ->
            query
                .append(name)
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                .append('&'));
    return query.substring(0, query.length() - 1);
  }
}
```

- If the row has no query, the path is used as it is.
- Otherwise `new StringBuilder(request.path()).append('?')` starts the URL, and for each query parameter `name=value&` is appended. `URLEncoder.encode(value, StandardCharsets.UTF_8)` escapes characters that may not appear raw in a URL: the cursor `not a cursor!` becomes `not+a+cursor%21`.
- `query.substring(0, query.length() - 1)` cuts off the last `&`.

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run only the contract tests:

```
./gradlew --offline cleanTest test --tests '*contract*'
```

   Output: 23 `PASSED` lines (7 `BreakingChangeTest`, 11 `ContractConformanceTest`, 5 `OpenApiDocumentTest`).

2. Lint all the contract files:

```
./gradlew --offline -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml openapi/examples/holds-entry.yaml'
```

   Output: three lines ending in `valid, every rule passed`.

3. See the lint refuse the broken example:

```
./gradlew --offline -q lintContract --args='openapi/examples/holds-entry-broken.yaml'
```

   Output: `4 problem(s)` and the four lines, then Gradle's `finished with non-zero exit value 3`.

4. See the diff pass the additive example and fail the breaking one: the two commands are in `openapi-validator-and-diff.md`, with the real output.

5. List the files of the package:

```
ls src/test/java/io/plainround/seatwave/api/contract
```

   Output: the seven files of the table at the top (`BreakingChangeTest.java`, `BreakingChanges.java`, `ContractConformanceTest.java`, `ContractRules.java`, `DiffContractCli.java`, `LintContractCli.java`, `OpenApiDocumentTest.java`). JaCoCo does not measure them, because it measures `src/main/java` only (`jacoco.md`).

## Mistakes people make

- **Writing a lint rule and no test that breaks it.** `findsTheMistakesInABrokenHoldsEntry` exists so the rules are known to fire.
- **Comparing prose with numbers by eye.** The test compares them for you.
- **Testing the server only.** `refusesEveryRequestTheContractForbids...` asks the contract first, so a wrong test row is found.
- **Using `==` on exit codes in a Gradle task.** A tool that calls `System.exit(3)` makes the Gradle task fail; read the first lines of output, not just the failure box.
- **Assuming the diff is enough.** The house rules caught what openapi-diff did not list (`openapi-validator-and-diff.md`).

## Check yourself

1. *What are the exit codes of `lintContract`?* 0 all files pass, 3 a file has problems, 2 bad usage.
2. *Which rule does `ContractRules` apply to every POST with a body?* It must require the `Idempotency-Key` header.
3. *What makes a new request field breaking in `BreakingChanges`?* It is required in the candidate and was not required in the released contract.
4. *How does `refusesEveryRequestTheContractForbids...` stop its own table from lying?* It asks the validator first and requires `hasErrors()` to be true for every row.
5. *Why does `OpenApiDocumentTest` look for the sentence `Hold one seat for 300 seconds` in the document?* The sentence is built from the code's constant, so if the document and the code disagree, the build fails.

## Further reading

- The OpenAPI Specification 3.1.x: <https://spec.openapis.org/oas/v3.1.0.html>
- Problem Details for HTTP APIs, RFC 9457: <https://www.rfc-editor.org/rfc/rfc9457>
- Prometheus text exposition format: <https://prometheus.io/docs/instrumenting/exposition_formats/>
- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
