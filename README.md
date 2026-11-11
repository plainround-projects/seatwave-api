# seatwave-api

The SeatWave booking API as an OpenAPI 3.1 contract and a Java implementation of it, with a retry test that proves no double booking and no double charge. The contract is the source of truth: `./gradlew check` fails when the document is invalid, when a change would break a client of the released version, and when any reply of the running service does not match the document. Everything listens on 127.0.0.1 only.

## Which module this belongs to

This is the hands-on project of Module 7, "Designing the contract: APIs", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service. Module 1 built its four operations (list events, view seats, hold a seat, confirm a booking) with ad hoc paths, form bodies, plain error strings and no retry safety. This repository turns them into a versioned contract:

- `openapi/seatwave-api.yaml`: version 1 under `/v1`: events and seats with cursor pagination, hold, read, confirm (which takes a payment, as Module 2 requires) and cancel; one error shape, problem details (RFC 9457); an `Idempotency-Key` header on the two writes that create something.
- The implementation on the JDK's own HTTP server, with an idempotency-key table written in the same transaction as each effect.
- A stand-in payment provider on 127.0.0.1 that can be told to capture and then time out, or to fail without capturing.
- Module 1's paths, still answering exactly as before, with `Deprecation`, `Sunset` and `Link` headers.
- The retry storm, the pagination consistency walk, the breaking-change check and the contract tests.

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS (Apple silicon) on 11 November 2026. The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine.
- `curl` for the session below.
- Nothing else for the Java layer: the Gradle wrapper (9.9.1) downloads Gradle and the libraries below on the first build.
- Optional: Docker with Compose, for a second linter (see "Docker Compose (optional)").

## Dependencies and build plugins

Every version is exact. Each was checked on Maven Central on 11 November 2026, and every jar in `gradle.lockfile` was checked against the OSV database on the same day. The versions live in `gradle/libs.versions.toml`; `gradle.lockfile` pins the transitive ones.

| Coordinates | Version | Scope | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | main | Apache-2.0 | The JDK has no success-or-typed-error type, so "seat taken" or "key reused" would have to be an exception. |
| `com.fasterxml.jackson:jackson-bom`, `com.fasterxml.jackson.core:jackson-databind` | 2.23.0 | main | Apache-2.0 | The JDK has no JSON parser. Jackson binds bodies to known records only and refuses unknown fields. The 2.x line is used because the OpenAPI tools use it, so one Jackson version serves every classpath. |
| `com.h2database:h2` | 2.5.252 | main | MPL-2.0 or EPL-1.0 | The JDK has the JDBC interface but no database. |
| `org.slf4j:slf4j-api` | 2.0.20 | main | MIT | The logging interface the guidelines require, with a correlation id in every line. |
| `ch.qos.logback:logback-classic` | 1.6.6 | runtime | EPL-2.0 or LGPL-2.1 | The logging implementation behind SLF4J. |
| `com.atlassian.oai:openapi-request-validator-core` | 3.0.0 | test | Apache-2.0 | Judges each request and reply against the OpenAPI document (status, media type, schema of the body). The JDK cannot read OpenAPI or JSON Schema. It brings `io.swagger.parser.v3:swagger-parser` 2.1.41 and `com.networknt:json-schema-validator` 2.0.1. |
| `org.openapitools.openapidiff:openapi-diff-core` | 2.1.7 | test | Apache-2.0 | Compares two contract versions and says whether the change is compatible. |
| `com.google.guava:guava` (constraint) | 33.8.0-android | test | Apache-2.0 | Not used directly: raises the version the OpenAPI tools bring (32.1.3-android, GHSA-xxph-c9ww-hj94, fixed in 33.7.2). |
| `org.apache.commons:commons-configuration2` (constraint) | 2.15.1 | test | Apache-2.0 | Not used directly: raises the version openapi-diff brings (2.13.0, GHSA-337m-mw94-2v6g, fixed in 2.15.0). |
| `org.junit:junit-bom`, `org.junit.jupiter:junit-jupiter` | 6.1.3 | test | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | test | Apache-2.0 | Fluent assertions with clear failure messages. |
| `org.awaitility:awaitility` | 4.3.0 | test | Apache-2.0 | Waits for a condition (a key row written after a lost reply) instead of sleeping. |
| `com.diffplug.spotless` (Gradle plugin) | 8.11.0 | build | Apache-2.0 | Runs the formatter in the build. |
| `com.google.googlejavaformat:google-java-format` | 2.0.0 | build | Apache-2.0 | One enforced code style. |
| `org.jacoco:org.jacoco.agent` and the JaCoCo plugin | 0.8.16 | build | EPL-2.0 | Measures line coverage and enforces the floor of 0.80. |

