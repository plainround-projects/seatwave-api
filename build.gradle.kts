plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

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

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

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

// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

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
