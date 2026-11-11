# Gradle 9.9.1 (build tool) and the Kotlin DSL

## What it is

Source code is text. A computer cannot run text until other programs have turned it into something runnable. A **build tool** is the program that does all those steps in the right order, from one command: it fetches the libraries the code needs, compiles the Java source files into **class files** (the form the Java Virtual Machine, the JVM, can run), runs the tests, checks the code style and measures the test coverage. **Gradle** is a build tool for Java. You tell it what you want (for example `check`) and it works out which smaller steps are needed.

A Gradle build is described in files written in a language. This repository uses the **Kotlin DSL**: the build file is a Kotlin script. "DSL" means domain-specific language, a small language made for one job. A file that ends in `.kts` is a Kotlin script, a Kotlin program that is run directly instead of being compiled first.

A **terminal** is a window where you type commands. The word after the program name, such as `check` in `./gradlew check`, is the name of a **task**. A task is one unit of work Gradle knows how to do.

## Why this repository uses it

The hands-on project of Module 7 must build and test with one command and the same result on every machine. Gradle gives that: `./gradlew check` checks the formatting of the code, compiles it with every warning turned into an error, runs 70 tests and fails if the line coverage is below 80 percent. The tests include the contract checks: they read the OpenAPI document from the `openapi/` folder and judge every reply of the running service against it. The build file also gives each of the five programs (the server, the payment stand-in, the retry storm, the page walk and the key-store size measurement) its own task, and the two contract tools (the lint and the diff) theirs, so a learner types one short command instead of assembling a long Java command line. It keeps those runs out of `check`, so the gate takes seconds.

## Where it appears