After the two constraints, OSV listed no known vulnerability for any of the 87 jars in `gradle.lockfile`. Instancio is not used: the tests need exact seats and keys, not generated ones. HikariCP is not used: an embedded H2 connection costs microseconds to open, and the in-flight limit bounds how many are open.

## Build and test

```
./gradlew clean check
```

This checks formatting with Spotless, compiles with `-Xlint:all -Werror`, runs the tests and fails if line coverage drops below 0.80. Run `./gradlew spotlessApply` first to fix formatting. Every test starts its own API and stand-in provider on 127.0.0.1, on ports the system picks, with an in-memory database.

Result on 11 November 2026 (Temurin 25.0.2, macOS): `BUILD SUCCESSFUL` in 9 seconds, 70 tests, 0 failures, line coverage 85.9 percent (1,935 of 2,252 lines). The untested lines are mostly the `main` methods of the command-line classes, which print and call `System.exit`.

## The contract checks

```
./gradlew -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml'
./gradlew -q diffContract --args='openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-breaking.yaml'
```

```text
openapi/seatwave-api.yaml: valid, every rule passed
openapi/released/seatwave-api-1.0.0.yaml: valid, every rule passed
```

```text
released:  openapi/released/seatwave-api-1.0.0.yaml
candidate: openapi/examples/seatwave-api-1.1.0-breaking.yaml
operations: 0 new, 0 removed, 1 changed
  no changes   POST   /v1/holds                    request incompatible responses no changes
openapi-diff: breaking
house rules: 3 breaking change(s)
  GET /v1/events/{eventId}/seats 200.items[]: field state is no longer always present
  GET /v1/events/{eventId}/seats 200.items[]: field state was removed
  POST /v1/holds request: field channel is newly required
verdict: BREAKING, released clients would fail
```

The second command ends with exit status 3, which Gradle reports as a failed task; its footer is cut here. The same comparison against `seatwave-api-1.1.0-additive.yaml` says "compatible", and so does the current document against the released one, which `BreakingChangeTest` checks on every build.

## The server, the stand-in provider and a session

In terminal 1 and terminal 2:

```
./gradlew -q paymentStandIn --args='config/payment-stand-in.properties'
./gradlew -q serve --args='config/serve.properties'
```

```text
payment stand-in on http://127.0.0.1:8088 for 600 s, mode ok; not a payment system
SeatWave API on http://127.0.0.1:8087 for 600 s (at most 64 requests in flight)
payment provider expected at http://127.0.0.1:8088
```

In terminal 3, a hold, its retry, the same key with another seat, a request without a key, and another fan's key for the same seat:

