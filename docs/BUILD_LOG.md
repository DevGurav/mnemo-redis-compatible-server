# Build Log — Mnemo

A running log of what was built and why. Newest first.

## Week 2 (tail) — KEYS + differential oracle tests

Closed the two remaining Week-2 items and declared W2 complete.

**Context.** `KEYS` and differential tests were deferred from the W2 main push because they require
a cross-namespace scan (touching all four keyspace maps) and an external Redis process to compare
against. Both depended on the W2 data-structure work being stable first.

**What was built.**

- **`KEYS pattern`** — scans all four namespaces (strings, zsets, hashes, lists) and returns matching
  keys as a RESP array. Pattern matching via `GlobPattern` (package-private, `command.keyspace`):
  full Redis glob subset — `*` (zero-or-more), `?` (one char), `[charset]`, `[^charset]` / `[!charset]`
  negation, `[a-z]` ranges, and `\x` literal-escape. The scan is exposed through
  `KeyValueStore.forEachKey(Consumer<String>)` (new interface method, implemented in both
  `HashMapStore` and `Dict`) and `Db.keys(Predicate<String>)` so the command layer owns the
  pattern-matching concern and `Db` stays decoupled from it.
- **Differential (oracle) tests** — `DifferentialTest` boots a `redis:7-alpine` container via
  Testcontainers alongside a live Mnemo instance and asserts that identical command sequences return
  identical RESP wire responses. Covers: strings (SET/GET/DEL/EXISTS), integers (INCR/DECRBY),
  WRONGTYPE errors, hashes (HSET/HGET/HGETALL/HDEL/HLEN), lists (LPUSH/RPUSH/LRANGE/LPOP/RPOP),
  sorted sets (ZADD/ZRANK/ZRANGE), KEYS glob patterns (including cross-namespace), TYPE, DBSIZE,
  FLUSHDB. Unordered-response commands (KEYS, HGETALL) are compared semantically (sorted element
  list / field→value map) rather than byte-for-byte. Tagged `@differential`; run with
  `./gradlew differentialTest` (requires Docker). Excluded from the default `./gradlew test` gate so
  CI stays portable on machines without a container daemon.

**Verification.** `./gradlew test` → **92 green** (+13 `KeysCommandTest`); `specTest` → 60
(unchanged). No regressions across any prior test.

**What's next.** Week 2 is complete. Week 3 continues — TTL (lazy + active expiry), LFU eviction
policy, AOF persistence. See [roadmap](roadmap.md).

## Week 3 — Memory bounding + approximate-LRU eviction

Implemented the `maxmemory` bound [ADR 0006](decisions/0006-logical-maxmemory.md) promised, with a
Redis-style random-sampling LRU evictor.

**Context.** Until now the keyspace grew without limit — the headline reliability gap before any
"survives under load" claim. [ADR 0006](decisions/0006-logical-maxmemory.md) had locked the *meaning*
of `maxmemory` (a logical byte counter, not a heap weigh) but left it unimplemented. Three things had
to land together: the counter, per-key recency tracking, and an evictor that honours the
project's zero-allocation thesis on the command path.

**What was built.**

- **`SizeWeigher`** — the logical cost of a mapping: `key + value + ENTRY_OVERHEAD_BYTES`. The counter
  lives in the store that holds the bytes (`Dict`/`HashMapStore` maintain `usedMemory()` on every
  put/overwrite/remove, where the old value is already in hand — no extra lookup). `Db.usedMemory()`
  exposes it. Keeping it in the store guarantees the weight added on insert is the exact weight
  subtracted on eviction — no `String`↔`byte[]` drift.
- **Logical access clock** — `DictEntry` gains a `long lruTime`, stamped from a per-`Dict`
  monotonically-incremented counter on every read and write. One increment per access, no
  `currentTimeMillis` syscall on the read path; the approximation lives in the sampling, not the clock.
- **`Evictor`** — when `usedMemory > maxmemory`, draw `N=5` random entries straight from the `Dict`
  bucket arrays (`Dict.randomEntry`), keep the smallest `lruTime`, delete it (`Dict.removeByteKey`, so
  no `String` is rebuilt), repeat until under budget. The sampler is **100% allocation-free**: one
  `DictEntry` reference + a `long` for the best candidate, an inlined xorshift for randomness, entries
  read in place — no arrays, iterators, or boxing.
