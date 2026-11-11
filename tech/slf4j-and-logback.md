# SLF4J 2.0.20 and Logback 1.6.6 (logging)

## What it is

**Logging** means a program writes short lines about what it is doing, so that a person can later find out what happened. A line usually has a time, a **level** (how serious it is), the name of the part of the program that wrote it, and a message.

**SLF4J** (Simple Logging Facade for Java) is a small library that gives the code one way to log (`Logger`, `LoggerFactory`, `MDC`), whatever library does the actual writing. It is a **facade**: a front that holds no logic of its own. **Logback** is a logging library that does the actual writing: it decides which lines are kept, how a line looks and where it goes. This repository pins `org.slf4j:slf4j-api` 2.0.20 for the code to compile against, and `ch.qos.logback:logback-classic` 1.6.6, which is on the run-time class path only. Because the code depends only on SLF4J, the writing library could be exchanged without changing a line of code.

## Why this repository uses it

The service handles many requests at once. When one request fails, someone needs the lines of that request and nothing else. So each request gets a **correlation id**, a random identifier. The id is in every log line of the request and is also the `instance` of any problem reply. A client who quotes the instance lets support find the lines. The JDK has a logger of its own (`java.util.logging`), but SLF4J with Logback gives the context map and the line pattern that the correlation id needs.

Logs go to **standard error**, so a tool's real output on standard output (for example the retry storm's report) stays clean.

## Where it appears

| Path | What it does with logging |
|---|---|
| `src/main/java/io/plainround/seatwave/api/SeatWaveApi.java` | A logger for the key purge job: an info line per run and a warning when the purge fails. |
| `src/main/java/io/plainround/seatwave/api/http/ApiServer.java` | The correlation id in the context map; one info line per request; warnings and errors for failures. |
| `src/main/resources/logback.xml` | Logback's configuration: the level, the target (standard error) and the line pattern. Another note quotes it line by line; this note explains what it does. |
| `build.gradle.kts` | `implementation` for the API, `runtimeOnly` for Logback (another note). |

The classes `ServeCli`, `RetryStormCli`, `PageWalkCli` and `KeyStoreSizeCli` do not log. They print their results with `System.out` and their usage errors with `System.err`: a command's own report is its output, not a log.

## How it works underneath

**Levels.** From least to most serious: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.

- `INFO` says that something normal happened (a request was served).
- `WARN` says that something unexpected happened but the program carried on (the client went away; a purge failed and will be tried again).
- `ERROR` says that something failed and needs attention (a database failure, a bug).

A configuration sets a **threshold**: lines below it are dropped. This repository's threshold is `INFO`, so `DEBUG` and `TRACE` lines are not written.