```text
$ curl -s -i -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-7-hold-1"' -d '{"eventId":1,"seat":"A-7","customer":"fan_7"}'
HTTP/1.1 201 Created
Date: Wed, 11 Nov 2026 01:04:53 GMT
Content-type: application/json
Content-length: 131
Location: /v1/holds/eacf8fd0-6013-4ae7-b679-ff3e54b8f45a

{"holdId":"eacf8fd0-6013-4ae7-b679-ff3e54b8f45a","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T01:09:53.369367Z"}
$ curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-7-hold-1"' -d '{"eventId":1,"seat":"A-7","customer":"fan_7"}'
{"holdId":"eacf8fd0-6013-4ae7-b679-ff3e54b8f45a","eventId":1,"seat":"A-7","state":"held","expiresAt":"2026-11-11T01:09:53.369367Z"}
$ curl -s -i -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-7-hold-1"' -d '{"eventId":1,"seat":"A-9","customer":"fan_7"}'
HTTP/1.1 422 
Date: Wed, 11 Nov 2026 01:04:53 GMT
Content-type: application/problem+json
Content-length: 240

{"type":"https://seatwave.example/problems/idempotency-key-reused","title":"This Idempotency-Key was used with another body","status":422,"detail":"use a new key for a new request","instance":"urn:uuid:2c21cd2b-881c-4b21-9e03-b75b188f75b7"}
$ curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -d '{"eventId":1,"seat":"A-0","customer":"fan 7","price":0}'
{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"2 fields are invalid","instance":"urn:uuid:8e986348-2ebd-43ec-b6a2-73639e663d3c","errors":[{"pointer":"Idempotency-Key","detail":"is required: a quoted string of 1 to 64 of A-Z a-z 0-9 _ -, new for each action"},{"pointer":"/price","detail":"is not a field of this request"}]}
$ curl -s -X POST http://127.0.0.1:8087/v1/holds -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-9-hold-1"' -d '{"eventId":1,"seat":"A-7","customer":"fan_9"}'
{"type":"https://seatwave.example/problems/seat-unavailable","title":"The seat is held or sold","status":409,"detail":"another fan holds or bought this seat","instance":"urn:uuid:3e4ad4bd-0c5c-4769-ae79-5639de8e21a3"}
```

A confirm whose provider captures the charge and then answers too late, then the retry with the same key:

```text
$ curl -s -X POST 'http://127.0.0.1:8088/control/mode?mode=charge_then_timeout'
{"mode":"charge_then_timeout"}
$ curl -s -i -w 'time_total=%{time_total}s\n' -X POST http://127.0.0.1:8087/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-12-pay-1"' -d '{"holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91"}'
HTTP/1.1 503 Service Unavailable
Date: Wed, 11 Nov 2026 01:04:54 GMT
Retry-after: 1
Content-type: application/problem+json
Content-length: 274

{"type":"https://seatwave.example/problems/payment-unavailable","title":"The payment provider did not confirm; retry with the same key","status":503,"detail":"nothing was booked; the same key will not charge twice","instance":"urn:uuid:93a14397-ec80-4a3e-86fd-c63be7047400"}time_total=1.552044s

$ curl -s http://127.0.0.1:8088/control/ledger
{"charges":1,"byReference":{"dcac8635-a6b9-427e-9a0d-3db433531b91":1}}
$ curl -s 'http://127.0.0.1:8087/v1/holds/dcac8635-a6b9-427e-9a0d-3db433531b91'
{"holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91","eventId":1,"seat":"B-12","state":"confirming","expiresAt":"2026-11-11T01:09:53.430Z"}
$ curl -s -X POST 'http://127.0.0.1:8088/control/mode?mode=ok'
{"mode":"ok"}
$ curl -s -i -X POST http://127.0.0.1:8087/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-12-pay-1"' -d '{"holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91"}'
HTTP/1.1 201 Created
Date: Wed, 11 Nov 2026 01:04:55 GMT
Content-type: application/json
Content-length: 259
Location: /v1/bookings/1d9cb85a-ea52-4d31-aa07-8be004452410

{"bookingId":"1d9cb85a-ea52-4d31-aa07-8be004452410","holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91","eventId":1,"seat":"B-12","paymentId":"pay_325943b0-a7ed-4a1f-b1f6-6db5d3c11711","amount":"80.00","currency":"USD","confirmedAt":"2026-11-11T01:04:55.071164Z"}
$ curl -s -X POST http://127.0.0.1:8087/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-12-pay-1"' -d '{"holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91"}'
{"bookingId":"1d9cb85a-ea52-4d31-aa07-8be004452410","holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91","eventId":1,"seat":"B-12","paymentId":"pay_325943b0-a7ed-4a1f-b1f6-6db5d3c11711","amount":"80.00","currency":"USD","confirmedAt":"2026-11-11T01:04:55.071164Z"}
$ curl -s -X POST http://127.0.0.1:8087/v1/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: "fan-12-pay-2"' -d '{"holdId":"dcac8635-a6b9-427e-9a0d-3db433531b91"}'
{"type":"https://seatwave.example/problems/hold-not-active","title":"The hold can no longer be confirmed","status":409,"detail":"the hold is confirmed","instance":"urn:uuid:7f683f1e-23ec-4096-b1db-301735fcbae1"}
$ curl -s http://127.0.0.1:8088/control/ledger
{"charges":1,"byReference":{"dcac8635-a6b9-427e-9a0d-3db433531b91":1}}
```