- **Wiring** — `maxmemory` added to `Config` (env `MNEMO_MAXMEMORY` / `--maxmemory`, default 0 =
  unlimited). The shard runs `evictor.evictIfNeeded()` *before* each command (Redis pre-command
  eviction), armed only when `maxmemory > 0` and the store is a `Dict`. `INFO`'s Memory section now
  reports the true logical `used_memory`, the configured `maxmemory`/policy, and cumulative
  `evicted_keys`.

**Verification.** `EvictorTest` proves the bound holds: it evicts down to budget, leaves an
under-budget keyspace untouched, keeps `used_memory` exactly equal to the surviving keys' weight across
evict/insert churn, spares a repeatedly-accessed "hot" key, and — the headline — drives **50k inserts
through a 200-entry budget** and asserts the resident set stays ≤200 keys, i.e. memory is bounded and
the server cannot OOM from keyspace growth. `./gradlew test` → **79 green** (+6); `specTest` → 60
(unchanged — the `Dict` changes keep every existing spec green).

**Decision recorded.** [ADR 0010](decisions/0010-random-sampling-lru-eviction.md) captures the
algorithm (random-sampling approximate LRU), the logical clock over wall-time, the counter-in-the-store
placement, and string-keyspace-only scope; it implements [ADR 0006](decisions/0006-logical-maxmemory.md).

**What's next.** Week 3 continues — TTL (lazy + active expiry), then LFU policy and AOF persistence /
crash-recovery. Week 2 has one tail item left: the `KEYS` command and differential tests vs. a real
`redis-server`. See [roadmap](roadmap.md).

## Week 2 (close) — Lists (LPUSH/RPUSH/LPOP/RPOP/LLEN/LRANGE) + the `INFO` endpoint

Added the fourth and final Week-2 value type and the observability surface, closing out W2.

**Context.** Lists are the one W2 type that is a genuinely new structure rather than a reuse of
`Dict` — and the natural place to apply the `DictEntryPool` pattern a second time. `INFO` is the
introspection surface [observability.md](observability.md) and [api-protocol.md](api-protocol.md) §5
promised but hadn't been wired.

**What was built.**

- **`IntrusiveList`** — a hand-built doubly-linked list with head/tail pointers: O(1)
  `lpush`/`rpush`/`lpop`/`rpop`, and `lrange` that walks from the nearer end.
- **`ListNodePool`** — the structural twin of `DictEntryPool`: an intrusive free list threaded
  through each node's `next`, nulling `value`/`prev`/`next` on release *before* the capacity check.
  Push acquires a shell, pop releases one, so a steady-depth queue allocates nothing in the hot path
  (the zero-allocation thesis, [ADR 0003](decisions/0003-separate-chaining-with-entry-pool.md)).
- **A fourth keyspace namespace** in `Db` (`key → IntrusiveList`). One-type-per-key is preserved by
  extending the `WRONGTYPE` guards a fourth way — every string/sorted-set/hash command now also checks
  `isList`, the list commands check the other three, and `TYPE` reports `list`.
- **`LPUSH`/`RPUSH`** (return new length, multi-value), **`LPOP`/`RPOP`** (drop an emptied list, per
  Redis), **`LLEN`**, **`LRANGE`** (Redis negative/clamped index semantics).
- **`INFO [section]`** — a Redis-compatible bulk string over four sections: Server (version/JDK/GC/
  uptime), Clients (a live connected-channel count), Memory (`maxmemory:0` + real `used_memory`),
  Keyspace (per-type key counts). Backed by a single `ServerStats` shared between the registry and a
  `@Sharable` connection-counter handler in the Netty pipeline; the counter is atomic because connect/
  disconnect run on worker threads while `INFO` reads on the shard thread.

**Verification.** `ListCommandsTest` drives every command through the real registry — ordering, the
four-way `WRONGTYPE` guards both directions, empty-key deletion, `LRANGE` index edges — and asserts
the GC thesis directly on `IntrusiveList`: popped nodes return to the pool and a later push reuses one.
`InfoCommandTest` checks each section, section filtering, and that Clients/Keyspace track live state.
`./gradlew test` → **73 green** (+24); `specTest` → 60 (unchanged — the new tests are plumbing, not
spec).

