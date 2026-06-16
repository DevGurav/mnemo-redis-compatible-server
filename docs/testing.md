# Testing strategy

Mnemo separates **plumbing tests** (the server scaffold — always green, gate CI) from **spec tests**
(the from-scratch data structures — red until you implement them, then green). This split is what makes
the project a TDD exercise rather than a finished artifact.

## Levels

| Level | Where | What it proves |
|-------|-------|----------------|
| **Protocol / unit** | `RespCodecTest` (EmbeddedChannel) | RESP2 decode/encode, incl. inline commands and a command split across two reads |
| **End-to-end** | `EndToEndTest` (real socket) | Full `PING`/`SET`/`GET`/`EXISTS`/`DEL` round-trip over TCP |
| **Structure spec (example)** | `DictTest`, `ZSetTest` | Hand-written structures against a hand-written contract, including the hard transient states (mid-rehash get/put/remove; skip-list span integrity on insert/delete) |
| **Structure spec (property)** | `DictPropertyTest` (jqwik) | Randomized sequences cross-checked against a reference model |

## Running

```bash
./gradlew test       # plumbing only — the CI gate, always green
./gradlew specTest   # the structure specs (Dict, ZSet, …)
./gradlew build      # compile + plumbing tests + assemble
```

Current state: **6 plumbing tests** green; **60 spec tests** green
(`DictTest` 24 · `DictPropertyTest` 1 · `ZSetTest` 35).

## Conventions

- Spec tests are JUnit-tagged `spec` and **excluded from the default `test` task**, so a freshly
  cloned repo is green on `./gradlew test` while the structures you haven't built yet stay red on
  `./gradlew specTest`. CI runs the green set; `specTest` is the local TDD loop.
- Property tests use jqwik; its failure-shrinking database (`.jqwik-database`) is a local artifact and
  is git-ignored.
- The skip list has no test file of its own by design — it is exercised through `ZSet`, including
  randomized rank/range cross-checks against a brute-force reference.

## Deliberately not (yet) tested

- **Differential tests vs. real Redis** — planned for W2; will replay command sequences against both
  Mnemo and a real `redis-server` and assert identical replies.
- **Concurrency** — there is nothing to stress: the keyspace is owned by a single command thread
  ([ADR 0002](decisions/0002-single-threaded-command-execution.md)), so there are no races to test for.
- **Performance** is measured, not asserted — see [benchmarking-methodology.md](benchmarking-methodology.md).