The guarded script sends one hold several times with one key:

```text
$ scripts/retry-hold.sh 8087 A-8 4 fan-8-hold-1 --run --yes
plan: 4 x POST http://127.0.0.1:8087/v1/holds, seat A-8, Idempotency-Key "fan-8-hold-1"
attempt 1: 201 {"holdId":"c73e70cf-3870-4911-a9f7-c3e47e6079d0","eventId":1,"seat":"A-8","state":"held","expiresAt":"2026-11-11T01:09:55.237481Z"}
attempt 2: 201 {"holdId":"c73e70cf-3870-4911-a9f7-c3e47e6079d0","eventId":1,"seat":"A-8","state":"held","expiresAt":"2026-11-11T01:09:55.237481Z"}
attempt 3: 201 {"holdId":"c73e70cf-3870-4911-a9f7-c3e47e6079d0","eventId":1,"seat":"A-8","state":"held","expiresAt":"2026-11-11T01:09:55.237481Z"}
attempt 4: 201 {"holdId":"c73e70cf-3870-4911-a9f7-c3e47e6079d0","eventId":1,"seat":"A-8","state":"held","expiresAt":"2026-11-11T01:09:55.237481Z"}
seatwave_idempotency_outcomes_total{endpoint="POST /v1/holds",outcome="first"} 5
seatwave_idempotency_outcomes_total{endpoint="POST /v1/holds",outcome="key_reused"} 1
seatwave_idempotency_outcomes_total{endpoint="POST /v1/holds",outcome="replayed"} 4
seatwave_idempotency_keys_stored 9
audit line added to build/retry-hold/audit.log
```

Without `--run` it only prints its plan; it refuses a port below 1024, a seat or a key that does not match the contract, and more than 20 requests.

## The retry storm

```
./gradlew -q retryStorm --args='config/retry-storm.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API and the stand-in provider in this process, on 127.0.0.1; H2 in memory
scenario 1: one hold sent 100 times at once with one key
  final replies by status: {201=100}
  distinct hold ids in the replies: 1; seats the database shows held by them: 1
scenario 2: 500 holds and 500 confirms, each sent twice at once with its own key (2000 requests)
  database: 500 bookings, 0 seats booked twice; provider: 500 charges, 0 holds charged twice
scenario 3: one seat wanted by 100 fans with 100 keys
  final replies by status: {201=1, 409=99}
  database: 1 holder(s) of the seat
all requests: final statuses {201=2101, 409=99}; retries (503, or 409 in progress): 620; idempotency keys stored: 1101
latency of every attempt: p50 19.73 ms, p99 415.78 ms, max 534.14 ms (2820 samples)
```

The API and the provider run inside the storm's process, on 127.0.0.1, with an in-memory database. The proof is read from the database and the provider's ledger, not from the replies.

## The pagination consistency walk

```
./gradlew -q pageWalk --args='config/page-walk.properties'
./gradlew -q pageWalk --args='config/page-walk-inserts.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API in this process on 127.0.0.1; H2 in memory
walk: every available seat of event 1, 500 per page; after each page fans hold 25 seats and the venue adds 5 (same seeded schedule for both walks)
walk      pages  returned  stable seats  skipped  repeated
cursor       40     19611         19026        0         0
offset       39     19264         19050      345         0
cursor pages: 25009 bytes on average, 25557 at most; latency p50 5.65 ms, p99 95.20 ms, max 95.20 ms (40 samples)
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave API in this process on 127.0.0.1; H2 in memory
walk: every available seat of event 1, 500 per page; after each page fans hold 0 seats and the venue adds 25 (same seeded schedule for both walks)
walk      pages  returned  stable seats  skipped  repeated
cursor       42     20538         20000        0         0
offset       43     21050         20000        0       500
cursor pages: 24956 bytes on average, 25582 at most; latency p50 5.80 ms, p99 87.39 ms, max 87.39 ms (42 samples)
```

## What one stored key costs

```
./gradlew -q keyStoreSize --args='100000'
```

