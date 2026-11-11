# Dependency locking

## What it is

Your project depends on libraries. Those libraries depend on other libraries. A library you did not ask for but that comes along is a **transitive dependency**. For example this project asks for the OpenAPI validator, and the validator needs a YAML parser called SnakeYAML; SnakeYAML is a transitive dependency.

If a build resolved "the newest compatible version" every time, two builds a week apart could use different jars and give different results. **Dependency locking** stops that. Gradle writes the exact version it chose for every library into a **lockfile**, commits it with the code, and from then on insists on those exact versions.

A **version** like `3.27.7` has a major part, a minor part and a patch part. A **coordinate** is the full address of a library: `group:name:version`, for example `org.assertj:assertj-core:3.27.7`.

## Why this repository uses it

The README says "`gradle.lockfile` pins the transitive ones", and that after the two constraints OSV (the open source vulnerability database) listed no known vulnerability for any of the 87 jars in the file. That sentence only means something because the file is locked: the list that was checked is the list that is built, today and next year. The version catalog (`toml-version-catalog.md`) pins the libraries this project names; the lockfile pins everything else.

**Why this file is much longer than the siblings'.** The sibling repositories lock 22 libraries. This one locks 87. The reason is two test libraries. The OpenAPI validator and the OpenAPI diff each bring a whole family: a parser for the current OpenAPI format (Swagger Parser version 3), a second parser for the old Swagger 2 format that the diff still carries, two different JSON Schema validators, an HTTP client for fetching remote references, the Guava utility library and the Apache Commons libraries. Of the 87 locked coordinates, about 55 exist only because of those two libraries. The main code of the service needs six libraries (result4j, Jackson with its core and annotations, H2, SLF4J and Logback with its core), and everything else is test or build tooling.

## Where it appears

| Path | What it does |
| --- | --- |
| `build.gradle.kts` | `dependencyLocking { lockAllConfigurations() }` turns it on (`gradle.md`). |
| `gradle.lockfile` | The locked versions of every configuration of the project: 4 comment lines, 87 library lines and one `empty=` line. |
| `settings-gradle.lockfile` | The same for the settings phase: 3 comment lines and one data line. |

## How it works underneath

1. A **configuration** is a named list of dependencies for one purpose (`compileClasspath`, `testRuntimeClasspath`, ...). Each has its own lock entries.
2. When a configuration is resolved, Gradle reads `gradle.lockfile`. For every library it finds there, it treats the locked version as a strict requirement. If the build's own requirements cannot be met with that version, resolution fails.
3. A library that is on a classpath but is **not** in the lockfile also fails the build, so nothing slips in unlocked.
4. To change the lock, you ask Gradle to recompute and rewrite the file: `./gradlew :dependencies --write-locks`. You commit the new file, so the change is visible in review.
5. Lines in the file are sorted, and each line says which configurations use that library. The names after `=` mean "these configurations use this exact version".
6. When two libraries ask for different versions of the same library, Gradle picks one (normally the newest) and the lock records that one. The dependency tree shows it as `a -> b`: "asked for a, got b". The lock has only the winner.

I tested step 2 in a scratch copy: after changing `assertj-core:3.27.7` to `assertj-core:3.27.6` in the lockfile only, `compileTestJava` failed with `Could not resolve org.assertj:assertj-core:3.27.7.` and `Cannot find a version of 'org.assertj:assertj-core' that satisfies the version constraints`, and the constraint line read `'org.assertj:assertj-core:{strictly 3.27.6}' because of the following reason: Dependency version enforced by Dependency Locking`. The real lockfile was not touched.

## Walkthrough of this repository's use

