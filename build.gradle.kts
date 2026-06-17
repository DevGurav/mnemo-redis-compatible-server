plugins {
    application
    id("me.champeau.jmh") version "0.7.2"
}

group = "dev.devgurav"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Netty 4 — non-blocking I/O, RESP codec pipeline, pooled ByteBuf allocator
    implementation("io.netty:netty-all:4.1.115.Final")

    // JCTools — MpscArrayQueue for lock-free Netty-worker → shard-executor hand-off
    implementation("org.jctools:jctools-core:4.0.5")

    // JUnit 5 platform + Jupiter engine
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // jqwik — property-based testing for Dict, SkipList, and eviction specs
    testImplementation("net.jqwik:jqwik:1.9.1")

    // Testcontainers — spins a real Redis instance for differential (oracle) testing
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("dev.devgurav.mnemo.server.MnemoServer")
    // Baseline dev flags. Production flag set is documented in docs/architecture-spec.md §5.
    // On JDK 23+ generational ZGC is the default; -XX:+ZGenerational is not required.
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseZGC",
        "-Xms256m",
        "-Xmx256m",
        "-XX:+AlwaysPreTouch",
    )
}

tasks.test {
    // Default CI gate: plumbing + integration tests only. Data-structure specs and
    // Testcontainers differential tests excluded; run those via their own tasks.
    useJUnitPlatform {
        excludeTags("spec", "differential")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// TDD loop: `./gradlew specTest` — these tests are RED by design until you implement
// Dict / SkipList / eviction. They turn GREEN as you complete each structure.
tasks.register<Test>("specTest") {
    description = "RED specs for Dict, SkipList, LRU/LFU eviction (fail until you implement them)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("spec")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Oracle testing: `./gradlew differentialTest` — requires Docker.
// Runs identical command sequences against Mnemo and a real redis:7-alpine container
// and asserts the RESP responses match exactly.
tasks.register<Test>("differentialTest") {
    description = "Differential (oracle) tests against a live redis:7-alpine container. Requires Docker."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("differential")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

jmh {
    fork.set(1)
    // Measure under the production GC so a young-gen pause can't masquerade as an algorithmic
    // spike; the headroom keeps the growth benchmark's transient garbage off the critical path.
    jvmArgsAppend.set(listOf("-XX:+UseZGC", "-Xmx3g"))
    // Per-benchmark profiles live in source annotations (so each is reproducible as reported):
    //   DictBenchmark / RehashBenchmark — 3 warmup, 5 measurement (dev profile), @Param/@BenchmarkMode.
    //   RehashSpikeBenchmark           — 5 warmup, 20 measurement (SingleShot needs more samples).
    // To run a single experiment: jmh { includes.set(listOf("RehashSpikeBenchmark")) }.
}