| Path | What it does with Gradle |
| --- | --- |
| `settings.gradle.kts` | Names the project and says where libraries may be downloaded from. |
| `build.gradle.kts` | The build itself: plugins, Java version, libraries, compiler flags, formatter, coverage, the test task, the seven program tasks. |
| `gradle/libs.versions.toml` | The version catalog: one place for every version (`toml-version-catalog.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | Pinned versions of every library, including the ones libraries bring along (`dependency-locking.md`). |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The wrapper that downloads and starts the right Gradle (`gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`). |
| `build/` and `.gradle/` | Folders Gradle creates while it works. Git ignores them (`git.md`). |

## How it works underneath

1. You run `./gradlew check`. The wrapper starts a **Gradle process**, a Java program that stays alive in the background for a while (the **daemon**) so the next command starts quickly.
2. Gradle reads `settings.gradle.kts` first. That file decides which projects are part of the build. Here there is one project.
3. Gradle then reads `build.gradle.kts`. Running the file does not build anything. It only **configures**: it creates tasks and tells each one what it depends on.
4. Gradle looks at the task you asked for and at everything it depends on, and builds a **task graph**: a list of tasks in a legal order, each after the tasks it needs.
5. It runs the tasks. For each task it checks whether the inputs (source files, settings) and outputs (class files, reports) are the same as last time. If yes, the task is marked `UP-TO-DATE` and skipped.
6. Everything it makes goes into the `build/` folder. Downloaded libraries are kept in a cache folder in your home directory (`~/.gradle`), shared by all projects.

A **plugin** is a package of tasks and settings that you switch on in one line. The `java` plugin adds the tasks that compile and test Java. A **dependency** is a library your code needs. A **configuration** is a named list of dependencies for one purpose, such as "what the tests need at run time". A **repository** is a server that stores libraries; Maven Central is the big public one.

## Walkthrough of this repository's use

### `settings.gradle.kts`

```kotlin
// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "seatwave-api"
```

- Line 1 is a comment: `//` starts a comment in Kotlin. It says that one place decides where libraries come from.
- `dependencyResolutionManagement { ... }` is a block. A block is a name followed by braces; everything inside configures that one topic. This block configures how Gradle **resolves** dependencies, which means finding the exact file for each library.
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` makes the build fail if `build.gradle.kts` ever adds a repository of its own. All repositories must be declared here. This stops a project from quietly pulling libraries from an unreviewed server.
- `repositories { mavenCentral() }` lists the only allowed repository: Maven Central. The build file therefore also cannot add a snapshot server or a company mirror without editing this file in plain sight.
- `rootProject.name = "seatwave-api"` names the project. The name appears in reports, for example "Rule violated for bundle seatwave-api".

### The plugins block

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}
```

- `plugins { ... }` lists the plugins to switch on.
- `java` adds the Java tasks: `compileJava`, `test`, `jar` and the `check` task.
- `jacoco` adds the coverage tool (`jacoco.md`).
- `alias(libs.plugins.spotless)` switches on the Spotless formatter plugin. `alias` means "use the plugin described in the version catalog". `libs.plugins.spotless` points at the `spotless` line in the `[plugins]` section of `gradle/libs.versions.toml`, which holds the plugin id and its version (`toml-version-catalog.md`).

### Project coordinates

```kotlin
group = "io.plainround"
version = "1.0.0"
```

`group` and `version` are the project's own identity in the Maven style: a group, a name (from `settings.gradle.kts`) and a version. They would matter if the project were published as a library. Here they only label the build. The version `1.0.0` is also the version of the contract the repository releases first (`openapi/released/seatwave-api-1.0.0.yaml`).

### The Java toolchain

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A **toolchain** is the exact JDK (Java Development Kit) that builds the code. `languageVersion = JavaLanguageVersion.of(25)` says "use Java 25". Gradle looks for an installed JDK 25 on the machine and uses it for compiling and for running tests, even if the JDK that started Gradle is different. This build does not configure an automatic download of a JDK, so a JDK 25 must be installed (the README names Eclipse Temurin 25.0.2). The `java { toolchain { ... } }` nesting is a block inside a block: `java` is the extension that the `java` plugin added, and `toolchain` is one of its parts.

### Dependencies and configurations

```kotlin
dependencies {
    implementation(libs.result4j)
    // One Jackson version for every Jackson jar on every classpath, including the validator's.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.h2)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    // The contract checks: the validator reads the OpenAPI document and judges every reply;
    // the diff compares the document with the last released one.
    testImplementation(libs.openapi.validator)
    testImplementation(libs.openapi.diff)
    constraints {
        // The OpenAPI tools bring versions with known vulnerabilities; these raise them to fixed ones.
        testImplementation(libs.guava)
        testImplementation(libs.commons.configuration)
    }
    testRuntimeOnly(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

A name like `implementation`, `runtimeOnly`, `testImplementation` or `testRuntimeOnly` is a **configuration**. The `test` prefix means "only for the test code". `Implementation` means "needed to compile and run". `RuntimeOnly` means "needed only when running, not when compiling". A **classpath** is the list of jar files and folders the JVM searches for classes. Each line below puts one library on one classpath. `libs.something` is a catalog entry (`toml-version-catalog.md`).

- `implementation(libs.result4j)` puts the result4j library on the classpath of the main code. It expands to `com.github.sviperll:result4j:1.2.0`. The settings reader, the services and the payment client use it so that a bad setting or a refused request is a value, not an exception (`result4j.md`).
- Line 18 is a comment: one Jackson version for every Jackson jar on every classpath, including the validator's. Jackson is the JSON library (`jackson.md`).
- `implementation(platform(libs.jackson.bom))` brings in a **BOM**, a bill of materials. A BOM is a small published file that lists the matching versions of many libraries of one family. `platform(...)` tells Gradle "use this list to decide versions". The Jackson family has many jars, and the OpenAPI libraries below bring older ones of their own. Because the BOM is a platform, Gradle uses 2.23.0 for every Jackson jar, including the ones the OpenAPI tools asked for at 2.11, 2.18, 2.19 and 2.21. I checked this in the dependency tree: lines such as `com.fasterxml.jackson.core:jackson-databind:2.19.0 -> 2.23.0` show the arrow "asked for 2.19.0, got 2.23.0".
- `implementation(libs.jackson.databind)` is the JSON library itself. The catalog line has no version; the BOM supplies it.
- `implementation(libs.h2)` is the H2 database engine, which runs inside the program (`h2-sql-and-migrations.md`).
- `implementation(libs.slf4j.api)` is the logging interface (`slf4j-and-logback.md`). `runtimeOnly(libs.logback.classic)` is the logging engine behind it. The code is compiled only against the interface, so the engine is `runtimeOnly`.
- `testImplementation(platform(libs.junit.bom))` brings in the JUnit BOM, so every JUnit library is 6.1.3 without a version of its own.
- `testImplementation(libs.junit.jupiter)` adds the JUnit Jupiter bundle: the annotations and the engine that finds and runs the tests (`junit.md`).
- `testImplementation(libs.assertj.core)` adds AssertJ, the readable assertions (`assertj.md`).
- `testImplementation(libs.awaitility)` adds Awaitility, which waits for a condition instead of sleeping (`awaitility.md`).
- Lines 29 and 30 are comments: the validator reads the OpenAPI document and judges every reply; the diff compares the document with the last released one.
- `testImplementation(libs.openapi.validator)` and `testImplementation(libs.openapi.diff)` add the two OpenAPI libraries (`openapi-validator-and-diff.md`). Both are test-only on purpose: the service itself never ships them. That is why the lint and diff tasks further down run from the test classpath.
- `testRuntimeOnly(libs.logback.classic)` names the logging engine for the test runs. I checked that the tests would still see it without this line, because the test classpath already inherits the main code's `runtimeOnly` libraries, so the line only states the need again.
- `testRuntimeOnly(libs.junit.platform.launcher)` adds the JUnit Platform launcher, the piece Gradle calls to start the tests. Gradle 9 expects the project to declare it. It is needed only when tests run. Its version comes from the BOM.

### The constraints block

Lines 33 to 37 of the listing above are a block of **dependency constraints**:

```kotlin
    constraints {
        // The OpenAPI tools bring versions with known vulnerabilities; these raise them to fixed ones.
        testImplementation(libs.guava)
        testImplementation(libs.commons.configuration)
    }
```

- A **constraint** does not add a library. It says: if some library brings this one in, then use at least this version. Here both entries are of the `testImplementation` kind, so they apply to the test classpaths.
- The comment on line 34 gives the reason: the OpenAPI tools bring versions with known vulnerabilities, and these two lines raise them to fixed ones.
- `libs.guava` is `com.google.guava:guava:33.8.0-android`. The tools ask for Guava 32.1.3-android (through swagger-core) and 28.2-android (through json-schema-validator). The catalog says the advisory GHSA-xxph-c9ww-hj94 is fixed in Guava 33.7.2.
- `libs.commons.configuration` is `org.apache.commons:commons-configuration2:2.15.1`. openapi-diff asks for 2.13.0. The advisory GHSA-337m-mw94-2v6g is fixed in 2.15.0.
- I checked the effect with `dependencyInsight`: the output lists `com.google.guava:guava:32.1.3-android` and `28.2-android` as requested versions that end at `33.8.0-android`, and `org.apache.commons:commons-configuration2:2.13.0 -> 2.15.1`, with the selection reason `By constraint`.
- Neither library is used by the tests directly, so nothing in `src/test` imports them. They are in the build only to lift the versions.

### Dependency locking

```kotlin
// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}
```

Libraries bring other libraries (their **transitive dependencies**). `lockAllConfigurations()` makes Gradle write the exact version of every library of every configuration into `gradle.lockfile`, and read that file on every later build. If the resolved versions would differ from the lock, the build fails. The whole story, with every line of both files, is in `dependency-locking.md`.

### The Java compiler flags

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

- `tasks.withType<JavaCompile>().configureEach { ... }` means: for every task of the type `JavaCompile` (there is one for main code and one for test code), run this block. `<JavaCompile>` is a Kotlin type argument, the type to select. `configureEach` applies the block to each one lazily, only when needed.
- `options.encoding = "UTF-8"` makes the compiler read source files as UTF-8 text, so the result does not depend on the machine's default.
- `options.release = 25` makes the compiler accept only Java 25 language features and use only Java 25 library classes.
- The comment on line 50 gives the reason for the next line: "Every warning is an error: a warning that is allowed to stay is a defect nobody owns."
- `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))` passes two flags to `javac`, the Java compiler. `-Xlint:all` turns on every optional warning (raw types, unchecked casts, an auto-closeable resource that is never used, missing serial ids and more). `-Werror` turns each warning into an error, so the build stops. The flags apply to the test code too.
- I ran an experiment in a scratch copy: a new class with the raw types `java.util.List` and `java.util.ArrayList` made `compileJava` fail. The output had `warning: [rawtypes] found raw type: List`, then `error: warnings found and -Werror specified`, `1 error` and `2 warnings`.

### Spotless and the formatter

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

Spotless is a Gradle plugin that checks and rewrites source files to one layout (`spotless-google-java-format.md`).
- `spotless { java { ... } }` configures Spotless for Java files.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` chooses the formatter. `libs.versions.googleJavaFormat` is the version entry `googleJavaFormat = "2.0.0"` from the catalog, and `.get()` turns it into the text `2.0.0`.
- `removeUnusedImports()` deletes `import` lines the file never uses.
- `endWithNewline()` makes every file end with one line break.
- Spotless adds the tasks `spotlessCheck` (fails if a file would change) and `spotlessApply` (changes the files). The `check` task depends on `spotlessCheck`, which is how formatting becomes part of the gate.

