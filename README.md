# Mnemo

**A Redis-compatible, in-memory data store written from scratch in Java 21.**
It speaks the real [RESP](https://redis.io/docs/latest/develop/reference/protocol-spec/) wire
protocol, so `redis-cli`, the `Jedis` client, and Redis's own `redis-benchmark` connect to **this**
server — but the core data structures (hash table, skip list, LRU/LFU eviction) are hand-written, not
borrowed from `java.util`.

> Status: **Week 2 — structures.** The from-scratch `Dict` is implemented with incremental rehashing,
> and the first ordered structure — a span-augmented skip list behind a `ZSet` sorted set — is built
> and unit-tested (`./gradlew test` → 6 green · `./gradlew specTest` → 60 green). Next: wire the
> sorted-set commands through the RESP registry. See the [roadmap](docs/roadmap.md).

---

## Why this exists

Redis is the industry-standard in-memory store, but a black box to most engineers. Mnemo rebuilds the
core to prove the systems fundamentals an interview probes: **custom data structures, concurrency, and
tail-latency engineering.** Every performance claim is designed to be defended under fire — see
[docs/architecture-spec.md](docs/architecture-spec.md) for the threading model, memory boundaries, and
the hardened answers to the classic critiques (JVM GC pauses, the eviction/rehash collision,
single-thread vs. lock-striping).

## Quickstart

```bash
./gradlew build      # compile + run the green plumbing tests + assemble
./gradlew run        # start the server on port 6379
./gradlew test       # plumbing tests only (CI default — green)
./gradlew specTest   # the RED specs for the structures you implement (Dict, ...)
```

Connect with a real Redis client (`redis-cli` isn't native on Windows — use Docker):

```bash
docker run --rm -it redis redis-cli -h host.docker.internal -p 6379
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET foo bar
OK
127.0.0.1:6379> GET foo
"bar"
```

Supported commands (week 1): `PING ECHO SET GET DEL EXISTS COMMAND` — full set + `INFO` metrics in
[docs/api-protocol.md](docs/api-protocol.md).

## The part you implement (the point of the project)

The data structures are **yours to build** — the scaffold gives you the contract, a red spec, and a
stub, never the algorithm. The TDD loop:

1. Run `./gradlew specTest` → see [`DictTest`](src/test/java/dev/devgurav/mnemo/store/DictTest.java)
   and [`DictPropertyTest`](src/test/java/dev/devgurav/mnemo/store/DictPropertyTest.java) fail.
2. Implement [`Dict`](src/main/java/dev/devgurav/mnemo/store/Dict.java) — a **separate-chaining** hash
   table (its Javadoc has the week-1 spec and the week-2 incremental-rehashing upgrade; GC churn is
   handled by a `DictEntry` object pool, see [architecture-spec.md](docs/architecture-spec.md) §4).
3. Re-run `./gradlew specTest` until green.
4. Run the server on your own structure: `MNEMO_USE_DICT=true ./gradlew run`.

`Dict` maps directly to **LeetCode 706** (Design HashMap); the later structures map to **146/460**
(LRU/LFU) and **1206** (Skiplist).

## Architecture (one breath)

```
client ──TCP/RESP──▶ Netty pipeline (multi-threaded I/O)
                       RespDecoder → CommandInboundHandler
                          │ hands a clean POJO to ↓ (ByteBuf stays in the EventLoop)
                       single command thread (lock-free data plane)
                          CommandRegistry → Db → KeyValueStore (HashMapStore | Dict)
                          ◀── RespEncoder writes the reply back
```

Multi-threaded I/O, single-threaded execution — so the keyspace needs no locks. Full rationale,
including 16-core scaling via shared-nothing sharding and the `ByteBuf`-stays-in-the-EventLoop rule, is
in [docs/architecture-spec.md](docs/architecture-spec.md) §1–§2.

## Documentation

Engineering docs are version-controlled under [`docs/`](docs/):

- [architecture-spec.md](docs/architecture-spec.md) — concurrency model, threading layout, memory boundaries.
- [decisions/](docs/decisions/) — Architecture Decision Records: the *why* behind each design choice.
- [roadmap.md](docs/roadmap.md) — what's done, in progress, and next.
- [api-protocol.md](docs/api-protocol.md) — supported RESP commands + custom `INFO` metrics.
- [data-model.md](docs/data-model.md) — keys, value types, sorted-set encoding.
- [testing.md](docs/testing.md) — the plumbing-vs-spec test split and how to run each.
- [benchmarking-methodology.md](docs/benchmarking-methodology.md) — JMH layout, async-profiler / JFR setup.
- [glossary.md](docs/glossary.md) · [security.md](docs/security.md) · [runbook.md](docs/runbook.md) · [observability.md](docs/observability.md) · [troubleshooting.md](docs/troubleshooting.md)
- [BUILD_LOG.md](docs/BUILD_LOG.md) — what was built and why, newest first.

## Roadmap

| Week | Focus |
| --- | --- |
| 1 | Netty server, RESP2 codec, command dispatch, `Dict` spec, CI ✅ |
| **2 (now)** | Incremental rehashing ✅, skip-list sorted set ✅, JMH harness ✅ — sorted-set commands + differential tests vs real Redis next |
| 3 | TTL, LRU/LFU eviction + the logical-capacity safety protocol, `DictEntry` pool, AOF + crash recovery |
| 4 | Keyspace sharding + benchmark, Docker/deploy, the [performance report](docs/benchmarking-methodology.md) |

**Scope:** single-node (no replication/consensus). Targets Java 21 bytecode; built and run on JDK 25.

## Layout

```
src/main/java/dev/devgurav/mnemo/
  server/   MnemoServer, Config
  net/      Netty pipeline + net/resp/ RESP2 codec + value model
  command/  Command, CommandRegistry, + strings/ server/ handlers
  store/    Db, KeyValueStore, HashMapStore (placeholder), Dict, SkipList, ZSet (hand-built) + entry/ (pool)
src/test/java/...   RespCodecTest, EndToEndTest (plumbing, green) · DictTest, DictPropertyTest, ZSetTest (structure specs, green)
src/jmh/java/...     DictBenchmark
docs/               architecture-spec · api-protocol · benchmarking-methodology · data-model · testing · roadmap · glossary · decisions/ (ADRs) · BUILD_LOG · …
```