### `gradle.lockfile`, line by line

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
# To regenerate this file, run: ./gradlew :dependencies --write-locks
```

Lines 1 to 4 are comments that Gradle writes. They say the file is generated, hand edits are risky, it belongs in source control, and the command that regenerates it. The command is `./gradlew :dependencies --write-locks`; `:dependencies` names the root project's `dependencies` task.

Each line below has the shape `group:name:version=configuration,configuration,...`. The meaning of each configuration name is in the table after the walkthrough.

#### Logging: the engine

```text
ch.qos.logback:logback-classic:1.6.6=runtimeClasspath,testRuntimeClasspath
ch.qos.logback:logback-core:1.6.6=runtimeClasspath,testRuntimeClasspath
```

- `ch.qos.logback:logback-classic:1.6.6` is the logging engine from the catalog (`slf4j-and-logback.md`). It is only on the two runtime classpaths: `runtimeClasspath` and `testRuntimeClasspath`. It is not on a compile classpath, because the code is compiled against the SLF4J interface and the engine is added with `runtimeOnly`.
- `ch.qos.logback:logback-core:1.6.6` is the part of Logback that `logback-classic` is built on. It came along as a transitive dependency.

#### The OpenAPI validator and its direct helpers

```text
com.atlassian.oai:openapi-request-validator-core:3.0.0=testCompileClasspath,testRuntimeClasspath
com.ethlo.time:itu:1.14.0=testCompileClasspath,testRuntimeClasspath
```

- `com.atlassian.oai:openapi-request-validator-core:3.0.0` is the validator from the catalog. It is on the two test classpaths only (`testCompileClasspath`, `testRuntimeClasspath`) because `build.gradle.kts` adds it with `testImplementation` (`openapi-validator-and-diff.md`).
- `com.ethlo.time:itu:1.14.0` parses and prints ISO-8601 dates quickly. The JSON Schema validator `com.networknt:json-schema-validator` uses it to check the `date-time` format of a field.

#### Jackson: the one family with one version

```text
com.fasterxml.jackson.core:jackson-annotations:2.23=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-core:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.core:jackson-databind:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.23.0=testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.23.0=testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.23.0=testCompileClasspath,testRuntimeClasspath
com.fasterxml.jackson:jackson-bom:2.23.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

- Seven lines, all at 2.23.0, except `jackson-annotations`, whose Jackson-published version has no patch number: `2.23`. That is Jackson's own naming, not a typo.
- `jackson-annotations`, `jackson-core` and `jackson-databind` are the three core jars of Jackson. They are on all four classpaths because the main code uses `jackson-databind` directly (`jackson.md`) and the other two come with it.
- `jackson-dataformat-yaml` reads and writes YAML through Jackson. The Swagger parser and the networknt validator use it to read the OpenAPI document. It is on the test classpaths only.
- `jackson-datatype-jdk8` (support for `Optional` and friends) and `jackson-datatype-jsr310` (support for Java date and time classes) are Jackson add-ons the OpenAPI libraries ask for. Test classpaths only.
- `com.fasterxml.jackson:jackson-bom:2.23.0` is the bill of materials from `platform(libs.jackson.bom)`. Because of it, the OpenAPI libraries' older Jackson requests (2.11, 2.18, 2.19, 2.21) all resolve to 2.23.0 (`gradle.md`).

#### Caching and the older JSON Schema tools

```text
com.github.ben-manes.caffeine:caffeine:3.2.4=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:btf:1.3=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:jackson-coreutils-equivalence:1.0=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:jackson-coreutils:2.0=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:json-patch:1.13=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:json-schema-core:1.2.14=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:json-schema-validator:2.2.14=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:msg-simple:1.2=testCompileClasspath,testRuntimeClasspath
com.github.java-json-tools:uri-template:0.10=testCompileClasspath,testRuntimeClasspath
```

- `com.github.ben-manes.caffeine:caffeine:3.2.4` is an in-memory cache library. The validator 3.0.0 uses it to keep parsed schemas.
- The eight `com.github.java-json-tools` lines belong to one old family of JSON libraries. They are pulled in by the Swagger 1.x compatibility parser. `json-schema-validator` and `json-schema-core` are an older JSON Schema validator. `jackson-coreutils` and `jackson-coreutils-equivalence` are helpers for Jackson trees and for comparing numbers. `json-patch` applies JSON patch documents. `msg-simple` formats messages. `uri-template` expands URI templates. `btf` is a small support library of the same family that `msg-simple` uses.

#### The one library of the main code that is not Jackson, H2 or logging