### JaCoCo, the coverage tool

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`jacoco { toolVersion = ... }` fixes the JaCoCo version to the catalog entry `jacoco = "0.8.16"`. Without it, Gradle would pick a default version. `jacoco.md` explains what the tool measures.

### The test task

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

- `tasks.test { ... }` configures the existing task named `test`.
- `useJUnitPlatform()` tells it to run tests through the JUnit Platform. Without it Gradle would look for older JUnit 4 tests and find none.
- The comment on line 68 says why the next two lines exist: the tests read the contract from the repository, the one copy that clients also read.
- `systemProperty("seatwave.contract", file("openapi/seatwave-api.yaml").absolutePath)` sets a **system property**, a named text value that the test JVM can read with `System.getProperty`. `file("openapi/seatwave-api.yaml")` is a Gradle helper that makes a path relative to the project folder, and `.absolutePath` turns it into a full path. The class `Contract` reads this property to find the current contract (`tests-contract-walkthrough.md`).
- `systemProperty("seatwave.releasedContract", file("openapi/released/seatwave-api-1.0.0.yaml").absolutePath)` does the same for the last released contract. `BreakingChangeTest` compares the two.
- `inputs.dir("openapi")` tells Gradle that the whole `openapi` folder is an input of the test task. Gradle skips a task when its inputs did not change. Without this line, an edit to the contract would not make Gradle run the tests again, because the YAML is not on the classpath. With it, any change in that folder reruns the tests.
- `testLogging { events("passed", "failed", "skipped") }` prints one line per test result while the task runs, for example `FingerprintTest > keepsTheEndpointAndTheBodyApart() PASSED`. Without it, only failures show.
- `finalizedBy(tasks.jacocoTestReport)` means: after `test` finishes, always run `jacocoTestReport`, even if tests failed. That creates the coverage report in `build/reports/jacoco/test/html`.

