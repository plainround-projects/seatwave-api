# HTTP and the JDK's built-in HTTP server

## What it is

**HTTP** (HyperText Transfer Protocol) is the set of rules two programs use to talk over a network when one asks and the other answers. The asking program is the **client** (a browser, `curl`, a phone app). The answering program is the **server**. The client sends a **request**; the server sends back a **response**. Each one is plain text made of a first line, some header lines, an empty line and an optional body.

The **JDK's built-in HTTP server** is a small web server that ships with Java, in the package `com.sun.net.httpserver` (module `jdk.httpserver`). You create it, tell it which port to listen on, give it a **handler** (your code that answers) and an **executor** (the threads that run the handlers), and start it. It supports HTTP/1.1. It is a supported, exported JDK API, but it is deliberately simple: no routing, no JSON, no security layer. This repository adds the little it needs on top.

## Why this repository uses it

Module 7 is about the **contract** of an API: the exact requests it accepts and responses it gives. A framework would hide those details. With the JDK server, every status code, every header and every byte of the body is visible in the code, so a reader sees how a contract becomes a running service. It also adds no dependency: the JDK has the server and an HTTP client, and nothing else is needed for HTTP.

## Where it appears

| Path (under `src/main/java/io/plainround/seatwave/api/`) | What it does with HTTP |
|---|---|
| `http/ApiServer.java` | Creates the JDK server on `127.0.0.1`, reads each request, matches a route, sends the reply. |
| `http/Reply.java`, `http/ApiRequest.java` | The reply and request as plain data. |
| `http/Problems.java`, `ProblemType.java`, `ProblemBody.java` | The one error shape, problem details. |
| `http/BookingRoutes.java` | `Idempotency-Key`, `Location`, `Retry-After`, content type checks. |
| `http/Version0Routes.java` | `Deprecation`, `Sunset` and `Link` headers. |
| `http/OperationsRoutes.java` | `GET /health` and `GET /metrics`. |
| `load/ApiClient.java` | The JDK's HTTP client, used by the load tools. |

The contract itself is `openapi/seatwave-api.yaml` (another note).

## How it works underneath

### A request and a response, from zero

A computer is reached by an **address**. `127.0.0.1` is the **loopback** address: it always means "this same machine", and traffic sent to it never leaves the computer. A **port** is a number from 0 to 65,535 that picks one program on that machine. `127.0.0.1:8087` means "the program listening on port 8087 of this machine". A **socket** is the operating system's handle for one end of a network connection. HTTP/1.1 runs over **TCP**, which carries a stream of bytes in order, without loss, between two sockets.

A request, as `curl -v` showed it when I sent one to the running API (the lines starting `>` are the request, `<` the response):

```text
> POST /v1/holds HTTP/1.1
> Host: 127.0.0.1:28187
> User-Agent: curl/8.7.1
> Accept: */*
> Content-Type: application/json
> Idempotency-Key: "http-1"
> Content-Length: 45
>
< HTTP/1.1 201 Created
< Date: Wed, 11 Nov 2026 01:49:37 GMT
< Content-type: application/json
< Content-length: 131
< Location: /v1/holds/c3a00701-2cd1-4d4d-b420-359fe1639ab4
<
```

- The first request line is `METHOD PATH VERSION`. The **method** (`POST`) says what to do. The **path** (`/v1/holds`) says to what. A **query string** can follow the path after a `?` (`/v1/events?limit=2`).
- Each **header** is `Name: value`. Header names are case-insensitive. `Content-Type` says what the body is. `Content-Length` says how many bytes follow.
- An empty line ends the headers. The **body** comes after it (here, 45 bytes of JSON).
- The response starts with the **status line**: version, a three-digit **status code**, and a **reason phrase** (`Created`). The code is what programs read. After it come headers, an empty line and the body.

### Methods, "safe" and "idempotent"

| Method | Meaning here | Safe | Idempotent |
|---|---|---|---|
| `GET` | Read something | Yes | Yes |
| `POST` | Create something (a hold, a booking) | No | No |
| `DELETE` | Remove something (cancel a hold) | No | Yes |

**Safe** means the method is not meant to change anything on the server, so a program may repeat or prefetch it freely. **Idempotent** means that sending the same request many times has the same effect as sending it once. `DELETE` is idempotent: after the first, the hold is gone, and later ones find nothing more to remove. `POST` is not, by itself: two identical posts could create two holds. This repository makes the two `POST` operations safe to repeat with an `Idempotency-Key` header (below). That is the central idea of the project.

### Status codes this repository uses

