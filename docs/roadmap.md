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

## Week 2 — Structures + headline optimization ✅

- ✅ Incremental (dual-table) rehashing in `Dict` — [ADR 0004](decisions/0004-incremental-rehashing.md)
- ✅ Span-augmented skip list + `ZSet` sorted set — [ADR 0005](decisions/0005-skiplist-over-treemap.md)
- ✅ JMH harness (`DictBenchmark`) for rehash throughput/latency
- ✅ Rehash p99 before/after — `DictSTW` control + `RehashSpikeBenchmark` isolate the resize-
  triggering put: stop-the-world **347.6 ms** vs incremental **6.1 ms** (≈57×). See
  [benchmarking-methodology.md](benchmarking-methodology.md) Headline 1
- ✅ Wire `Z*` commands (`ZADD`/`ZRANK`/`ZRANGE`) through the RESP registry — sorted set is reachable
  over the wire, with a WRONGTYPE-guarded typed keyspace ([ADR 0007](decisions/0007-typed-keyspace.md))
- ✅ `INCR` / `DECR` / `INCRBY` / `DECRBY` integer counters (overflow- and WRONGTYPE-guarded)
- ✅ `TYPE` / `DBSIZE` / `FLUSHDB` / `FLUSHALL` keyspace commands
- ✅ Hashes (`HSET`/`HGET`/`HGETALL`/`HDEL`/`HLEN`) on a per-key `Dict`, with a dual-table
  `Dict.forEach` iterator ([ADR 0008](decisions/0008-hash-type.md))
- ✅ Lists (`LPUSH`/`RPUSH`/`LPOP`/`RPOP`/`LLEN`/`LRANGE`) on an `IntrusiveList` with a `ListNode`
  pool; `INFO` over Server/Clients/Memory/Keyspace ([ADR 0009](decisions/0009-list-type-and-info.md))
- ✅ `KEYS` (Redis-compatible glob pattern: `*`, `?`, `[charset]`, `[^charset]`, ranges, `\` escape)
- ✅ Differential (oracle) tests vs. a real `redis:7-alpine` via Testcontainers (`./gradlew differentialTest`)

*Milestone target: first `redis-benchmark` numbers.*

## Week 3 — Ops + hardening 🔶

- ✅ TTL (lazy + active expiry) — 7 commands (EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/PTTL/PERSIST),
  single expiry map across all namespaces, `TtlSweeper` co-scheduled on shard thread, 20 unit tests
  — [ADR 0011](decisions/0011-ttl-lazy-and-active-expiry.md)
- ✅ Approximate-LRU eviction + the logical-capacity protocol: allocation-free random sampler over the
  `Dict`, logical access clock, `maxmemory` config, `used_memory`/`evicted_keys` in `INFO`
  — [ADR 0006](decisions/0006-logical-maxmemory.md) / [ADR 0010](decisions/0010-random-sampling-lru-eviction.md)
- ✅ LFU eviction policy — Morris counter (`DictEntry.lfu`), `EvictionPolicy` enum, `--eviction-policy`
  config flag, `INFO` reports live policy — [ADR 0012](decisions/0012-lfu-eviction-policy.md)
- ⬜ `DictEntry` object pool: async-profiler allocation comparison (benchmark docs)
- ✅ AOF persistence + crash-recovery test — `AofWriter`/`AofReplayer`, write whitelist in shard,
  synchronous startup replay, `Config.aofPath`, 2 integration tests — [ADR 0013](decisions/0013-aof-persistence.md)
- ⬜ ZGC / JFR / Epsilon GC pass (benchmark docs)

*Milestone target: survives `kill -9`; stays under `maxmemory` mid-rehash.*

## Week 4 — Concurrency + ship ⬜

- ⬜ Finalize single-thread executor; shard across cores + benchmark (POJO-over-MPSC boundary)
- ⬜ Docker image + Actions release
- ⬜ Deploy
- ⬜ Performance report: rehash p99 before/after, GC pause vs. command p99 separated, skip-list
  scaling, `redis-benchmark` ops/sec
- ⬜ 90-second demo