There is no `maxParallelForks` and no `forkEvery`, so Gradle runs all test classes in one test JVM, one test class after another. Each test that needs a server starts its own on a port the system picks, so no two tests share a socket.

### The XML coverage report

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

`tasks.jacocoTestReport { ... }` configures the task that writes the coverage report. `reports { xml.required = true }` switches on the **XML report**. By default the task writes only the HTML report, which people read in a browser. XML is a text format made of tags such as `<counter type="LINE" missed="317" covered="1935"/>`; a program can read it, so the exact numbers can be copied into the README without counting by eye. The file lands in `build/reports/jacoco/test/jacocoTestReport.xml`.

### The coverage rule wired into `check`

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

- `tasks.jacocoTestCoverageVerification` is the task that checks coverage against a rule. `dependsOn(tasks.test)` makes sure the tests have run, so there is coverage data to read.
- `violationRules { rule { limit { ... } } }` is the rule. A **limit** names a counter, here `counter = "LINE"`, and a bound. `minimum = "0.80".toBigDecimal()` means the covered share of lines must be at least 0.80. `.toBigDecimal()` turns the text into an exact decimal number, which the plugin requires. The comment explains that the untested remainder is the command-line entry points.
- `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` adds the coverage check to `check`. By default `check` does not run it, so this line is what makes the floor a real gate. I proved it in a scratch copy: with the minimum raised to 0.95 the build failed with `Rule violated for bundle seatwave-api: lines covered ratio is 0.85, but expected minimum is 0.95`.
- `check` is the standard "is this project healthy" task. It runs the tests, `spotlessCheck` and the coverage verification. None of the `JavaExec` tasks of the next sections is a dependency of it.

