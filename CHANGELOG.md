# Changelog

All notable changes to Mnemo, newest first. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project is pre-1.0 and versions by
build week until it ships.

## [Unreleased] — Week 2

### Added
- Hash type: `HSET` / `HGET` / `HGETALL` / `HDEL` / `HLEN`, each hash backed by a `Dict` per key,
  plus a `Dict.forEach` iterator that stays correct mid incremental rehash. `TYPE` now reports `hash`.
- Rehash latency benchmark: a `DictSTW` stop-the-world control plus `RehashBenchmark` /
  `RehashSpikeBenchmark`, quantifying incremental rehashing's ≈57× lower worst-case resize-trigger
  latency (stop-the-world 347.6 ms → incremental 6.1 ms). See docs/benchmarking-methodology.md.
- `TYPE` / `DBSIZE` / `FLUSHDB` / `FLUSHALL` keyspace commands (`TYPE` reports `string`/`zset`/`none`).
- `INCR` / `DECR` / `INCRBY` / `DECRBY` integer counters: a missing key starts at 0, the value
  round-trips as a decimal string through `GET`/`SET`, and overflow / non-integer / WRONGTYPE are
  rejected without changing the stored value.
- `ZADD` / `ZRANK` / `ZRANGE` commands over the wire (RESP), with Redis 0-based rank semantics,
  negative/clamped `ZRANGE` indices, `WITHSCORES`, and a WRONGTYPE-guarded typed keyspace
  (one type per key; `SET` overwrites; `DEL`/`EXISTS` span types).
- Incremental (dual-table) rehashing in `Dict`: buckets drain from the old table into a double-sized
  new one a few at a time across normal traffic, instead of one stop-the-world pass.
- `ZSet` sorted set backed by a span-augmented skip list (O(log n) rank/range) paired with a `Dict`
  (O(1) membership/score).
- JMH benchmark harness (`src/jmh/`, `DictBenchmark`) for rehash throughput / sampled latency.
- Engineering docs: Architecture Decision Records (`docs/decisions/`), `roadmap`, `testing`,
  `data-model`, `glossary`, `security`, `runbook`, `observability`, `troubleshooting`; plus
  `AGENTS.md`, `CONTRIBUTING.md`, and this changelog.

### Notes
- The sorted-set structure is built and unit-tested but not yet wired through the RESP command
  registry — `Z*` commands aren't reachable over the wire yet.
- The rehash benchmark exists but doesn't yet force the table *through* a rehash under load.

## [Week 1] — Foundation

### Added
- Gradle build (checksum-verified wrapper) + GitHub Actions CI.
- Netty pipeline with a hand-rolled RESP2 codec (multibulk + inline, partial-read buffering).
- Command pattern + registry on a single command thread (lock-free keyspace).
- Commands: `PING ECHO SET GET DEL EXISTS COMMAND`.
- `KeyValueStore` seam with a temporary `HashMapStore`, and a `Dict` specified by red tests.
