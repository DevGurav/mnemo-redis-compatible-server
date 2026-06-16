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
    // Default CI gate: plumbing + integration tests only. Data-structure specs excluded
    // until the student implements them; run via `./gradlew specTest`.
    useJUnitPlatform {
        excludeTags("spec")
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

jmh {
    // Dev-run profile (fast turnaround). For a publish-grade run, raise to 5/10/2.
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    // tableSize parameter is declared via @Param in benchmark source — not set here.
    // Benchmark modes (Throughput + SampleTime) are declared via @BenchmarkMode in source.
}