### The five program tasks

```kotlin
// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
val entryPoints = mapOf(
    "serve" to "io.plainround.seatwave.api.ServeCli",
    "paymentStandIn" to "io.plainround.seatwave.api.payment.PaymentStandInCli",
    "retryStorm" to "io.plainround.seatwave.api.load.RetryStormCli",
    "pageWalk" to "io.plainround.seatwave.api.load.PageWalkCli",
    "keyStoreSize" to "io.plainround.seatwave.api.load.KeyStoreSizeCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
    }
}
```

- The first comment line says what the block is for: "One command per example: each entry point gets its own task, so a reader never assembles a classpath."
- `val entryPoints = mapOf(...)` creates a read-only map in Kotlin: each pair is a task name and the full name of a class that has a `main` method (an **entry point**, the place where a Java program starts). `"a" to "b"` builds one pair. The five pairs are:
  - `serve` runs `ServeCli`, the API server itself, read from a settings file.
  - `paymentStandIn` runs `PaymentStandInCli`, the stand-in payment provider on the loopback address.
  - `retryStorm` runs `RetryStormCli`, the many-retries run that counts double effects in the database and the ledger.
  - `pageWalk` runs `PageWalkCli`, the cursor walk against the offset walk.
  - `keyStoreSize` runs `KeyStoreSizeCli`, which measures what one stored idempotency key costs in bytes.
- `entryPoints.forEach { (taskName, entryPoint) -> ... }` runs the block once per pair. The round brackets `(taskName, entryPoint)` take the pair apart into its two parts.
- `tasks.register<JavaExec>(taskName) { ... }` creates a new task of the type `JavaExec`, which runs a Java program. `register` creates it lazily.
- `group = "application"` files the task in the `application` group in the `./gradlew tasks` list. `description = "Runs $entryPoint."` is the text shown next to it; `$entryPoint` inserts the variable into the text.
- `classpath = sourceSets.main.get().runtimeClasspath` gives the program the compiled main classes plus the libraries they need (Jackson, H2, SLF4J, Logback, result4j). A **source set** is a named group of source folders; `main` is `src/main/java`. Because `JavaExec` depends on the classpath, Gradle compiles the code first.
- `mainClass = entryPoint` names the class to start.
- The comment "Paths in --args are read from the project folder, wherever Gradle was started." and `workingDir = rootDir`: a **working directory** is the folder a program treats as "here": a relative path such as `config/serve.properties` is looked for from it. `rootDir` is the folder that holds `settings.gradle.kts`, the project folder.

### The two contract tasks