| Code | Name | Where it comes from |
|---|---|---|
| 200 | OK | A read worked. |
| 201 | Created | A hold or a booking was made (with `Location`). |
| 204 | No Content | A cancel worked; there is no body. |
| 400 | Bad Request | Input broke the contract (all errors listed). |
| 404 | Not Found | No such path, event, hold or booking. |
| 405 | Method Not Allowed | The path exists for another method (with `Allow`). |
| 409 | Conflict | Seat taken, hold not active, confirm in progress, hold not cancellable. |
| 413 | Content Too Large | The body is over 1,024 bytes. (The JDK server prints the older name, Request Entity Too Large.) |
| 415 | Unsupported Media Type | The body is not `application/json`. |
| 422 | Unprocessable Content | The `Idempotency-Key` was used before with another body. |
| 500 | Internal Server Error | A bug; the client is asked to quote the `instance`. |
| 503 | Service Unavailable | Too many requests in flight, the database or the payment provider did not answer (with `Retry-After`). |

The first digit tells the class: 2 success, 4 the client's mistake, 5 the server's trouble. The JDK server prints the reason phrase it knows. For 422 it prints none, which HTTP/1.1 allows: the real response line is `HTTP/1.1 422 ` followed by nothing. Clients read the code.

### The headers used here

- **`Content-Type`** names the format of the body. The API reads and writes `application/json`. An error reply has `application/problem+json`. `GET /metrics` has `text/plain; version=0.0.4`.
- **`Idempotency-Key`** is sent by the client on `POST /v1/holds` and `POST /v1/bookings`. It is a quoted string of 1 to 64 characters from `A-Z a-z 0-9 _ -`, new for each intended action. The server stores the reply under the key, so a retry gets the same reply and does no second effect.
- **`Location`** is on a `201`. It gives the address of the new thing (`/v1/holds/<id>`).
- **`Retry-After`** is on a `503`. It says in seconds how long to wait before trying again.
- **`Allow`** is on a `405`. It lists the methods the path takes.
- **`Deprecation`**, **`Sunset`** and **`Link`** are on every reply of Module 1's old paths. `Deprecation: @1794355200` says since when the path is deprecated (an RFC 9745 date: `@` and seconds since 1 January 1970). `Sunset: Tue, 11 May 2027 00:00:00 GMT` says when it stops answering (an RFC 8594 date). `Link: </v1/events>; rel="successor-version"` points to the replacement (RFC 8288).

### Problem details, the one error shape

RFC 9457 defines a standard JSON body for errors, with the media type `application/problem+json`. The API uses it for every error of version 1:

```json
{"type":"https://seatwave.example/problems/not-found","title":"No such resource","status":404,"detail":"no such path","instance":"urn:uuid:1afc09c2-38dc-4d4f-b157-63108e461d2c"}
```

`type` names the kind of problem (a client branches on it). `title` is a short fixed summary. `status` repeats the code. `detail` explains this occurrence. `instance` identifies this occurrence; here it is the **correlation id** that also appears in the server's log line, so support can find the log. A 400 adds `errors`: a list of `{pointer, detail}` for each field at fault. The code that builds all of them is in `java-http-walkthrough.md`.

### How the JDK server works

1. `HttpServer.create(address, backlog)` makes the server and binds it: the operating system reserves the port for it. The **backlog** is how many waiting connections the system may queue; 0 means the system's default.
2. `server.createContext(path, handler)` says which handler answers a path prefix. The context `/` matches every path.
3. `server.setExecutor(executor)` says which threads run the handlers. Without it, the handlers run on the server's own single thread, one at a time.
4. `server.start()` starts a thread that accepts connections. For each request it parses the head, builds an `HttpExchange`, and gives the handler to the executor.
5. The handler reads `exchange.getRequestMethod()`, `getRequestURI()`, `getRequestHeaders()` and `getRequestBody()`. It answers with `exchange.getResponseHeaders()` to set headers, `sendResponseHeaders(status, length)` and then writes bytes to `getResponseBody()`.
6. `sendResponseHeaders(status, length)` takes the body length: a positive number means exactly that many bytes follow; 0 means chunked encoding (length not known in advance); -1 means no body at all.
7. Closing the exchange (or its response stream) ends the reply.
8. `server.stop(delay)` closes the listening socket and waits up to `delay` seconds for running handlers.

The JDK server normalizes the case of header names: it stores `Idempotency-key`, `Content-type`, `Retry-after`. That is why the output above shows `Content-type`. Header names are case-insensitive, so clients do not care. This code lower-cases every name it reads, so it does not care either.

## Walkthrough of this repository's use

