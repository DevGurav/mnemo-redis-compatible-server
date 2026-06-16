# Contributing

Conventions for working in Mnemo.

## Dev environment

- JDK 21+ (built for Java 21 bytecode; developed on JDK 25). The Gradle wrapper is checksum-verified —
  use `./gradlew`, don't install Gradle separately.
- No other setup. `./gradlew build` from a fresh clone should be green.

## The loop (every change)

1. **Work test-first** where there's a structure to build: run `./gradlew specTest`, see it red,
   implement against the contract, get it green.
2. **Verify:** `./gradlew test` (plumbing, green) and `./gradlew specTest` (structures) before you
   commit. Don't commit on unknown status.
3. **Update the docs the change touches:** [docs/BUILD_LOG.md](docs/BUILD_LOG.md) always (a new entry —
   context, what, why, tradeoffs, results, next); move the markers in [docs/roadmap.md](docs/roadmap.md);
   record any new decision as an ADR in [docs/decisions/](docs/decisions/); add a
   [CHANGELOG.md](CHANGELOG.md) line.
4. **Commit atomically** (below), then push.

## Commits

- **Atomic:** one logical change per commit — a structure, a benchmark, a doc set. Not a daily dump.
  Each commit should build and pass its relevant tests on its own.
- **Format:** Conventional-Commits subject — `type(scope): imperative summary`, lowercase, no trailing
  period. Types: `feat fix refactor perf test docs build chore`. Add a body explaining the *why* when
  it isn't obvious; small changes can be subject-only.
- **Voice:** it should read as one experienced engineer's work. **Never** include automated attribution
  — no co-author trailers, no tool or assistant credit — no emoji, no marketing filler
  ("comprehensive/robust/seamless"), and don't restate the diff in prose.

```
perf(dict): rehash incrementally to keep p99 flat during resize

Full-table rehash on resize stalled the command thread for milliseconds
on large keyspaces. Drain buckets a few at a time across normal traffic;
keep two tables live during the move. See docs/decisions/0004-*.md.
```

## Tests

See [docs/testing.md](docs/testing.md). Spec tests are tagged `spec` and excluded from the default
`test` task; the `.jqwik-database` failure cache is git-ignored.
