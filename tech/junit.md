# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and all 89 run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks and Awaitility (`awaitility.md`) the waiting. This module is about API design, so many tests start a real API and a stand-in payment provider on the loopback address and talk to them over HTTP, or attack one hold with 100 retries at once; JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform()`. |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 19 test classes with 89 `@Test` methods, and 10 other classes that hold no tests (the helpers `TestApi`, `Contract`, `MutableClock`, `HttpStatus`, `AllAtOnce`, `FailingKeyWrite`, the contract tools `ContractRules`, `BreakingChanges` and the two `*Cli` classes). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the methods marked `@BeforeEach`, then the test method, then the methods marked `@AfterEach`, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 16 here), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The test classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The helper classes are `public` on purpose, because tests in several packages use them.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. Each test that needs a server starts its own on a port the system picks, so the tests would not share a socket even if they did overlap (`tests-booking-and-idempotency-walkthrough.md`).

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void isTheSameForTheSameRequestAndDifferentOtherwise() {
    Fingerprint first = Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}");

    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-1\"}")).isEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/holds", "{\"seat\":\"A-2\"}")).isNotEqualTo(first);
    assertThat(Fingerprint.of("POST /v1/bookings", "{\"seat\":\"A-1\"}")).isNotEqualTo(first);
    assertThat(first.hex()).hasSize(SHA_256_HEX_LENGTH);
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "is the same for the same request and different otherwise". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). Here the first statement arranges and acts (it fingerprints one request) and the four `assertThat` lines assert.

Tests that open a socket or load a file declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `IOException` or `InterruptedException` that would be noise in a test.

### `@BeforeEach` and `@AfterEach`: a fresh API for every test

```java
  private TestApi api;

  @BeforeEach
  void startApi() throws Exception {
    api = TestApi.start();
  }

  @AfterEach
  void stopApi() throws Exception {
    api.close();
  }
```

- `private TestApi api;` is a field of the object, not of the class (there is no `static`). Because JUnit creates a new instance of the class for every test method, each test gets its own field.
- `@BeforeEach` marks a method that runs **before every test method**. `startApi()` starts one API with its own in-memory database, its own stand-in provider and its own clock. Five classes use the pair: `HoldRetryTest`, `ConfirmPaymentTest`, `ContractConformanceTest`, `Version0CompatibilityTest` and `PaymentStandInTest`.
- `@AfterEach` marks a method that runs **after every test method**, also when the test failed. `stopApi()` closes the API and the provider so no port is left held. A test that moves the clock forward or writes rows cannot disturb the next test, because the next test has a whole new API.

### `@TempDir`: a folder for one test

```java
  @Test
  void appliesEachMigrationOnceAndSeedsOnce(@TempDir Path folder) throws Exception {
    String url = "jdbc:h2:file:" + folder.resolve("seatwave").toAbsolutePath();
    ApiSettings.Seed seed = new ApiSettings.Seed(EVENTS, SECTIONS, SEATS_PER_SECTION);
    Clock clock = Clock.fixed(TestApi.START, ZoneOffset.UTC);
    try (Database first = Database.open(url)) {
      first.seedIfEmpty(seed, clock);
    }

    try (Database again = Database.open(url)) {
      again.seedIfEmpty(seed, clock);

      assertThat(number(again, "SELECT COUNT(*) FROM schema_history")).isEqualTo(MIGRATIONS);
      assertThat(number(again, "SELECT COUNT(*) FROM events")).isEqualTo(EVENTS);
      assertThat(number(again, "SELECT COUNT(*) FROM seats"))
          .isEqualTo(SECTIONS * SEATS_PER_SECTION);
    }
  }
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. `DatabaseTest` uses it to put a real database file in a folder: the test opens the database twice on the same file and checks that the migrations ran only once. I searched the test sources and found `@Test` 70 times and `@TempDir` once.

### Closing what a test opens

```java
  @Test
  void answersAnUnknownPathAndAWrongMethodWithProblems() throws Exception {
    try (TestApi api = TestApi.start()) {
      TestApi.Response unknown = api.sendUnchecked("GET", "/v2/events", Map.of(), "");
      TestApi.Response wrongMethod = api.sendUnchecked("PUT", "/v1/holds", Map.of(), "");

      assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(unknown.header("Content-Type")).isEqualTo("application/problem+json");
      assertThat(wrongMethod.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
      assertThat(wrongMethod.header("Allow")).isEqualTo("POST");
    }
  }
```

Here there is no `@AfterEach`. Instead the test opens the API in the round brackets of a `try`. A resource opened there is closed automatically when the block ends, even when an assertion throws. `TestApi` implements `AutoCloseable` for that purpose. That is why no server is left holding its port after a failing test (`java.md` explains the construct).

### A helper class that is not a test

```java
public final class FailingKeyWrite implements Trigger {

  /** The SQL that installs it. */
  public static final String INSTALL =
      "CREATE TRIGGER crash_before_key_write BEFORE UPDATE ON idempotency_keys FOR EACH ROW"
          + " CALL \"io.plainround.seatwave.api.booking.FailingKeyWrite\"";

  /** The SQL that removes it. */
  public static final String REMOVE = "DROP TRIGGER crash_before_key_write";

  @Override
  public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
    // Thrown after the seat UPDATE ran in the same transaction: the process "dies" here.
    throw new SQLException("simulated crash between the effect and the key write", "08006");
  }
}
```

This class is not a test. It is a helper for one: an H2 database **trigger** that the test installs with SQL to simulate a crash. JUnit ignores it because it has no `@Test`. The database creates an instance itself from the class name.

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@BeforeAll`, `@AfterAll`, `@Nested`, `@DisplayName`, `@Timeout` or `@ExtendWith`. Where several inputs need one check, a test lists them in a plain `List` and loops (`ContractConformanceTest.refusesEveryRequestTheContractForbidsBeforeDoingAnyWork` loops over 14 forbidden requests). That keeps each test readable from top to bottom.