**Decision recorded.** Lists landed as a fourth parallel namespace, holding the keyspace unification
[ADR 0008] foresaw at this exact point; it's now pinned to the W4 sharding rework where a type-tagged
keyspace `Dict` and a centralized `typeOf` collapse the now four-way guards. `INFO` deliberately omits
the logical payload-byte counter, which is the W3 eviction work
([ADR 0009](decisions/0009-list-type-and-info.md)).

**What's next.** Week 3 — TTL/expiry and the logical-`maxmemory` eviction counter (LRU/LFU); then
differential tests vs. a real `redis-server`. See [roadmap](roadmap.md).

## Week 2 (cont.) — Hashes (HSET/HGET/HGETALL/HDEL/HLEN) + the Dict iterator

Added the third value type, and the dual-table iterator it required.

**Context.** A Redis hash is a field→value map — exactly what the hand-built `Dict` already is. The
one missing piece: `HGETALL` has to enumerate the whole hash, but `Dict` had no safe traversal, and
during an incremental rehash a hash's entries are split across both internal tables.

**What was built.**

- **`Dict.forEach(BiConsumer<byte[],byte[]>)`** — walks `ht[0]` and, while rehashing, `ht[1]`. An
  entry lives in exactly one table (migration *moves* nodes; new keys land in `ht[1]`), and buckets
  below `rehashidx` are already drained to null, so every entry is yielded exactly once — including
  mid-migration.
- **A third keyspace namespace** in `Db` (`key → Dict`), reusing `Dict` for each hash. One-type-per-key
  is preserved by extending the `WRONGTYPE` guards — every string/sorted-set command now also checks
  `isHash`, and `TYPE` reports `hash`. ([ADR 0008](decisions/0008-hash-type.md).)
- **`HSET`** (counts newly-added fields), **`HGET`**, **`HGETALL`** (via `forEach`), **`HDEL`** (drops
  an emptied hash, per Redis), **`HLEN`**.

**Verification.** `DictIteratorTest` (plumbing, in the `store` package so it can read the rehash state)
asserts `forEach` completeness directly against an *active* rehash with entries proven to span both
tables. `HashCommandsTest` drives `HGETALL` across field counts 1 → 100 — many of which leave the
inner `Dict` mid-rehash — and asserts every field comes back. `./gradlew test` → **49 green** (+16);
`specTest` → 60 (unchanged).

**Decision recorded.** Hash landed as a third parallel namespace, *not* the [ADR 0007] keyspace
unification — that's deferred to the W3–W4 keyspace/sharding rework, where a type-tagged keyspace
`Dict` plus a centralized `typeOf` is the natural home for the now-repetitive `WRONGTYPE` guards
([ADR 0008](decisions/0008-hash-type.md)).

**What's next.** Lists (a genuinely new structure), then `INFO`; differential tests vs. a real
`redis-server`. See [roadmap](roadmap.md).

## Week 2 (cont.) — Flagship benchmark: incremental vs stop-the-world rehash

Closed the standing benchmark gap and put a defensible number on the project's headline claim.

**Context.** The architecture rests on *incremental rehashing flattens the tail latency a
stop-the-world resize spikes*. But `DictBenchmark` only measured steady state (a pre-grown table);
the growth/resize behaviour — the whole point — was unmeasured.

**What was built.** A control engine, `DictSTW` — a clone of `Dict` reusing the same chaining and
`DictEntryPool`, with the dual-table rehash swapped for a naive one-shot `resize()`, so the only
variable is the rehash strategy — plus two benchmarks: `RehashBenchmark` (SampleTime, growth from
empty) and `RehashSpikeBenchmark` (SingleShot, isolates the resize-triggering put). Run under ZGC so a
collector pause can't be mistaken for the algorithmic spike.

**It took two tries, and I kept the first (negative) result.** `RehashBenchmark` (SampleTime) showed
the two engines as identical — p99 ≈ 1 µs both, p100 ≈ 4 ms both — because at 65 k entries a single
resize (~0.5 ms) sits below the JIT/GC noise floor, and SampleTime sub-samples a ~1-in-5 000 event.
Diagnosed and fixed by isolating the resize-triggering put directly: a SingleShot benchmark primed to
the load-factor edge of a much larger table (capacity 4 M, ~3.1 M-entry copy), heap GC-stabilised for
a tight interval.

