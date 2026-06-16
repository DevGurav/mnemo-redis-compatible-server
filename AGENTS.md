# AGENTS.md — operating manual for agents in this repo

Read this first. It's the short, current orientation for anyone — a teammate or an automated tool —
making a change in Mnemo. Deep rationale lives in [`docs/`](docs/); this file is the map and the rules.

## What this is

Mnemo is a Redis-compatible, in-memory data store written from scratch in Java 21 (Netty + a
hand-rolled RESP2 codec). The point is to build the core data structures — hash table, skip list,
eviction — by hand, not borrow them from `java.util`.

## Commands

```bash
./gradlew build      # compile + plumbing tests + assemble
./gradlew run        # start the server on :6379 (HashMapStore backend)
MNEMO_USE_DICT=true ./gradlew run   # run on the from-scratch Dict
./gradlew test       # plumbing tests — the green CI gate
./gradlew specTest   # structure spec tests (Dict, ZSet, …)
./gradlew jmh        # JMH benchmarks (src/jmh/)
```

Current state: `./gradlew test` → 17 green; `./gradlew specTest` → 60 green.

## Where things live

```
src/main/java/dev/devgurav/mnemo/
  server/   MnemoServer, Config
  net/      Netty pipeline + net/resp/ RESP2 codec + value model
  command/  Command, CommandRegistry, handlers (strings/, server/, sortedset/)
  store/    Db (typed keyspace), KeyValueStore, HashMapStore (placeholder), Dict, SkipList, ZSet, entry/ (pool)
src/test/java/...   plumbing (green) + store specs (DictTest, DictPropertyTest, ZSetTest)
src/jmh/java/...     DictBenchmark
docs/               architecture-spec · api-protocol · benchmarking-methodology · data-model ·
                    testing · roadmap · glossary · security · runbook · observability ·
                    troubleshooting · decisions/ (ADRs) · BUILD_LOG
```

## Source of truth

- **What's done / next:** [docs/roadmap.md](docs/roadmap.md).
- **Why it's built this way:** [docs/decisions/](docs/decisions/) (ADRs) and
  [docs/architecture-spec.md](docs/architecture-spec.md). Don't re-litigate a settled ADR — supersede
  it with a new one if you disagree.
- **How it was built, session by session:** [docs/BUILD_LOG.md](docs/BUILD_LOG.md).

## Rules

- **Build the structures by hand.** `Dict`, `SkipList`, eviction, etc. are the point — never replace
  them with `java.util` equivalents or pull in a library that does the work.
- **The keyspace is single-threaded.** It's owned by one command thread; add no locks and no
  concurrency to the data plane ([ADR 0002](docs/decisions/0002-single-threaded-command-execution.md)).
  `ByteBuf` stays inside the Netty EventLoop; only POJOs cross to the command thread.
- **`docs/act-as-a-senior-kind-waffle.md` is local-only** (git-ignored). It's the private master
  blueprint — read it locally if present, but never commit it or quote it into committed files.
- **Docs ship with code.** Touch the living docs your change affects (BUILD_LOG always); record new
  decisions as ADRs.
- **Commits:** atomic, human-voiced, with no automated attribution or co-author trailers, no emoji,
  no marketing filler. Conventional-Commits subject + a *why* body. Details in
  [CONTRIBUTING.md](CONTRIBUTING.md).
