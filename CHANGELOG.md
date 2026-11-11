# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

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
