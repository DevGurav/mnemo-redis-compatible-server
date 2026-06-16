# Roadmap

Single source of truth for *how far along Mnemo is*. State markers move as part of the change that
moves them. Scope is single-node (no replication/consensus); targets Java 21 bytecode, built and run on
JDK 25.

Legend: ✅ done · 🔶 in progress · ⬜ not started

## Week 1 — Foundation ✅

- ✅ Gradle build (wrapper, checksum-verified) + GitHub Actions CI
- ✅ Netty pipeline + hand-rolled RESP2 codec (multibulk + inline, partial reads) — [ADR 0001](decisions/0001-hand-rolled-resp2-codec.md)
- ✅ Command pattern + registry; single command thread — [ADR 0002](decisions/0002-single-threaded-command-execution.md)
- ✅ Commands: `PING ECHO SET GET DEL EXISTS COMMAND`
- ✅ `KeyValueStore` seam with a temporary `HashMapStore`; `Dict` specified by red tests

*Milestone met: `redis-cli` set/get works in-process via `EndToEndTest`.*

## Week 2 — Structures + headline optimization 🔶

- ✅ Incremental (dual-table) rehashing in `Dict` — [ADR 0004](decisions/0004-incremental-rehashing.md)
- ✅ Span-augmented skip list + `ZSet` sorted set — [ADR 0005](decisions/0005-skiplist-over-treemap.md)
- ✅ JMH harness (`DictBenchmark`) for rehash throughput/latency
- 🔶 Rehash p99 before/after numbers — benchmark exists; doesn't yet force the table *through* a
  rehash under load (tracked)
- ✅ Wire `Z*` commands (`ZADD`/`ZRANK`/`ZRANGE`) through the RESP registry — sorted set is reachable
  over the wire, with a WRONGTYPE-guarded typed keyspace ([ADR 0007](decisions/0007-typed-keyspace.md))
- ✅ `INCR` / `DECR` / `INCRBY` / `DECRBY` integer counters (overflow- and WRONGTYPE-guarded)
- ✅ `TYPE` / `DBSIZE` / `FLUSHDB` / `FLUSHALL` keyspace commands
- ⬜ Hashes, Lists; `KEYS` `INFO`
- ⬜ Differential tests vs. a real Redis instance

*Milestone target: first `redis-benchmark` numbers.*

## Week 3 — Ops + hardening ⬜

- ⬜ TTL (lazy + active expiry)
- ⬜ LRU/LFU eviction + the logical-capacity protocol — [ADR 0006](decisions/0006-logical-maxmemory.md)
- ⬜ `DictEntry` object pool: async-profiler allocation comparison
- ⬜ AOF persistence + crash-recovery test
- ⬜ ZGC / JFR / Epsilon GC pass

*Milestone target: survives `kill -9`; stays under `maxmemory` mid-rehash.*

## Week 4 — Concurrency + ship ⬜

- ⬜ Finalize single-thread executor; shard across cores + benchmark (POJO-over-MPSC boundary)
- ⬜ Docker image + Actions release
- ⬜ Deploy
- ⬜ Performance report: rehash p99 before/after, GC pause vs. command p99 separated, skip-list
  scaling, `redis-benchmark` ops/sec
- ⬜ 90-second demo
