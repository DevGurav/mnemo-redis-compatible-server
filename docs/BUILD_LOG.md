# Build Log — Mnemo

A running log of what was built and why. Newest first.

## Week 2 (cont.) — Sorted set over the wire

Made the sorted set built last session actually reachable from a client: `ZADD`, `ZRANK`, `ZRANGE`
now dispatch through the RESP registry.

**Context / problem.** `ZSet` existed and was unit-tested, but the keyspace (`Db`) only modelled one
value type — strings, as `key → byte[]`. To serve sorted-set commands a key has to be able to hold a
`ZSet`, and Redis's contract is that a key holds *exactly one* type: a mismatched command must return
`WRONGTYPE`, `SET` overwrites whatever was there, and `DEL`/`EXISTS` ignore type.

**What was implemented.**

- **Typed keyspace.** `Db` gained a second namespace (`key → ZSet`) alongside the string store, with
  `isString`/`isZSet` guards. `SET` now evicts any sorted set under the key; `DEL`/`EXISTS`/`size`/
  `flush` span both namespaces; `GET` on a sorted set returns `WRONGTYPE`.
- **`ZADD key score member [score member …]`** — parses every score up front so a bad float can't
  half-apply the command; returns the count of *newly added* members (updates don't count). Accepts
  `inf`/`-inf`, rejects `nan`.
- **`ZRANK key member`** — returns the member's rank. `ZSet.rank` is 1-based; Redis `ZRANK` is
  **0-based**, so the handler subtracts one and returns a null bulk for an absent member or key.
- **`ZRANGE key start stop [WITHSCORES]`** — translates Redis 0-based, end-relative, clamped indices
  onto `ZSet.rangeByRank`'s 1-based inclusive ranks; `WITHSCORES` interleaves scores formatted the
  Redis way (`1.0 → "1"`, infinities as `inf`/`-inf`).

**Tradeoffs / decisions.** The sorted-set namespace is a plain `HashMap<String, ZSet>` for now rather
than a refactor of `Dict` into a typed-value keyspace. The hand-built structures (string `Dict`, and
the `SkipList`/`Dict` inside each `ZSet`) are untouched; only the outer key→value lookup for this one
type is a library map. Unifying into a single typed keyspace dictionary is deferred until a third
value type (Hashes/Lists) makes the parallel-namespace approach stop paying — recorded as
[ADR 0007](decisions/0007-typed-keyspace.md). The two index-base mismatches (1-based `ZSet`/`SkipList`
vs. Redis 0-based) are handled at the command boundary, keeping the structures' own API natural.

**Results.** `./gradlew test` → **17 green** (added 11 in `SortedSetCommandsTest`: 0-based rank,
negative/clamped range, `WITHSCORES` score formatting, `WRONGTYPE`, `SET` overwrite, cross-type
`DEL`/`EXISTS`, invalid-float no-partial-apply, arity). `./gradlew specTest` → 60 green (unchanged).

**What's next.** Differential tests replaying command sequences against a real `redis-server`; then
`ZSCORE`/`ZRANGEBYSCORE` and the Hashes/Lists + `INCR`/`TYPE`/`KEYS`/`INFO` surface, toward the W2
`redis-benchmark` milestone. See [roadmap](roadmap.md).

## Week 2 — Structures: incremental rehashing + the sorted set

Turned the Week-1 `Dict` spec green and built the first ordered structure, then brought the project's
documentation up to a professional engineering standard.

**Context / problem.** Week 1 left `Dict` as a red spec and the keyspace running on a `HashMap`
placeholder. The headline goal of the project is *flat p99*, and the obvious threat to it is table
growth: a separate-chaining table that rehashes all at once stalls the single command thread for
milliseconds on a large keyspace — and on this design a command-thread stall is a tail-latency spike
for every connected client.

**What was implemented.**

- **Incremental, dual-table rehashing in `Dict`.** `ht[0]` drains into a double-sized `ht[1]` one
  bucket at a time; every `put`/`remove` piggy-backs a single `rehashStep`, so the migration amortizes
  across normal traffic instead of one pause. Reads probe both tables but never advance the cursor —
  a lookup costs at most one extra bounded bucket probe. New keys during a rehash go into `ht[1]` so
  they survive promotion; overwrites search both. Nodes are spliced between tables **by pointer**, so
  the `DictEntry` pool is untouched during a move (occupancy and allocation pressure unchanged across a
  resize). An empty-bucket cap (`steps × 10`) stops a sparse table from turning one step into a long
  walk.
- **`ZSet` sorted set on a span-augmented skip list.** Each forward pointer carries a `span` (level-0
  edges crossed); summing spans along the search path gives exact rank in O(log n), which drives
  `getByRank`/`range`. `ZSet` pairs the skip list with a `Dict` (member → 8-byte big-endian score) so
  membership/score stay O(1) while rank/range stay O(log n). `MAX_LEVEL=32`, `P=0.25` (Redis defaults);
  ties broken by unsigned lexicographic member order (Redis semantics).
- **JMH harness** (`src/jmh/DictBenchmark`) for rehash throughput / sampled latency, with a dev-run
  iteration profile.

**Tradeoffs / decisions (now recorded as ADRs).** Separate chaining stays, with the GC concern
answered by the entry pool rather than an open-addressing rewrite — [ADR 0003]. Incremental rehashing
over full-table rehash — [ADR 0004]. Skip list over `TreeMap` (which gives O(n) rank) and over an
augmented RB-tree (fiddlier, and diverges from Redis, costing differential-test parity) — [ADR 0005].
Week-1 decisions (hand-rolled RESP2, single-threaded command execution) and the locked logical-
`maxmemory` rule were backfilled as [ADR 0001], [ADR 0002], and [ADR 0006].

**Results.** `./gradlew test` → 6 green (RESP codec + end-to-end). `./gradlew specTest` → **60 green**
(`DictTest` 24, `DictPropertyTest` 1, `ZSetTest` 35, incl. randomized rank/range cross-checks against a
brute-force reference and mid-rehash get/put/remove/overwrite).

**What's next.** Wire `Z*` commands through the RESP registry so the sorted set is reachable over the
wire; make the JMH benchmark force the table *through* a rehash under load to produce the p99
before/after number; then Hashes/Lists + `INCR`/`TYPE`/`KEYS`/`INFO` and differential tests vs. a real
Redis. See [roadmap](roadmap.md).

[ADR 0001]: decisions/0001-hand-rolled-resp2-codec.md
[ADR 0002]: decisions/0002-single-threaded-command-execution.md
[ADR 0003]: decisions/0003-separate-chaining-with-entry-pool.md
[ADR 0004]: decisions/0004-incremental-rehashing.md
[ADR 0005]: decisions/0005-skiplist-over-treemap.md
[ADR 0006]: decisions/0006-logical-maxmemory.md

## Architecture decisions — pre-Week-2 hardening (design + docs only, no code yet)

Locked four execution-flaw patches into the [blueprint](act-as-a-senior-kind-waffle.md) and split the
engineering docs into `docs/`:

- **Hash table:** stays **separate chaining** permanently (open-addressing refactor dropped). GC
  reference-churn is handled by a **`DictEntry` object pool** — refs nulled on free, per-shard, bounded —
  not a primitive-array rewrite. Keeps incremental rehashing clean.
- **Thread boundary:** Netty `ByteBuf` alloc/release stays inside EventLoop worker threads; the decoder
  copies frames into plain POJOs, releases the buffer immediately, and only POJOs cross the MPSC queue to
  the shard executor (preserves Netty's per-thread arena fast-path; trivial buffer lifetime).
- **Memory bound:** `maxmemory` is a **deterministic logical capacity** (payload bytes / key count, O(1)
  counter), not a physical object-graph weigher with `Runtime` reconciliation — real heap is observed
  out-of-band via JFR.
- **Docs:** engineering documentation is now version-controlled under `docs/` — `architecture-spec.md`,
  `api-protocol.md`, `benchmarking-methodology.md` (the old `ARCHITECTURE.md` / `BENCHMARKS.md` folded in).

No production code changed — this is design + documentation discipline ahead of the Week-2 build.

## Week 1 — Foundation (server, protocol, dispatch, test harness)

Stood up the skeleton of a Redis-compatible server and proved it end-to-end.

- **Build:** Gradle 9.5.1 (wrapper, checksum-verified). Targets Java 21 bytecode (`options.release = 21`)
  for portability; builds and runs on the installed JDK 25.
- **Networking:** Netty 4.1 pipeline — a hand-written RESP2 decoder/encoder (`net/resp/`) that handles
  both multibulk (`redis-cli`/Jedis) and inline commands, including partial-read buffering.
- **Concurrency:** multi-threaded Netty I/O hands each command to a single command thread, so the
  keyspace is lock-free. This is the data-plane design the whole project rests on (see architecture-spec.md §1).
- **Commands:** `PING ECHO SET GET DEL EXISTS COMMAND` via a Command-pattern registry.
- **Storage seam:** a `KeyValueStore` interface with a temporary `HashMapStore` so the server runs today,
  and a `Dict` stub (the real hash table) left to implement against a red spec.
- **Tests:** `RespCodecTest` (EmbeddedChannel) and `EndToEndTest` (real socket, full PING/SET/GET/
  EXISTS/DEL round-trip) are green. `DictTest` + `DictPropertyTest` (jqwik) are tagged `spec` and
  excluded from the default build — they fail on purpose until `Dict` is implemented (`./gradlew specTest`).
- **CI:** GitHub Actions builds + runs the green tests on Temurin 21.

Verified: `./gradlew test` green; `./gradlew specTest` red (7 failing on the unimplemented `Dict`,
exactly as intended).

Next: implement `Dict` to turn the spec green, then add incremental rehashing with a JMH p99 benchmark.
