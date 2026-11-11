# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "line coverage 86.2 percent (1,979 of 2,297 lines)".

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
tasks.test {
    useJUnitPlatform()
    // The tests read the contract from the repository, the one copy that clients also read.
    systemProperty("seatwave.contract", file("openapi/seatwave-api.yaml").absolutePath)
    systemProperty("seatwave.releasedContract", file("openapi/released/seatwave-api-1.0.0.yaml").absolutePath)
    inputs.dir("openapi")
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run. The test task also passes the contract paths to the tests; that part is explained in `gradle.md`.

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="317" covered="1935"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-api: lines covered ratio is 0.86, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 89 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 1,648 | 11,757 | 85 percent (10,109 covered) |
| Branches | 177 | 645 | 72 percent (468 covered) |
| Lines | 318 | 2,297 | 1,979 lines, 86.2 percent |
| Methods | 21 | 389 | 368 |
| Classes | 5 | 114 | 109 |
| Complexity | 165 | 719 | 554 |

The rule needs 0.80 of 2,297 lines, which is 1,837.6, so at least 1,838 covered lines. The build has 1,979. **The margin is 141 lines.** The README's figures (89 tests, 86.2 percent, 1,979 of 2,297) match this run.

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `api` (the root package) | 36 | 68 | All 25 lines of `ServeCli` (the server that runs until stopped) and 11 in `SeatWaveApi`. |
| `config` | 11 | 139 | Five in `ApiSettings`, three in `SettingsReader`, two in the `Payment` record and one in the `Seed` record. |
| `store` | 6 | 84 | Three in `Database` and three in `DemoSeed`. |
| `idempotency` | 8 | 121 | Five in `IdempotencyStore`, two in `Fingerprint`, one in `StoredReply`. |
| `catalog` | 6 | 181 | Five in `CatalogService` and one in `SeatFilter`. |
| `booking` | 32 | 318 | 25 in `ConfirmService`, three in `HoldCommand`, two in `HoldService`, one each in `HoldId` and `SeatLedger`. |
| `payment` | 35 | 186 | All 24 lines of `PaymentStandInCli`, nine in `PaymentClient` and two in `PaymentStandIn`. |
| `http` | 24 | 714 | Nine in `Version0Routes`, six in `ApiServer` and one in its nested class `Route`, five in `BookingRoutes` and three in `Parameters`. |
| `json` | 6 | 54 | Six in `JsonCodec`. |
| `load` | 154 | 432 | `RetryStormCli` 57, `PageWalkCli` 46, `KeyStoreSizeCli` 44 (all of these are the command-line classes), then five in `RetryStorm` and one each in `PageWalk` and `Latencies`. |

The command-line entry points explain most of the gap: `ServeCli` 25, `PaymentStandInCli` 24, `RetryStormCli` 57, `PageWalkCli` 46 and `KeyStoreSizeCli` 44 make 196 of the 318 missed lines. `Classes` shows 5 missed of 114, and they are exactly those five. The other 122 missed lines are spread over the classes in the table, mostly one to nine lines each; I did not read each one.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

All of these ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 89 `PASSED` lines, `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 11s` and `10 actionable tasks: 10 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  1,648 of 11,757  85%  177 of 645  72%  165  719  318  2,297  21  389  5  114`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="318" covered="1979"/>`.

4. Print the totals of the `load` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.api.load/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  830 of 2,324  64%  48 of 110  56%  40  121  154  432  10  66  3  15`. The `load` package has 432 lines, 154 of them missed, and three missed classes (the three command-line classes).

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-resources`, `jacoco-sessions.html` and one folder per package (`io.plainround.seatwave.api` and its sub-packages `.booking`, `.catalog`, `.config`, `.http`, `.idempotency`, `.json`, `.load`, `.payment` and `.store`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead (here `ServeCli`, `PaymentStandInCli`, `RetryStormCli`, `PageWalkCli` and `KeyStoreSizeCli`).
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 141 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 86.2 percent and not 100?* The command-line classes are never run by a test (196 of the 318 missed lines), and the rest are lines in other classes that no test reaches.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 141: 1,979 covered against 1,838 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