There is also no `@Timeout`. A test that waits for something never relies on JUnit to cut it off. Each wait carries its own bound instead: Awaitility's `.atMost(...)` (`awaitility.md`), the HTTP client's timeouts and the provider's connect and request timeouts. A bound inside the call gives a clear error, where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why tests such as `BreakingChangeTest` and `ApiSettingsTest` can read `openapi/examples/seatwave-api-1.1.0-additive.yaml` and `config/serve.properties` by those relative paths. The contract itself is found through the system properties that `build.gradle.kts` sets (`gradle.md`).

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform()` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test | grep -c PASSED
```

   Output: `89`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*FingerprintTest'
```

   Output: three lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `7 actionable tasks: 3 executed, 4 up-to-date`.

3. Run one method:

```
./gradlew --offline cleanTest test --tests '*HoldRetryTest.repeatsTheSameHoldWithoutActingAgain'
```

   Output: one `PASSED` line, `HoldRetryTest > repeatsTheSameHoldWithoutActingAgain() PASSED`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Output: `5 @AfterEach`, `5 @BeforeEach`, `5 @Override` and `89 @Test`. (`@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test` finds its three uses: `ApiSettingsTest.java` line 97 and `DatabaseTest.java` lines 28 and 68.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 19 files has a `tests="..."` count after a full run: 8 (`ConfirmPaymentTest`), 9 (`HoldRetryTest`), 2 (`RetryHoldScriptTest`), 3 (`CursorsTest`), 3 (`PaginationConsistencyTest`), 8 (`ApiSettingsTest`), 7 (`BreakingChangeTest`), 11 (`ContractConformanceTest`), 5 (`OpenApiDocumentTest`), 2 (`ApiServerTest`), 1 (`ReplyTest`), 5 (`Version0CompatibilityTest`), 3 (`FingerprintTest`), 2 (`IdempotencyKeyTest`), 3 (`IdempotencyStoreTest`), 5 (`JsonCodecTest`), 1 (`RetryStormTest`), 6 (`PaymentStandInTest`) and 5 (`DatabaseTest`). They add up to 89.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own API in `@BeforeEach`, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*catalog*'` would also match the package name `catalog`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test from a different folder.** The settings and contract files are found by relative paths, so the test must start in the project folder, as Gradle does.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every API and provider in `@AfterEach` or in a `try` block.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another. It is why each test here gets a new `TestApi` and a new database.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter, and deletes it afterwards.
4. *When does an `@AfterEach` method run?* After every test method, also after a failed one.
5. *How do 89 `@Test` methods give 89 results?* Each method runs once; this repository has no parameterized tests.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
