# Technology notes for seatwave-api

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal, a build tool, a test, a database or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, try it yourself, mistakes people make, check yourself, and further reading. This index is the one file without them.

The repository is the hands-on project of Module 7, "Designing the contract: APIs", of a system design course. It holds the SeatWave ticket-booking API as an OpenAPI 3.1 contract, and a Java 25 implementation of it on the JDK's own HTTP server, with JSON through Jackson, an embedded H2 database, idempotency keys stored in the same transaction as their effect, cursor pagination, a stand-in payment provider on the loopback address, and retry tests that prove no double booking and no double charge. It is built with Gradle, has one Bash script, and has one optional Docker Compose file.

## The files

**The language and the libraries**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, method, exception, records, enums, sealed interfaces, `switch` with patterns, generics, lambdas, streams, `Optional`, try-with-resources, `Duration` and `Clock`, regular expressions, virtual threads and the small concurrency tools. |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, every place the main code uses it, and why expected failures are values here and exceptions are kept for bugs. |
| `http-and-the-jdk-server.md` | HTTP from zero (requests, replies, status codes, headers, media types) and the JDK's built-in HTTP server that SeatWave runs on. |
| `jackson.md` | JSON and Jackson 2.23.0: how a request body becomes a Java record, and how unknown fields, wrong types and duplicates are refused. |
| `slf4j-and-logback.md` | Logging with SLF4J 2.0.20 and Logback 1.6.6: the log line with its correlation id. |

**The data formats**

| File | Role |
|---|---|
| `h2-sql-and-migrations.md` | A relational database, tables, keys, constraints, transactions and row locks from zero; H2 2.5.252; every file under `src/main/resources/` (the three migrations and `logback.xml`), why migrations only add, and how they run at start-up. |
| `openapi-contract.md` | What an API contract is, YAML basics, the OpenAPI 3.1 structure, and every file under `openapi/` line by line: the current contract, the released copy, the additive and the breaking 1.1.0 candidates, and the two Lab 1 files. |
| `java-properties.md` | The `.properties` format and the five settings files under `config/`, key by key, with what changes when a value changes. |

**The code walkthroughs** (each quotes the source lines and explains them)

| File | Role |
|---|---|
| `java-root-and-config-walkthrough.md` | The root package (`ContractLimits`, `SeatWaveApi`, `ServeCli`) and the `config` package (`SettingsReader`, `ApiSettings`). |
| `java-payment-and-store-walkthrough.md` | The `payment` package (the client, the provider key made from the hold, the stand-in provider and how it is told to time out or fail) and the `store` package (opening H2, migrating, one connection per unit of work, the demo seed). |
| `java-idempotency-walkthrough.md` | The `idempotency` package: what a key is, why the key and its effect share a transaction, a different body, a race, a lost reply, an expired key. |
| `java-booking-walkthrough.md` | The `booking` package: the seat states, the conditional `UPDATE` that makes a hold safe, confirm in three steps with a payment, cancel, and where each transaction begins and ends. |
| `java-catalog-walkthrough.md` | The `catalog` package: events and seats in pages, cursors and why they beat offsets. |
| `java-http-walkthrough.md` | The `http` and `json` packages: the server, the routes of version 1 and version 0, the one error mapping, the metrics, and the JSON codec. |
| `java-load-walkthrough.md` | The `load` package: the retry storm, the pagination walk, the rejected offset reader, and the key-size tool. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle 9.9.1 and `build.gradle.kts`: the plugins, the dependencies, and the tasks (`serve`, `paymentStandIn`, `retryStorm`, `pageWalk`, `keyStoreSize`, `lintContract`, `diffContract`). |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 0.80 floor. |

**The tools around the lab**

| File | Role |
|---|---|
| `bash-retry-hold.md` | `scripts/retry-hold.sh`: the guarded Bash script that sends one hold several times with one key, with a dry run and an audit line. |
| `docker-compose.md` | `compose.yaml`, the optional second linter for `openapi/` in a container, and the YAML it is written in. |
| `editorconfig.md` | `.editorconfig`, the shared editor settings. |
| `git.md` | `.gitignore` and `.gitattributes`. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit Jupiter 6.1.3, the test engine. |
| `assertj.md` | AssertJ 3.27.7, the assertion library. |
| `awaitility.md` | Awaitility 4.3.0, waiting for a condition instead of sleeping. |
| `openapi-validator-and-diff.md` | The OpenAPI request validator 3.0.0 and openapi-diff 2.1.7: how a reply is judged against the contract and two contract versions are compared. |
| `tests-contract-walkthrough.md` | The `contract` test package line by line: document rules, conformance of replies, breaking-change checks and the lint and diff commands. |
| `tests-booking-and-idempotency-walkthrough.md` | The `booking` and `idempotency` test packages: the retry tests and the failing key write. |
| `tests-other-packages-walkthrough.md` | The remaining test packages and the shared test helpers: catalog, config, http, load, payment, store. |

## Reading order

1. **The language first.** `java.md`, then `result4j.md`, then `java-properties.md` (the settings every program reads).
2. **The web and the data.** `http-and-the-jdk-server.md`, `jackson.md`, `slf4j-and-logback.md`, then `h2-sql-and-migrations.md` and `openapi-contract.md`.
3. **The code, from the edge to the middle.** `java-root-and-config-walkthrough.md`, `java-payment-and-store-walkthrough.md`, `java-idempotency-walkthrough.md`, `java-booking-walkthrough.md`, `java-catalog-walkthrough.md`, `java-http-walkthrough.md`, `java-load-walkthrough.md`.
4. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
5. **The tools around the lab.** `bash-retry-hold.md`, `docker-compose.md`, `editorconfig.md`, `git.md`.
6. **The tests last.** `junit.md`, `assertj.md`, `awaitility.md`, `openapi-validator-and-diff.md`, then the three `tests-*-walkthrough.md` files.

## If you have ten minutes

Read these three, in this order:

1. `openapi-contract.md`: what the contract promises, the header and the idempotency rules in the `description`, and the difference between an additive and a breaking change.
2. `java-idempotency-walkthrough.md`: the "How it works underneath" section, which explains why a key and its effect share one transaction and what happens in a race, a lost reply and an expired key.
3. `java-booking-walkthrough.md`: the "How it works underneath" section, which explains the seat states, the conditional `UPDATE` and the three steps of a confirm.

## About the examples

Timings and generated ids differ on every run. Docker (`compose.yaml`) and the Windows script (`gradlew.bat`) were not run on the verification machine, and the notes that cover them say so. Where a note says "I did not run" an example, that example was not run.