### Creating the server, binding it to loopback, giving it handlers and an executor

```java
  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final int SYSTEM_BACKLOG = 0;
  private static final int STOP_DELAY_SECONDS = 0;
  // sendResponseHeaders takes -1 for "no body" (204), as the JDK's HttpExchange documents.
  private static final long NO_BODY = -1;
  private static final int NO_CONTENT = 204;
```

`NO_BODY` is the `-1` of step 6. `NO_CONTENT` is the 204 status.

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

- `new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.httpPort())` is the address `127.0.0.1` and the port from the settings. Binding only the loopback address means no other computer can reach the server, which is what a lesson project should do.
- `SYSTEM_BACKLOG` is 0, the default queue length.
- `Executors.newVirtualThreadPerTaskExecutor()` is the executor: each request gets a new virtual thread. The comment gives the reason: a request that waits for the database or the payment provider costs almost nothing, and the in-flight limit, not the thread count, bounds the work.
- `server.createContext("/", api::serve)` registers one handler for all paths; the method reference `api::serve` is an `HttpHandler`.
- `server.start()` begins accepting connections.

### Stopping it

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

`server.stop(0)` stops without waiting. `workers.close()` waits for running requests to finish. `port()` is useful when the settings ask for port 0 (a free port chosen by the system, used by the tests).

### Reading a request

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

Each line has its reason: the header map is lower-cased because of the normalized case; `values.getFirst()` takes the first value of a header; `readNBytes(MAX_BODY_BYTES + 1)` reads at most one byte over the limit; `getRawPath()` and `getRawQuery()` keep the percent-encoding as sent, so the code decodes only once and itself.

### Sending a reply

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

A 204 has no body, so the length is `-1` and the method returns. Otherwise the code sets the `Content-Type`, sends the exact body length, and writes the bytes. Sending the exact length means the reply has a `Content-length` and is not chunked.

### The headers, one by one, in code

`Retry-After` on the busy and storage 503s:

```java
    if (!inFlight.tryAcquire()) {
      return problems
          .reply(ProblemType.SERVICE_BUSY, "too many requests in flight", request.correlationId())
          .withHeader("Retry-After", Long.toString(retryAfter.toSeconds()));
    }
```

`Retry-After` on the payment 503, and the 422 for a reused key:

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

`Location` on a stored `201`, and the content type of a stored reply:

```java
  private static Reply replyOf(StoredReply stored) {
    // Stored errors are problem details; stored successes are plain JSON.
    String contentType =
        stored.status() >= ProblemType.INVALID_REQUEST.status() ? Reply.PROBLEM_JSON : Reply.JSON;
    Reply reply = new Reply(stored.status(), contentType, stored.body(), Map.of());
    return stored.location().isEmpty() ? reply : reply.withHeader("Location", stored.location());
  }
```

`Allow` on a 405:

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

`Deprecation`, `Sunset` and `Link`:

```java
  private Reply deprecated(Reply reply, String successor) {
    return reply
        .withHeader("Deprecation", deprecation)
        .withHeader("Sunset", sunset)
        .withHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
  }
```

The content-type check on a write (the media type may carry a UTF-8 charset parameter, in any letter case):

```java
  // application/json, with at most a UTF-8 charset parameter (RFC 8259 makes UTF-8 the encoding).
  private static final Pattern JSON_MEDIA_TYPE =
      Pattern.compile("application/json\\s*(;\\s*charset=\"?utf-8\"?)?", Pattern.CASE_INSENSITIVE);
```

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

The `Idempotency-Key` header, read and validated:

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

### The JDK's HTTP client

The load tools send requests with the JDK's own client, `java.net.http.HttpClient`:

```java
  ApiClient(int port, Duration timeout) {
    this.base = URI.create("http://127.0.0.1:" + port);
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }
```

```java
  Answer postJson(String path, String key, String json) throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "\"" + key + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build());
  }
```

The `Idempotency-Key` header value is the key wrapped in double quotes, because the contract writes the header as a quoted string.

## Try it yourself

I started the API in a copy of the repository on port 28187 with an in-memory database (`./gradlew -q serve --args=...` with a changed settings file) and the stand-in payment provider on port 28188. Use the ports of your own settings files (8087 and 8088). Ids and times differ in each run.

1. See a whole request and response, with `-v` (request lines start with `>`):

   `curl -v -s -o /dev/null "http://127.0.0.1:28187/v1/events?limit=1"`