**Finding.** The single put that crosses the load factor — stop-the-world **347.6 ms ± 54.9** vs
incremental **6.13 ms ± 1.47**, ≈ **57×** lower worst-case latency. STW copies all 3.1 M entries in
one shot (a ~⅓-second stall); incremental allocates the doubled array, migrates one bucket, and
amortises the rest. Honest scope: incremental removes the O(n) *copy*, not the array *allocation* —
both still pay ~6 ms to allocate the 64 MB backing array; the win is killing the stall. Bulk inserts
(p50–p99) are equal between the engines. Full methodology, environment, and the negative-result
write-up are in [benchmarking-methodology.md](benchmarking-methodology.md) Headline 1.

**What's next.** A publish-grade multi-fork run + the GC-pause-vs-command-p99 split (Headline 2);
then the remaining command surface (Hashes/Lists) and differential tests. See [roadmap](roadmap.md).

## Week 2 (cont.) — Keyspace commands (TYPE/DBSIZE/FLUSH)

Added the keyspace introspection/admin surface: `TYPE`, `DBSIZE`, `FLUSHDB`, `FLUSHALL`.

**Context.** With strings and sorted sets both in the keyspace, clients (and `redis-cli` sessions)
need to ask what a key holds and how big the keyspace is. These are thin commands over the typed `Db`
built last session — no new structures.

**What was implemented.** `TYPE key` returns `string`/`zset`/`none` via the existing `isString`/
`isZSet` guards (a natural validation of the typed keyspace). `DBSIZE` returns `Db.size()` (keys across
all types). `FLUSHDB`/`FLUSHALL` call `Db.flush()`; they're identical today on a single-node, one-DB
server but kept distinct for client compatibility. New `command/keyspace/` package.

**Results.** `./gradlew test` → **33 green** (added 6 in `KeyspaceCommandsTest`: type per value kind,
type after a SET overwrite, DBSIZE across types and after DEL, FLUSHDB/FLUSHALL emptying, arity).
`specTest` → 60 green (unchanged).

**What's next.** The bigger items toward the `redis-benchmark` milestone: Hashes (reusing `Dict` for
field storage — needs an iteration method on `Dict` for `HGETALL`), Lists, `INFO`; and differential
tests vs. a real `redis-server`. There's also the standing flagship gap: make `DictBenchmark` drive an
active rehash to produce the p99 before/after number. See [roadmap](roadmap.md).

## Week 2 (cont.) — Integer counters (INCR/DECR)

Added the `INCR` / `DECR` / `INCRBY` / `DECRBY` family — the next command surface toward the
`redis-benchmark` milestone (`INCR` is one of its default workloads).

**Context.** Redis models a counter as an ordinary string whose value happens to be a decimal
integer, so a counter must round-trip through `GET`/`SET`. The work is the command-layer arithmetic
and its error contract, not a new structure.

**What was implemented.** A shared `NumericString.apply(key, operand, subtract)` helper: a missing key
starts at 0; the current value is parsed as a signed 64-bit integer; the delta is applied with
`Math.addExact`/`subtractExact`; the decimal-string result is written back. The four commands are thin
wrappers (`INCR`/`DECR` pass operand 1; `INCRBY`/`DECRBY` parse the delta first).

**Error contract (matches Redis).** Non-integer stored value or non-integer delta →
`ERR value is not an integer or out of range`; a result outside `long` →
`ERR increment or decrement would overflow`; a sorted-set key → `WRONGTYPE`. On any error the stored
value is left untouched (overflow is checked *before* the write).

**Results.** `./gradlew test` → **27 green** (added 10 in `StringNumberCommandsTest`: missing-key-is-
zero, decimal round-trip via `GET`, `INCRBY`/`DECRBY` deltas, non-integer value/delta, overflow and
underflow rejection with the value preserved, `WRONGTYPE`, arity). `specTest` → 60 green (unchanged).

**What's next.** `TYPE`/`DBSIZE` introspection and the Hashes/Lists surface; differential tests vs. a
real `redis-server`. See [roadmap](roadmap.md).

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
