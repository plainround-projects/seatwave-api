# Awaitility 4.3.0

## What it is

Some things in a program happen **later**, in another thread, after the line that started them has already returned. A test that wants to check the result must wait. The crude way is `Thread.sleep(2000)`: wait a fixed time and hope it was enough. It is slow when the thing finishes in a millisecond and flaky when the machine is busy and needs three seconds. **Awaitility** is a small library for the better way: you describe the condition you are waiting for, and Awaitility checks it again and again until it holds or a time limit passes.

A **flaky** test passes on some runs and fails on others without any change to the code. Fixed sleeps are a common cause.

## Why this repository uses it

Two tests need to wait for something they did not wait for in the normal flow:
- `HoldRetryTest.returnsTheFirstHoldToARetryAfterALostReply` sends a request and hangs up before the reply comes. The server still finishes the hold on its own thread. The test must wait until the row that proves it is in the database, and only then send the retry.
- `ApiServerTest.refusesAtOnceWithRetryAfterWhenTheInFlightLimitIsReached` starts a slow confirm in the background. The test must wait until the stand-in provider has captured the charge, which means the confirm is now holding the server's only slot, and only then send the request that must be refused.

The README lists Awaitility for exactly this: "Waits for a condition (a key row written after a lost reply) instead of sleeping."

## Where it appears

| Path | What it does with Awaitility |
| --- | --- |
| `gradle/libs.versions.toml` | `awaitility = "4.3.0"` and the entry `awaitility`. |
| `build.gradle.kts` | `testImplementation(libs.awaitility)`. |
| `gradle.lockfile` | Locks `org.awaitility:awaitility:4.3.0` and its dependency `org.hamcrest:hamcrest:2.1` (`dependency-locking.md`). |
| `src/test/java/.../booking/HoldRetryTest.java` | One `await()` call (line 120). |
| `src/test/java/.../http/ApiServerTest.java` | One `await()` call (line 48). |

