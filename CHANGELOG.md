# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-11

### Added

- The `tech/` notes: one beginner-level file per technology the repository uses, and walkthroughs
  of the main code and the tests line by line.
- Migration `V4__seats_by_hold.sql`: an index on `seats.hold_id`, which every hold lookup filters
  on.
- Tests for every fix below: `JsonCodecTest`, `ReplyTest` and `RetryHoldScriptTest`, and new cases
  in `ContractConformanceTest`, `ApiSettingsTest`, `IdempotencyStoreTest` (two requests with one
  expired key, forced to interleave), `DatabaseTest` (the query plan, and start-up after a crash
  between a script and its history row), `BreakingChangeTest` and `OpenApiDocumentTest`.

### Fixed

- A number or a boolean sent for a text field, such as `"customer":5`, was accepted and stored as
  text; it is now refused at the field's pointer.
- An empty body, a body that is not an object and data after the object were reported at pointer
  `/` as "has the wrong type"; they are now reported at the whole-document pointer `""` with what
  is actually wrong.
- A number too large for its field was reported as "the body is not valid JSON"; it is now
  reported at the field's pointer as out of range. An unknown field name with `/` or `~` is now
  escaped in its pointer, as RFC 6901 requires.
- An unknown query parameter hid the other problems of the request (`?limit=0&foo=1` named only
  `foo`); every problem is now in one reply.
- A missing or unreadable settings file was reported only by its path; the message now says that
  the file does not exist or cannot be read.
- The two rules between settings keys (the lease and the timeouts, the sunset and the deprecation)
  were reported one at a time; they are now reported with every other problem in one run.
- Two requests with the same expired Idempotency-Key at the same moment could leave the second
  with nothing to complete and an internal error; the expired key is now taken over by one
  conditional update, so one request does the work and the other gets its reply.
- No index covered `seats.hold_id`, so every hold lookup scanned the seats table.
- A crash between a migration script and its history row made the next start fail; every
  migration statement is now safe to run again.
- The stand-in provider used the name `REPLAYED` for a plain 200 on its control endpoints; those
  now use `OK`.
- The house breaking-change check judged a reply by its first media type instead of the same media
  type by name, and did not flag a value added to a response enum. Tests now cover a removed
  status, a removed request field and a new required parameter.
- Lab 1's broken draft had four mistakes, not three; the linter now reports the fourth (a required
  field that is never defined), and the file and the README say four.
- The README did not say why both the library diff and the house rules run.
- `scripts/retry-hold.sh --run` without `--yes` and without a terminal exited without saying
  "nothing sent"; it now says so and exits with status 1.
- The dry run of `scripts/retry-hold.sh` printed the curl command without its quotes; the line can
  now be pasted.
- `build.gradle.kts` declared Logback twice for the tests; the test runtime inherits it.
- `Reply` promised to keep its headers in order but copied them into an unordered map; it now keeps
  the order they were added in.

## [1.0.0] - 2026-11-11

### Added

- The SeatWave booking API contract, `openapi/seatwave-api.yaml` (OpenAPI 3.1.2): list events and
  view seats with cursor pagination, hold, read, confirm and cancel, health and metrics, one problem
  details error shape (RFC 9457), Idempotency-Key on the two creating writes (the IETF draft's
  RFC 9651 String), a 24-hour key retention, and Module 1's paths marked deprecated.
- The released copy, `openapi/released/seatwave-api-1.0.0.yaml`, and two example candidates, one
  additive and one breaking, for the breaking-change check.
- A Java implementation on the JDK's HTTP server, bound to 127.0.0.1, one virtual thread per request
  and an in-flight limit that answers 503 with Retry-After.
- An idempotency-key table keyed by endpoint and key, with a fingerprint of the request and the
  stored reply, written in the same transaction as the effect; expired keys purged once a minute.
- Confirm with payment: a lease on the seat while the provider is called outside any transaction,
  and a provider key made from the hold, so one hold is charged at most once.
- A stand-in payment provider on 127.0.0.1 that deduplicates by key and can be told to capture then
  time out, or to fail without capturing.
- Module 1's four paths, unchanged in shape, with Deprecation, Sunset and Link headers.
- Versioned SQL migrations (expand only): Module 1's schema, the key table and payment columns, and
  the seat-number column the cursors use.
- Contract tests that judge every reply against the document, the house lint rules, the
  breaking-change check, the retry tests (repeats, a different body, 100 racing retries, a lost
  reply, an expired key, a crash between the effect and the key write) and the payment tests.
- The retry storm (`retryStorm`), the pagination consistency walk (`pageWalk`) and the measurement of
  what one stored key costs on disk (`keyStoreSize`).
- `scripts/retry-hold.sh`: one hold sent several times with one key, a dry run by default, a
  confirmation, an allow-list and an audit line.
- An optional Docker Compose file running Redocly CLI 2.77.0, pinned by tag and digest.
- Build with a pinned Gradle wrapper, dependency lock files, `-Xlint:all -Werror`, Spotless with
  google-java-format, and a JaCoCo line-coverage floor of 0.80 with an XML report.