```text
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`com.github.sviperll:result4j:1.2.0` is the result type used by the settings reader, the services and the payment client (`result4j.md`). All four classpaths use it. It brings no library of its own.

#### Guava and the small annotation libraries

```text
com.google.code.findbugs:jsr305:3.0.2=testCompileClasspath,testRuntimeClasspath
com.google.errorprone:error_prone_annotations:2.52.0=testCompileClasspath,testRuntimeClasspath
com.google.guava:failureaccess:1.0.3=testCompileClasspath,testRuntimeClasspath
com.google.guava:guava:33.8.0-android=testCompileClasspath,testRuntimeClasspath
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava=testCompileClasspath,testRuntimeClasspath
com.google.j2objc:j2objc-annotations:3.1=testCompileClasspath,testRuntimeClasspath
```

- `com.google.code.findbugs:jsr305:3.0.2` holds annotations such as `@Nullable` that several libraries use to mark values that may be null.
- `com.google.errorprone:error_prone_annotations:2.52.0` holds annotations for a code checker. Caffeine and Guava use them.
- `com.google.guava:guava:33.8.0-android` is a widely used utility library. It is the version raised by the constraint in `build.gradle.kts`: the OpenAPI tools ask for 32.1.3-android and 28.2-android, and the lock records 33.8.0-android. The sibling `failureaccess` and `listenablefuture` are parts of Guava: `failureaccess` is an internal helper, and `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` is a deliberately empty jar that exists so an old copy of the same classes can never be pulled in beside Guava.
- `com.google.j2objc:j2objc-annotations:3.1` holds annotations for a tool that translates Java to Objective-C. Guava uses them; they have no effect here.

#### Phone numbers, HTML, the validator's JSON Schema engine, Apache and IO

```text
com.googlecode.libphonenumber:libphonenumber:8.11.1=testCompileClasspath,testRuntimeClasspath
com.h2database:h2:2.5.252=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
com.j2html:j2html:1.6.0=testCompileClasspath,testRuntimeClasspath
com.networknt:json-schema-validator:2.0.1=testCompileClasspath,testRuntimeClasspath
commons-codec:commons-codec:1.11=testCompileClasspath,testRuntimeClasspath
commons-io:commons-io:2.22.0=testCompileClasspath,testRuntimeClasspath
commons-logging:commons-logging:1.3.6=testCompileClasspath,testRuntimeClasspath
```

- `com.googlecode.libphonenumber:libphonenumber:8.11.1` parses telephone numbers. The older JSON Schema validator uses it for its `phone` format.
- `com.h2database:h2:2.5.252` is the embedded database from the catalog (`h2-sql-and-migrations.md`). All four classpaths use it.
- `com.j2html:j2html:1.6.0` builds HTML in Java. The diff library uses it for its HTML report, which this repository never asks for.
- `com.networknt:json-schema-validator:2.0.1` is the JSON Schema engine the validator uses to check a reply body against the schema in the document.
- `commons-codec:commons-codec:1.11` encodes and decodes things such as Base64 and hex. The Apache HTTP client uses it.
- `commons-io:commons-io:2.22.0` has file and stream helpers. Several libraries ask for older versions (2.15.1 and 2.20.0); the lock records 2.22.0.
- `commons-logging:commons-logging:1.3.6` is an older logging interface that the Apache HTTP client writes to. It is redirected to SLF4J by `jcl-over-slf4j`, below.

#### Swagger: the OpenAPI object model and the parsers, version 3

```text
io.swagger.core.v3:swagger-annotations:2.2.48=testCompileClasspath,testRuntimeClasspath
io.swagger.core.v3:swagger-core:2.2.48=testCompileClasspath,testRuntimeClasspath
io.swagger.core.v3:swagger-models:2.2.48=testCompileClasspath,testRuntimeClasspath
io.swagger.parser.v3:swagger-parser-core:2.1.41=testCompileClasspath,testRuntimeClasspath
io.swagger.parser.v3:swagger-parser-safe-url-resolver:2.1.41=testCompileClasspath,testRuntimeClasspath
io.swagger.parser.v3:swagger-parser-v2-converter:2.1.41=testCompileClasspath,testRuntimeClasspath
io.swagger.parser.v3:swagger-parser-v3:2.1.41=testCompileClasspath,testRuntimeClasspath
io.swagger.parser.v3:swagger-parser:2.1.41=testCompileClasspath,testRuntimeClasspath
```

Swagger is the family of tools around OpenAPI. These eight lines are the current generation.
- `io.swagger.core.v3:swagger-annotations`, `swagger-core` and `swagger-models` at 2.2.48. The `swagger-models` jar holds the Java classes that mirror an OpenAPI document (`OpenAPI`, `PathItem`, `Operation`, `Schema`, `Parameter`, `ApiResponse`). The test classes `ContractRules` and `BreakingChanges` import those classes (`tests-contract-walkthrough.md`).
- `io.swagger.parser.v3:swagger-parser` at 2.1.41 is the reader that turns YAML text into those objects (`OpenAPIParser`). `swagger-parser-core` holds the shared types such as `ParseOptions`. `swagger-parser-v3` reads OpenAPI 3.x. `swagger-parser-v2-converter` reads the older Swagger 2 format and converts it. `swagger-parser-safe-url-resolver` limits which web addresses the parser may fetch.
- openapi-diff asked for 2.1.31 of these parser jars; the validator asked for 2.1.41; the lock records 2.1.41.

#### Swagger: the older generation

```text
io.swagger:swagger-annotations:1.6.16=testCompileClasspath,testRuntimeClasspath
io.swagger:swagger-compat-spec-parser:1.0.76=testCompileClasspath,testRuntimeClasspath
io.swagger:swagger-core:1.6.16=testCompileClasspath,testRuntimeClasspath
io.swagger:swagger-models:1.6.16=testCompileClasspath,testRuntimeClasspath
io.swagger:swagger-parser-safe-url-resolver:1.0.76=testCompileClasspath,testRuntimeClasspath
io.swagger:swagger-parser:1.0.76=testCompileClasspath,testRuntimeClasspath
```

Six lines, at 1.6.16 and 1.0.76, in the group `io.swagger`. They are the Swagger 2 object model (`swagger-core`, `swagger-models`, `swagger-annotations`) and the Swagger 2 parser (`swagger-parser`, `swagger-compat-spec-parser`, `swagger-parser-safe-url-resolver`). The version 3 converter above needs them to read old documents. This repository has no Swagger 2 document, but the jars are in the tree, so they are in the lock.

#### Jakarta and javax: API jars, joda-time, byte-buddy, jopt-simple

```text
jakarta.activation:jakarta.activation-api:1.2.2=testCompileClasspath,testRuntimeClasspath
jakarta.validation:jakarta.validation-api:2.0.2=testCompileClasspath,testRuntimeClasspath
jakarta.xml.bind:jakarta.xml.bind-api:2.3.3=testCompileClasspath,testRuntimeClasspath
javax.validation:validation-api:1.1.0.Final=testCompileClasspath,testRuntimeClasspath
joda-time:joda-time:2.10.5=testCompileClasspath,testRuntimeClasspath
net.bytebuddy:byte-buddy:1.18.3=testCompileClasspath,testRuntimeClasspath
net.sf.jopt-simple:jopt-simple:5.0.4=testCompileClasspath,testRuntimeClasspath
```

- `jakarta.activation:jakarta.activation-api:1.2.2`, `jakarta.validation:jakarta.validation-api:2.0.2` and `jakarta.xml.bind:jakarta.xml.bind-api:2.3.3`, and the older `javax.validation:validation-api:1.1.0.Final`, are **API jars**: they hold only interfaces and annotations that Swagger's classes mention, so those classes can be loaded.
- `joda-time:joda-time:2.10.5` is an old date and time library used by the older JSON Schema validator.
- `net.bytebuddy:byte-buddy:1.18.3` creates classes at run time. AssertJ uses it (`assertj.md`).
- `net.sf.jopt-simple:jopt-simple:5.0.4` parses command-line options. The older JSON Schema validator's own command-line tool uses it; this repository never calls that tool.

#### Apache Commons and the Apache HTTP client

```text
org.apache.commons:commons-collections4:4.5.0=testCompileClasspath,testRuntimeClasspath
org.apache.commons:commons-configuration2:2.15.1=testCompileClasspath,testRuntimeClasspath
org.apache.commons:commons-lang3:3.20.0=testCompileClasspath,testRuntimeClasspath
org.apache.commons:commons-text:1.15.0=testCompileClasspath,testRuntimeClasspath
org.apache.httpcomponents:httpclient:4.5.14=testCompileClasspath,testRuntimeClasspath
org.apache.httpcomponents:httpcore:4.4.16=testCompileClasspath,testRuntimeClasspath
```

- `org.apache.commons:commons-collections4:4.5.0`, `commons-configuration2:2.15.1`, `commons-lang3:3.20.0` and `commons-text:1.15.0` are helper libraries for collections, configuration files, strings and text. openapi-diff uses `commons-collections4` and `commons-configuration2`; Swagger uses `commons-lang3`.
- `commons-configuration2:2.15.1` is the second version raised by a constraint. openapi-diff asks for 2.13.0.
- `org.apache.httpcomponents:httpclient:4.5.14` and `httpcore:4.4.16` are an HTTP client. The old Swagger 2 parser uses it to fetch referenced documents. This repository's validator is only ever given text, so no request leaves the machine.

#### Test tools: JUnit's helper, AssertJ, Awaitility, Hamcrest

```text
org.apiguardian:apiguardian-api:1.1.2=testCompileClasspath
org.assertj:assertj-core:3.27.7=testCompileClasspath,testRuntimeClasspath
org.awaitility:awaitility:4.3.0=testCompileClasspath,testRuntimeClasspath
org.hamcrest:hamcrest:2.1=testCompileClasspath,testRuntimeClasspath
```

- `org.apiguardian:apiguardian-api:1.1.2` holds annotations that JUnit uses to mark its public API. It is only on `testCompileClasspath`: it is needed to compile against JUnit but not to run it.
- `org.assertj:assertj-core:3.27.7` is the assertion library from the catalog (`assertj.md`).
- `org.awaitility:awaitility:4.3.0` is the waiting library from the catalog (`awaitility.md`).
- `org.hamcrest:hamcrest:2.1` is a library of matchers that Awaitility is built on.

#### JaCoCo

```text
org.jacoco:org.jacoco.agent:0.8.16=jacocoAgent,jacocoAnt
org.jacoco:org.jacoco.ant:0.8.16=jacocoAnt
org.jacoco:org.jacoco.core:0.8.16=jacocoAnt
org.jacoco:org.jacoco.report:0.8.16=jacocoAnt
```

These four are JaCoCo (`jacoco.md`) at 0.8.16, and they use two other configurations:
- `jacocoAgent` is the configuration for the **agent**, a small jar attached to the test JVM that counts which lines run. Only `org.jacoco:org.jacoco.agent` is in it (and also in `jacocoAnt`).
- `jacocoAnt` is the configuration for the Ant-based tasks that read the counts, write the report and check the rule: `org.jacoco.ant`, `org.jacoco.core`, `org.jacoco.report` and the agent.

#### JSpecify and JUnit

```text
org.jspecify:jspecify:1.0.1=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter-api:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter-engine:6.1.3=testRuntimeClasspath
org.junit.jupiter:junit-jupiter-params:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.jupiter:junit-jupiter:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-commons:6.1.3=testCompileClasspath,testRuntimeClasspath
org.junit.platform:junit-platform-engine:6.1.3=testRuntimeClasspath
org.junit.platform:junit-platform-launcher:6.1.3=testRuntimeClasspath
org.junit:junit-bom:6.1.3=testCompileClasspath,testRuntimeClasspath
```

- `org.jspecify:jspecify:1.0.1` carries nullness annotations (`@Nullable`, "this value may be null"). Caffeine and Guava ask for 1.0.0 and 1.0.1 respectively; the lock records 1.0.1. It is on both test classpaths here, because Caffeine, which the validator uses, needs it at run time too.
- Four `org.junit.jupiter` lines: `junit-jupiter-api` (the annotations such as `@Test`), `junit-jupiter-engine` (finds and runs tests; only at run time, so only `testRuntimeClasspath`), `junit-jupiter-params` (parameterized tests, which no test here uses), and `junit-jupiter` (the bundle that brings them together). All at 6.1.3.
- Three `org.junit.platform` lines: `junit-platform-commons`, `junit-platform-engine` (run time only) and `junit-platform-launcher` (run time only, the one the build file adds with `testRuntimeOnly`).
- `org.junit:junit-bom:6.1.3` is the bill of materials from `platform(...)`.

#### A JavaScript engine, the diff library, and the tools beneath them

```text
org.mozilla:rhino:1.7.15.1=testCompileClasspath,testRuntimeClasspath
org.openapitools.openapidiff:openapi-diff-core:2.1.7=testCompileClasspath,testRuntimeClasspath
org.opentest4j:opentest4j:1.3.0=testCompileClasspath,testRuntimeClasspath
```

- `org.mozilla:rhino:1.7.15.1` is a JavaScript engine written in Java. The older JSON Schema validator uses it to evaluate regular expressions the way JavaScript does.
- `org.openapitools.openapidiff:openapi-diff-core:2.1.7` is the comparer from the catalog (`openapi-validator-and-diff.md`). Test classpaths only.
- `org.opentest4j:opentest4j:1.3.0` defines the `AssertionFailedError` that tests throw when a check fails.

#### ASM, SLF4J and SnakeYAML

```text
org.ow2.asm:asm-commons:9.11.1=jacocoAnt
org.ow2.asm:asm-tree:9.11.1=jacocoAnt
org.ow2.asm:asm:9.11.1=jacocoAnt
org.slf4j:jcl-over-slf4j:2.0.17=testCompileClasspath,testRuntimeClasspath
org.slf4j:slf4j-api:2.0.20=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
org.yaml:snakeyaml:2.5=testCompileClasspath,testRuntimeClasspath
```

- Three `org.ow2.asm` lines at 9.11.1 (`asm`, `asm-commons`, `asm-tree`): a library that reads and changes Java bytecode. JaCoCo's Ant tasks use it, so the configuration is `jacocoAnt` only.
- `org.slf4j:jcl-over-slf4j:2.0.17` is a bridge: it pretends to be the old `commons-logging` interface and sends the messages to SLF4J. openapi-diff brings it so that the Apache libraries' log output ends in the same place as everything else.
- `org.slf4j:slf4j-api:2.0.20` is the logging interface from the catalog. The OpenAPI libraries ask for 2.0.9, 2.0.17 and Logback asks for 2.0.19; the lock records 2.0.20 for all.
- `org.yaml:snakeyaml:2.5` is the YAML parser underneath Jackson's YAML module and Swagger. Several libraries ask for 2.3, 2.4; the lock records 2.5.

#### The line that lists nothing

```text
empty=annotationProcessor,testAnnotationProcessor
```

`empty=` lists configurations that resolved to nothing: `annotationProcessor` and `testAnnotationProcessor`. An **annotation processor** is a compiler plug-in; this project has none, so both are empty. Recording them proves they were locked too, so adding one later is noticed.

### The meaning of each configuration name after `=`

| Name | What it holds |
| --- | --- |
| `compileClasspath` | Everything needed to compile the main code. |
| `runtimeClasspath` | Everything needed to run the main code. |
| `testCompileClasspath` | Everything needed to compile the tests. |
| `testRuntimeClasspath` | Everything needed to run the tests. |
| `jacocoAgent` | The JaCoCo agent that measures the tests. |
| `jacocoAnt` | The JaCoCo tools that write the report and check the floor. |
| `annotationProcessor`, `testAnnotationProcessor` | Compiler plug-ins for main and test code (empty here). |

### `settings-gradle.lockfile`

```text
# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
empty=incomingCatalogForLibs0
```

The three comment lines are the same as in the other file, without the regeneration line. The one data line, `empty=incomingCatalogForLibs0`, is the lock of an internal configuration Gradle creates for the catalog named `libs`; it holds no dependencies, so it is listed as empty. It exists because dependency locking also covers the settings phase, where catalogs are read.

### Regenerating the files

`./gradlew :dependencies --write-locks` recomputes both `gradle.lockfile` and `settings-gradle.lockfile`. In a scratch copy I deleted both files and ran the command: it ended with exit status 0 and `git status --short` showed no modified file, so the written files were identical to the committed ones, which proves the committed files are exactly what Gradle resolves. When you change a version in the catalog, run it, then commit both files.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. Look at the locked versions:

```
cat gradle.lockfile
```

   Expected: the 4 comment lines, 87 library lines and the `empty=` line quoted above.

2. Count the libraries:

```
grep -v '^#' gradle.lockfile | grep -vc '^empty'
```

   Expected: `87`.

3. Prove the committed lock is current:

```
./gradlew dependencies --write-locks -q > /dev/null; git status --short
```

   Expected: no line for `gradle.lockfile` or `settings-gradle.lockfile` (untracked files, such as a new `tech/` folder, may be listed).

4. See a version being enforced:

```
./gradlew -q dependencyInsight --dependency assertj-core --configuration testRuntimeClasspath
```

   Expected: `org.assertj:assertj-core:3.27.7`, then `Selection reasons:` and a line `By constraint: Dependency version enforced by Dependency Locking`.

5. See who asked for the old Guava and which one won:

```
./gradlew -q dependencyInsight --dependency guava --configuration testRuntimeClasspath
```

   Expected: a tree under `com.google.guava:guava:33.8.0-android` in which entries read `(requested com.google.guava:guava:32.1.3-android)` and `(requested com.google.guava:guava:28.2-android)`: the versions the OpenAPI tools asked for.

6. See the OpenAPI libraries' share of the tree:

```
./gradlew -q dependencies --configuration testRuntimeClasspath | grep -c "swagger"
```

   Expected: `39` (lines of the tree that mention swagger; the same jar repeats where several libraries share it). The main code's classpath has no swagger line: `./gradlew -q dependencies --configuration runtimeClasspath | grep -c swagger` prints `0`.

## Mistakes people make

- **Editing the lockfile by hand.** The header warns about it. Use `--write-locks`.
- **Changing a version in the catalog and forgetting to regenerate.** The build then fails with "Dependency version enforced by Dependency Locking".
- **Not committing the lockfile.** The lock is only useful if everyone gets the same file.
- **Thinking the lock replaces the catalog.** The catalog says what you ask for; the lock records what Gradle found for it, including everything transitive.
- **Locking only some configurations.** `lockAllConfigurations()` locks them all, including the JaCoCo ones.
- **Ignoring a large lockfile diff.** Review it: it shows exactly which library versions moved. In this repository a new OpenAPI library version can move dozens of lines at once.
- **Trusting a vulnerability scan of the direct dependencies only.** The vulnerable Guava here was transitive. Scan the lockfile, which lists everything.

## Check yourself

1. *What is a transitive dependency?* A library your dependencies need, which you did not ask for directly.
2. *Which command rewrites the lock?* `./gradlew :dependencies --write-locks`.
3. *What does the text after `=` on a lockfile line mean?* The configurations that use that exact version.
4. *Why is this lockfile 87 libraries long and the siblings' 22?* The two OpenAPI test libraries bring about 55 libraries between them.
5. *Why is `junit-jupiter-engine` only on `testRuntimeClasspath`?* Tests need the engine only to run, not to compile.

## Further reading

- Locking dependency versions in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/dependency_locking.html>
- Declaring dependencies and configurations: <https://docs.gradle.org/9.9.1/userguide/declaring_dependencies.html>
- Viewing the dependency tree: <https://docs.gradle.org/9.9.1/userguide/viewing_debugging_dependencies.html>
- Dependency constraints: <https://docs.gradle.org/9.9.1/userguide/dependency_constraints.html>