**A logger** is an object you get by name. The usual name is the class: `LoggerFactory.getLogger(ApiServer.class)`. The name is printed in each line (Logback's pattern shortens it to the last part, `ApiServer`).

**Parameterized messages.** Instead of building text with `+`, you write a message with `{}` and give the values after it: `LOG.info("{} {} {} ms", routeLabel, status, elapsed.toMillis())`. The library fills the `{}` in order only if the line is actually written. That saves the work of building text for a line that the threshold drops. If the **last** argument is an exception and there is no `{}` for it, the library prints the exception's stack trace under the line.

**The MDC.** The **Mapped Diagnostic Context** is a small map of text to text that belongs to the current thread. The code puts `correlationId` into it at the start of a request. The line pattern can print any key of the map. When the request is done the code removes the key. Because each request runs on its own thread (a virtual thread), each sees its own map. A line written by another thread, such as the purge job, has no `correlationId` in its map.

**The pattern.** Logback's configuration describes each line with a pattern. In this repository the pattern prints the time (`%d{HH:mm:ss.SSS}`), the level padded to five characters (`%-5level`), the correlation id in brackets with `none` if the map has none (`%X{correlationId:-none}`), the logger name shortened to its last part (`%logger{0}`), a dash, the message (`%msg`) and a new line (`%n`).

**The target.** The appender is a `ConsoleAppender` with the target `System.err`. Standard error is the stream for diagnostics; the shell can send it to a file separately from standard output.

## Walkthrough of this repository's use

### The logger of a class

```java
  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveApi.class);
```

`private static final Logger LOG = LoggerFactory.getLogger(SeatWaveApi.class);` is the usual line: one logger per class, named after it.

```java
  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
```

### The correlation id and the request line

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

- `String correlationId = UUID.randomUUID().toString();` makes the id. A **UUID** is a random 128-bit number written as 36 characters; two requests never get the same one in practice.
- `MDC.put("correlationId", correlationId);` puts it into the thread's context map.
- The `ApiRequest` carries the same id, so `Problems` can write `"urn:uuid:" + correlationId` as the `instance` of an error body.
- `LOG.warn("the reply could not be sent: {}", clientGone.getMessage());` is a warning when the client has gone before or during the reply. It is a warning, not an error: it is the lost-reply case, and the client's retry with the same key gets the stored reply.
- In `finally`, `LOG.info("{} {} {} ms", routeLabel, status, elapsed.toMillis());` writes one line for every request, whatever happened: the route template, the status code and the milliseconds. Using the template (`GET /v1/events/{eventId}/seats`) and not the real path keeps lines and metrics comparable.
- `MDC.remove("correlationId");` cleans up so the next task on the same thread does not inherit it.

### Failures

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

- `LOG.error("database failure, SQL state {}", databaseFailure.getSQLState(), databaseFailure);` logs the SQL state (a five-character code that names the kind of database failure) and, because the last argument is the exception, its stack trace. The client gets only a fixed 503 reply; these details stay in the log.
- `LOG.error("unexpected failure", bug);` logs a bug with its stack trace. The client gets a 500 that asks it to quote the `instance`, which is the id in the brackets of this very line.

### The purge job

```java
  private static void purgeExpiredKeys(Database database, IdempotencyStore keys, Clock clock) {
    try (Connection connection = database.connect()) {
      int deleted = keys.purgeExpired(connection, clock.instant(), PURGE_BATCH);
      LOG.info("purged {} expired idempotency keys", deleted);
    } catch (SQLException purgeFailure) {
      // A failed purge only delays deletion; the next run tries again. It must not kill the
      // scheduler, which a thrown exception would.
      LOG.warn("key purge failed, SQL state {}", purgeFailure.getSQLState(), purgeFailure);
    }
  }
```

- `LOG.info("purged {} expired idempotency keys", deleted);` runs once a minute. The purge thread has no request, so its lines show `[none]`.
- `LOG.warn("key purge failed, SQL state {}", purgeFailure.getSQLState(), purgeFailure);` is a warning: a failed purge only delays deletion. The comment says why it is not rethrown: a thrown exception would kill the scheduler.

### What is never logged

In the two classes this note covers, no log line contains a request body, an idempotency key, a customer name or a payment id. The request line has only the route template, the status, the time and the id. This keeps personal data out of the logs.

## Try it yourself

I ran the API from a copy of the repository on port 28187 (`./gradlew -q serve --args=...`) with standard error saved to a file. Times and ids differ in every run. In your own terminal the lines appear where the server runs.

1. Start the server and send one request to a path that does not exist:

```text
$ curl -s http://127.0.0.1:28187/nothing
{"type":"https://seatwave.example/problems/not-found","title":"No such resource","status":404,"detail":"no such path","instance":"urn:uuid:af0270c3-afc4-4c3e-a384-4e17885c118c"}
```

2. Find the id in the server's log. The text after `urn:uuid:` is the id in brackets:

```text
01:52:19.505 INFO  [af0270c3-afc4-4c3e-a384-4e17885c118c] ApiServer - unmatched 404 18 ms
```

   (The label `unmatched` is used when no route matched.) A `GET /health` request wrote:

```text
01:52:08.418 INFO  [b3bd5fe2-1366-43e4-abb6-b43865895e98] ApiServer - GET /health 200 24 ms
```

3. Count the lines of a run. The retry storm of 10 copies and 20 pairs wrote 118 lines to standard error, one for each attempt, which equals the 118 samples in its latency line. The report itself went to standard output.

4. After more than a minute, the purge job writes its line (the thread has no request, so the id is `none`):

```text
01:53:07.303 INFO  [none] SeatWaveApi - purged 0 expired idempotency keys
```

   The server had started at 01:52:08, so the first run came one minute later.

## Mistakes people make

- **Building the message with `+`.** The text is built even when the line is dropped. Use `{}`.
- **Logging secrets or personal data.** Keys, passwords, card numbers and names do not belong in logs. Here none is logged.
- **Losing the stack trace.** `LOG.error("failed: " + e.getMessage())` drops it. Pass the exception as the last argument.
- **Forgetting to clear the context.** A thread that is reused would carry the old id into the next task. `finally` removes it.
- **One line per request with no way to tie them together.** Without a correlation id, a failure cannot be traced. Here every line has one.
- **Logging to standard output when it carries data.** The tools print their reports on standard output; logs go to standard error.
- **Logging in a loop at `INFO`.** It floods the file. One line per request is the most this code writes at `INFO`, and the purge writes one per minute.

## Check yourself

1. What is the difference between SLF4J and Logback?
   Answer: SLF4J is the facade the code calls; Logback is the library that decides what is written and where.
2. Why is `LOG.info("{} {} {} ms", a, b, c)` better than joining the text with `+`?
   Answer: the library builds the text only if the line passes the level threshold.
3. What ties a log line to a problem reply?
   Answer: the correlation id, which is the text after `urn:uuid:` in the reply's `instance` and the text in the brackets of the line.
4. Why does a line from the purge job show `[none]`?
   Answer: the id lives in the MDC of a request's thread; the purge thread has none, and the pattern prints `none` for a missing key.
5. Why is a failed send (`IOException`) a warning and not an error?
   Answer: it means the client went away, which is the normal lost-reply case; the client's retry with the same key gets the stored reply.

## Further reading

- SLF4J manual: https://www.slf4j.org/manual.html
- SLF4J FAQ (parameterized messages): https://www.slf4j.org/faq.html#logging_performance
- Logback manual, architecture: https://logback.qos.ch/manual/architecture.html
- Logback manual, layouts and the pattern: https://logback.qos.ch/manual/layouts.html
- Logback manual, the MDC: https://logback.qos.ch/manual/mdc.html
- Logback manual, configuration: https://logback.qos.ch/manual/configuration.html
