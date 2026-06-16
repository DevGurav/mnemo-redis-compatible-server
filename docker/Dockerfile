# Mnemo container image (scaffolding — exercised in Week 4).
# Multi-stage: build a runnable distribution with the JDK, run it on a slim JRE.
# JDK 25 images are used so generational ZGC is the default (just -XX:+UseZGC). If a 25 tag is
# unavailable in your registry, 21 images also work (the bytecode targets Java 21).

# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean installDist

# ---- runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/build/install/mnemo ./
EXPOSE 6379
# JVM args (incl. -XX:+UseZGC) are baked into the generated bin/mnemo start script via
# applicationDefaultJvmArgs in build.gradle.kts. Extra args can be passed through JAVA_OPTS.
ENTRYPOINT ["./bin/mnemo"]