I searched the test sources: the Awaitility `await()` appears exactly twice (a third `await()` is the unrelated `startGate.await()` of the JDK's `CountDownLatch` in `AllAtOnce`), and no test calls `Thread.sleep`.

## How it works underneath

1. `await()` returns a **condition factory**. You describe the wait on it, and the last call, here `until(...)`, runs it.
2. `until(condition)` takes a **lambda** that returns `true` or `false` (a `Callable<Boolean>`). Awaitility calls it, and if the answer is `false` it waits one **poll interval** and calls it again. The default poll interval is 100 milliseconds, and the default poll delay (the wait before the first check) is also 100 milliseconds.
3. When the lambda returns `true`, `until` returns and the test goes on.
4. When the time limit passes first, `until` throws a `ConditionTimeoutException`, and the test fails with a message that names the condition. The default limit is 10 seconds. `atMost(...)` sets it. This repository sets 5 seconds in both places.
5. By default Awaitility evaluates the condition on a separate thread that it starts, while the thread that called `await()` waits for the answer. The lambda here only reads a number from the database or the provider, which is safe from any thread.
6. If the lambda itself throws (the database call can throw `SQLException`), the exception is not hidden: the wait ends and the test fails with it. The tests here do not ask Awaitility to ignore exceptions.

The limit is a **ceiling**, not a delay. A condition that becomes true after 10 milliseconds ends the wait after about 100 milliseconds (the first poll), not after 5 seconds. A passing test is therefore fast, and only a broken one waits the full limit.

## Walkthrough of this repository's use

### The static import

```java
import static org.awaitility.Awaitility.await;
```

This is the `import static` line from `HoldRetryTest` (line 4). It lets the test write `await()` instead of `Awaitility.await()`. `ApiServerTest` has the same line.

### Waiting for the server to finish a hold nobody is listening to

```java
  @Test
  void returnsTheFirstHoldToARetryAfterALostReply() throws Exception {
    sendAndHangUp(TestApi.holdBody("A-1", "fan_1"), "lost-reply");
    // The server finishes the hold although nobody reads the reply.
    await()
        .atMost(SERVER_FINISHES_WITHIN)
        .until(() ->
            api.count(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE state = 'COMPLETED'"
                        + " AND idempotency_key = ?",
                    "lost-reply")
                == 1);

    TestApi.Response retry = api.hold("A-1", "lost-reply");

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE hold_id = ?", holdIdIn(retry.body())))
        .isEqualTo(1);
    assertThat(api.count("SELECT COUNT(*) FROM seats WHERE status = 'HELD'")).isEqualTo(1);
  }
```

- Line 118: `sendAndHangUp(...)` sends a complete hold request on a raw socket and closes it without reading the reply (`tests-booking-and-idempotency-walkthrough.md` shows the helper). The client is gone, but the server has already read the request and carries on.
- The comment says it: "The server finishes the hold although nobody reads the reply."
- `await().atMost(SERVER_FINISHES_WITHIN)` sets the limit. `SERVER_FINISHES_WITHIN` is a constant `Duration.ofSeconds(5)`.
- `.until(() -> api.count("... WHERE state = 'COMPLETED' AND idempotency_key = ?", "lost-reply") == 1)` is the condition. `api.count(...)` reads one number from the database, here the number of key rows in state `COMPLETED` for the key `lost-reply`. The condition is true when exactly one such row exists: the server has finished the hold and stored its reply under the key.
- Only after the wait does the test send the retry with the same key (`api.hold("A-1", "lost-reply")`) and check three things: the status is 201, the hold id appears once in `seats`, and only one seat is `HELD` in all. Without the wait, the retry could arrive before the first request finished, and the test would be checking a race, not the lost-reply case.

### Waiting for a request to hold the server's only slot

```java
  @Test
  void refusesAtOnceWithRetryAfterWhenTheInFlightLimitIsReached() throws Exception {
    try (TestApi api = TestApi.start(new ApiSettings.Seed(1, 1, SEATS), ONE_REQUEST_IN_FLIGHT)) {
      String holdId = api.hold("A-1", "slot").header("Location").substring("/v1/holds/".length());
      // A confirm that waits for a slow provider keeps the one slot busy.
      api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT);

      try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<TestApi.Response> slowConfirm =
            background.submit(() -> api.confirm(holdId, "slow-confirm"));
        // The provider has captured: the confirm is now waiting for its reply, holding the slot.
        await().atMost(CAPTURE_WITHIN).until(() -> api.provider().chargeCount() == 1);

        TestApi.Response refused = api.send("GET", "/v1/events", Map.of(), "");

        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.body()).contains("/problems/service-busy");
        assertThat(refused.header("Retry-After")).isEqualTo("1");
        assertThat(slowConfirm.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }
  }
```

- The API is started with an in-flight limit of one request (`ONE_REQUEST_IN_FLIGHT`), so a second request at the same moment must be refused.
- `api.provider().setMode(StandInMode.CHARGE_THEN_TIMEOUT)` tells the stand-in provider to capture the charge and then answer late.
- The confirm is submitted to a background thread (`background.submit(...)`), because the call blocks until the slow provider replies.
- `await().atMost(CAPTURE_WITHIN).until(() -> api.provider().chargeCount() == 1)` waits until the provider reports one charge captured. At that moment the confirm is waiting for the provider's reply and holds the slot. `CAPTURE_WITHIN` is 5 seconds.
- Now the test sends `GET /v1/events` and expects 503 with a `service-busy` problem and `Retry-After: 1`. The background confirm ends with 503 too, because the provider's reply came after the configured timeout. The call `slowConfirm.get()` waits for that result.
- Without the `await`, the `GET` could arrive before the confirm had taken the slot, and the test would sometimes get 200.

### The build wiring

`testImplementation(libs.awaitility)` puts the library on the test classpath only (`gradle.md`). It brings Hamcrest 2.1, a matcher library that Awaitility is built on; no test imports Hamcrest.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Find every wait in the tests:

```
grep -rn "await()" src/test
```

   Output: three lines: `AllAtOnce.java:34` (the JDK latch, not Awaitility), `HoldRetryTest.java:120` and `ApiServerTest.java:48`, each with its folder in front.

2. Confirm that nothing sleeps:

```
grep -rn "Thread.sleep" src/test
```

   Output: nothing (exit code 1).

3. Run the two tests that wait:

```
./gradlew --offline cleanTest test --tests '*HoldRetryTest.returnsTheFirstHoldToARetryAfterALostReply' --tests '*ApiServerTest.refusesAtOnceWithRetryAfterWhenTheInFlightLimitIsReached'
```

   Output: two `PASSED` lines.

4. See the library on the classpath:

```
./gradlew --offline -q dependencyInsight --dependency awaitility --configuration testRuntimeClasspath
```

   Output begins with `org.awaitility:awaitility:4.3.0`.

## Mistakes people make

- **Using a fixed sleep.** It is either too short (flaky) or too long (slow). Describe the condition instead.
- **Waiting without a limit.** `atMost(...)` here keeps a broken test from running for the 10-second default, and says how long the author thinks the thing may take.
- **A condition with a side effect.** The lambda runs many times. This one only reads a count.
- **Waiting on a thing the test itself needs to cause.** The hang-up test sends first and waits afterwards; waiting before sending would wait forever.
- **Putting the assertion inside `until`.** `until` wants a true or false answer. The checks come after the wait, with AssertJ (`assertj.md`).

## Check yourself

1. *Why is `Thread.sleep(5000)` a poor way to wait for the server?* It always waits the full time, and it still fails if the server needs longer.
2. *What does `await().atMost(Duration.ofSeconds(5)).until(...)` do when the condition never holds?* After 5 seconds it throws a `ConditionTimeoutException` and the test fails.
3. *How often does Awaitility check by default?* Every 100 milliseconds, after a first wait of 100 milliseconds.
4. *What is the condition in `HoldRetryTest`?* That exactly one key row for `lost-reply` is in state `COMPLETED`.
5. *Why does `ApiServerTest` wait for the provider's `chargeCount()`?* A charge captured means the confirm is waiting for the provider's reply, so it holds the only slot.

## Further reading

- Awaitility 4.3.0 usage guide: <https://github.com/awaitility/awaitility/wiki/Usage>
- Awaitility getting started: <https://github.com/awaitility/awaitility/wiki/Getting_started>
- Javadoc: <https://javadoc.io/doc/org.awaitility/awaitility/4.3.0>