```kotlin
// The contract tools run from the test classpath, where the validator and the diff live,
// so the service itself never ships them.
val contractTools = mapOf(
    "lintContract" to "io.plainround.seatwave.api.contract.LintContractCli",
    "diffContract" to "io.plainround.seatwave.api.contract.DiffContractCli",
)

contractTools.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Runs $entryPoint."
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = entryPoint
        workingDir = rootDir
    }
}
```

This block has the same shape as the one above, with three differences.
- The comment on lines 122 and 123 gives the reason: the contract tools run from the **test classpath**, where the validator and the diff live, so the service itself never ships them.
- `lintContract` runs `LintContractCli` and `diffContract` runs `DiffContractCli`. Both classes live in `src/test/java/.../contract/` (`tests-contract-walkthrough.md`).
- `group = "verification"` files them under `Verification tasks`, next to `check`, because they judge a contract.
- `classpath = sourceSets.test.get().runtimeClasspath` is the other difference. It is the runtime classpath of the **test** source set: the compiled main classes, the compiled test classes and every test library, including the OpenAPI ones. That is the only classpath that has the validator and the diff.

None of the seven `JavaExec` tasks depends on `check`, and `check` depends on none of them. The program tasks run only when you ask. The contract is still guarded in `check`, because `OpenApiDocumentTest` and `BreakingChangeTest` call the same code as these two tasks.

**Why the program runs are their own tasks and not part of `check`.** The retry storm starts a server and many threads, the page walk times itself, and the server runs until it is stopped. A table of timings is not a pass-or-fail check anyway: a person reads it. So the tests check that the machinery is right and finish in seconds, and the tasks make the real runs when asked. The cost: the `main` methods are not covered by tests, which is why the coverage floor is 0.80 and not higher (`jacoco.md`).

### The commands you type