```text
H2 file database, compacted: 12,288 bytes with no keys, 23,355,392 bytes with 100,000 keys
bytes per stored key, table and indexes: 233
```

The tool fills a fresh H2 file database under `build/key-size/` with keys that each hold a real hold reply, compacts it, and divides the growth by the number of keys. Another database stores rows differently; measure yours the same way.

## The settings

Every program reads one settings file in `config/`, and every key is required: there are no defaults in code. A missing or invalid key is reported with its name, all of them in one run. The numbers the contract publishes (500 items per page at most, 100 by default, a body of at most 1,024 bytes, a 300-second hold, 24 hours of key retention) are constants in `ContractLimits`, not settings, and a test checks that they match the document.

## Project structure

```text
openapi/       the contract (seatwave-api.yaml), the released 1.0.0 copy, and examples: an additive and a
               breaking 1.1.0 candidate, and Lab 1's holds entry (a broken draft and the solution)
config/        settings for serve, paymentStandIn, retryStorm and pageWalk (keyStoreSize takes a count)
scripts/       retry-hold.sh: one hold sent several times with one key, with a dry run and an audit line
src/main/resources/db/migration/   V1 (Module 1's tables), V2 (keys, payment), V3 (seat order for cursors)
src/main/java/io/plainround/seatwave/api/
  ContractLimits, SeatWaveApi, ServeCli
  config/      SettingsReader, ApiSettings
  store/       Database (migrations, connections), DemoSeed
  idempotency/ IdempotencyKey, Fingerprint, KeyClaim, StoredReply, ClaimResult, IdempotencyStore
  catalog/     CatalogService, Cursors, Page, Position, SeatFilter, EventSummary, SeatSummary, ...
  booking/     HoldService, ConfirmService, SeatLedger, commands, outcomes, Refusal, KeyedReply
  payment/     PaymentClient, PaymentOutcome, PaymentStandIn, PaymentStandInCli, StandInMode, ...
  json/        JsonCodec
  http/        ApiServer, CatalogRoutes, BookingRoutes, Version0Routes, OperationsRoutes, Problems, ...
  load/        RetryStorm, PageWalk, OffsetSeatReader (the rejected alternative), their CLIs, KeyStoreSizeCli
src/test/java/ the tests, one folder per package; contract/ also holds the lint and diff tools
```

## Technology notes

The `tech/` folder explains every technology this repository uses, from zero: Java, HTTP and the JDK server, JSON with Jackson, logging, H2 with SQL and migrations, the OpenAPI contract, the contract validator and diff tools, Gradle and its wrapper scripts, Bash and the retry script, the version catalog, dependency locking, the formatter, the coverage tool, the test libraries, properties files, Docker Compose, EditorConfig and Git. Ten more files walk through the main code and the tests line by line. Start at `tech/README.md`, which gives a reading order.

## What is simplified for teaching

- No authentication: idempotency keys are scoped to the endpoint, not to the caller. A production API scopes them to the authenticated client (Module 38), so one client cannot read another's stored reply.
- One process and an embedded database; the key table lives beside the seats so one transaction covers both. With a separate database the rule is the same: the key row goes in the database that holds the effect.
- A seat whose payment outcome is unknown stays CONFIRMING until a retry settles it. A production system also runs a reconciliation job that asks the provider and settles or refunds such seats.
- No trace context is propagated to the provider, and metrics are kept in memory and written as Prometheus text, not exported.
- The JDK server sends an empty reason phrase for 422, which HTTP/1.1 allows; clients read the status code.

## Continuous integration

There is no CI configuration on purpose: the course builds and checks each project locally, with the commands above.

## Docker Compose (optional)

`compose.yaml` runs Redocly CLI 2.77.0, pinned by tag and digest (checked on Docker Hub on 11 November 2026), as a second linter over `openapi/`, read-only, with no network and no Linux capabilities: `docker compose run --rm lint`.

[not run] Docker was not running on the verification machine, and the course brief for this module says not to start it:

```text
$ docker info
ERROR: Cannot connect to the Docker daemon at unix://~/.docker/run/docker.sock. Is the docker daemon running?
errors pretty printing info
```

## License

MIT; see `LICENSE`.