```text
> GET /v1/events?limit=1 HTTP/1.1
> Host: 127.0.0.1:28187
> User-Agent: curl/8.7.1
> Accept: */*
< HTTP/1.1 200 OK
< Date: Wed, 11 Nov 2026 01:49:37 GMT
< Content-type: application/json
< Content-length: 124
```

2. Use a `POST` key twice with a different body. The second reply is a 422, with an empty reason phrase:

```text
$ curl -s -i -X POST http://127.0.0.1:28187/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "http-1"' -d '{"eventId":1,"seat":"C-4","customer":"fan_3"}'
HTTP/1.1 422
Date: Wed, 11 Nov 2026 01:49:37 GMT
Content-type: application/problem+json
Content-length: 240
```

3. Send a write without a key; the problem lists the header as the field at fault:

```text
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"one field is invalid","instance":"urn:uuid:e8f986bc-3109-4149-aab3-59bcffa723c3","errors":[{"pointer":"Idempotency-Key","detail":"is required: a quoted string of 1 to 64 of A-Z a-z 0-9 _ -, new for each action"}]}
```

4. Make the payment provider fail, then confirm a hold. The reply is a 503 with `Retry-after: 1`; nothing was booked. Then switch the provider back to `ok` and send the same request with the same key; it works and has a `Location`:

```text
$ curl -s -X POST "http://127.0.0.1:28188/control/mode?mode=fail"
{"mode":"fail"}
$ curl -s -i -X POST http://127.0.0.1:28187/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "http-3"' -d '{"holdId":"<the hold id>"}'
HTTP/1.1 503 Service Unavailable
Date: Wed, 11 Nov 2026 01:49:37 GMT
Retry-after: 1
Content-type: application/problem+json
Content-length: 274
```

```text
HTTP/1.1 201 Created
Date: Wed, 11 Nov 2026 01:49:37 GMT
Content-type: application/json
Content-length: 258
Location: /v1/bookings/641bee5d-0399-488b-a016-8e47a34f51c6
```

5. A method the path does not take. `OPTIONS` on `/v1/holds` is answered with 405 and `Allow: POST`:

```text
HTTP/1.1 405 Method Not Allowed
Date: Wed, 11 Nov 2026 01:49:37 GMT
Allow: POST
```

## Mistakes people make

- **Binding to all addresses.** A server bound to `0.0.0.0` is reachable from the network. Here it is bound to `127.0.0.1` only.
- **Forgetting the body length.** A reply with no length and no chunking leaves the client waiting. `send` always gives the exact length, or -1 for 204.
- **Trusting a header's case.** `Idempotency-Key`, `idempotency-key` and the JDK's `Idempotency-key` are the same header. Lower-case on the way in.
- **Using 200 for everything.** The status code is what programs read. Here each outcome has its code.
- **Retrying a `POST` without a key.** It may do the work twice. The key makes the retry safe.
- **Sending a stack trace in an error.** It tells an attacker how the system is built. Here the body has only fixed titles and an `instance` to quote.
- **Running every handler on one thread.** One slow request blocks all others. The executor gives each request its own virtual thread.

## Check yourself

1. What is the loopback address, and why bind the server to it?
   Answer: `127.0.0.1`, meaning "this machine"; binding there means only programs on the same machine can reach the server.
2. What is the difference between a safe and an idempotent method?
   Answer: a safe method does not change server state; an idempotent method has the same effect when sent once or many times. `DELETE` is idempotent but not safe; `GET` is both; `POST` is neither.
3. What do the three values of the `length` argument of `sendResponseHeaders` mean?
   Answer: a positive number is the exact body size; 0 means chunked encoding; -1 means no body.
4. Which header tells a client how long to wait before a retry, and on which status does this API send it?
   Answer: `Retry-After`; on every 503 the API sends.
5. Why does the code read request headers with lower-cased names?
   Answer: header names are case-insensitive and the JDK server changes their case, so one normal form makes lookups reliable.

## Further reading

- RFC 9110, HTTP Semantics (methods, status codes, headers): https://www.rfc-editor.org/rfc/rfc9110
- RFC 9112, HTTP/1.1 (message format): https://www.rfc-editor.org/rfc/rfc9112
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 8594, The Sunset HTTP Header Field: https://www.rfc-editor.org/rfc/rfc8594
- RFC 9745, The Deprecation HTTP Response Header Field: https://www.rfc-editor.org/rfc/rfc9745
- RFC 8288, Web Linking: https://www.rfc-editor.org/rfc/rfc8288
- IETF draft, The Idempotency-Key HTTP Header Field: https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- Java SE 25 API, `com.sun.net.httpserver`: https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- Java SE 25 API, `java.net.http`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