- `./gradlew check` runs the full gate. `./` means "the file `gradlew` in this folder".
- `./gradlew spotlessApply` rewrites source files into the formatter's layout. Run it before `check` if `spotlessCheck` complains.
- `-q` (`--quiet`) hides Gradle's own messages, so only the program's output shows.
- `--args='...'` passes the text after it to the program's `main` method as arguments. Gradle splits the text at spaces into separate arguments. `./gradlew -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml'` therefore gives `LintContractCli` two arguments, the two files.
- `--offline` makes Gradle use only what is in its cache and touch no network. I used it for every command in these notes.
- When a program calls `System.exit` with a non-zero value, Gradle reports `Execution failed for task ':lintContract'` (the name of the task) and exits with 1 itself. The tools exit with 3 for "problems found", and Gradle prints `finished with non-zero exit value 3`.

## Try it yourself

All commands below ran in a scratch copy of this repository on 11 November 2026 (Temurin 25.0.2, Gradle 9.9.1). Timings differ on every run.

1. List the tasks that run the programs:

```
./gradlew --offline tasks --group application
```

   The list starts with `Application tasks` and has these five lines, in alphabetical order: `keyStoreSize - Runs io.plainround.seatwave.api.load.KeyStoreSizeCli.`, `pageWalk - Runs io.plainround.seatwave.api.load.PageWalkCli.`, `paymentStandIn - Runs io.plainround.seatwave.api.payment.PaymentStandInCli.`, `retryStorm - Runs io.plainround.seatwave.api.load.RetryStormCli.` and `serve - Runs io.plainround.seatwave.api.ServeCli.`

2. Run the whole gate:

```
./gradlew --offline clean check
```

   It prints one `PASSED` line per test (70 in all), then `> Task :jacocoTestCoverageVerification`, `> Task :spotlessJavaCheck` and ends with `BUILD SUCCESSFUL in 10s` and `10 actionable tasks: 10 executed`.

3. Run the contract lint:

```
./gradlew --offline -q lintContract --args='openapi/seatwave-api.yaml openapi/released/seatwave-api-1.0.0.yaml'
```

   Expected: `openapi/seatwave-api.yaml: valid, every rule passed` and `openapi/released/seatwave-api-1.0.0.yaml: valid, every rule passed`.

4. Run the contract diff on a breaking candidate:

```
./gradlew --offline -q diffContract --args='openapi/released/seatwave-api-1.0.0.yaml openapi/examples/seatwave-api-1.1.0-breaking.yaml'
```

   Expected: the line `openapi-diff: breaking`, then `house rules: 3 breaking change(s)` with three lines, then `verdict: BREAKING, released clients would fail`, and after that Gradle's `Execution failed for task ':diffContract'` with `finished with non-zero exit value 3`.

5. Break the coverage floor on purpose, in a scratch copy only. Change `"0.80"` to `"0.95"` in `build.gradle.kts` and run `./gradlew --offline check -q`. The output contains `Rule violated for bundle seatwave-api: lines covered ratio is 0.85, but expected minimum is 0.95` and `Execution failed for task ':jacocoTestCoverageVerification'`, and `BUILD FAILED`.

## Mistakes people make

- **Running `gradle` instead of `./gradlew`.** The installed Gradle may be a different version. The wrapper always starts 9.9.1 (`gradle-wrapper.md`).
- **Editing `gradle.lockfile` by hand.** The file says manual edits can break the build. Regenerate it (`dependency-locking.md`).
- **Adding a repository in `build.gradle.kts`.** `FAIL_ON_PROJECT_REPOS` in the settings file stops it on purpose.
- **Ignoring a compiler warning.** `-Werror` makes that impossible here.
- **Thinking `test` is enough.** `./gradlew test` skips the formatter check and the coverage floor. Only `check` runs all three.
- **Editing the contract and expecting a stale test result.** `inputs.dir("openapi")` makes Gradle rerun the tests after any change in that folder. A task without that line would be skipped as up to date.
- **Running a program with a path that is relative to the wrong folder.** The seven tasks set `workingDir = rootDir`, so `config/serve.properties` works from any folder. A `java` command typed by hand does not.
- **Looking for the XML report in the wrong place.** It is `build/reports/jacoco/test/jacocoTestReport.xml`, written only because of the `xml.required = true` line.
- **Committing anything from `build/`.** The folder is ignored on purpose (`git.md`).
- **Forgetting that the build file runs.** It is a program. Typing mistakes show up as Kotlin errors, not as friendly messages.

## Check yourself

1. *What is a task?* One named unit of work, such as `compileJava`, `test` or `check`. Gradle runs a task only after the tasks it depends on.
2. *What does `implementation(platform(libs.jackson.bom))` do?* It loads a list of matching versions for the Jackson family, so every Jackson jar on every classpath is 2.23.0, even those that other libraries asked for at an older version.
3. *Why does `-Werror` matter?* It turns every compiler warning into an error, so no warning can stay unnoticed.
4. *Which line makes the coverage floor part of `check`?* `dependsOn(tasks.jacocoTestCoverageVerification)` inside `tasks.check`.
5. *Why do `lintContract` and `diffContract` use the test classpath?* The OpenAPI parser, the validator and the diff are test libraries, so only the test runtime classpath has them, and the service never ships them.

## Further reading

- Gradle 9.9.1 user guide: <https://docs.gradle.org/9.9.1/userguide/userguide.html>
- Build file basics: <https://docs.gradle.org/9.9.1/userguide/part1_gradle_init.html>
- Java toolchains: <https://docs.gradle.org/9.9.1/userguide/toolchains.html>
- Kotlin DSL primer: <https://docs.gradle.org/9.9.1/userguide/kotlin_dsl.html>
- Dependency constraints: <https://docs.gradle.org/9.9.1/userguide/dependency_constraints.html>
- Custom task classes and up-to-date checks: <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The Java plugin and its `check` task: <https://docs.gradle.org/9.9.1/userguide/java_plugin.html>
- The JaCoCo plugin and its reports: <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
