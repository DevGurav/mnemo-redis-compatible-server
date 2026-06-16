# Project #3 Capstone — Mnemo (Battle-Hardened, Shippable Blueprint)

*A Redis-compatible in-memory data store in Java 21 — scoped to ship in 4 weeks and survive a hostile technical interview.*

> **Reviewed in adversarial mode.** Every performance claim is attacked first, then defended with a concrete mechanism and precise parameters. **Scope cuts locked in:** (1) **Netty** for networking (not raw NIO); (2) **on-heap only** (off-heap FFM slab dropped); (3) **no UI** — interface is `redis-cli` + a markdown performance report. Core custom DSA is *yours to implement*: **`Dict` (separate chaining + incremental rehashing), span-augmented `SkipList`, O(1) `LRU/LFU`**.
>
> **Revision — four execution-flaw patches (this version):**
> 1. **Hash table:** stay **exclusively on separate chaining** (drop the open-addressing refactor). Solve GC reference-chasing with a **`DictEntry` object pool**, not a primitive-array rewrite.
> 2. **Thread boundary:** Netty `ByteBuf` alloc/release stays **strictly inside EventLoop worker threads**; the decoder copies frames into plain POJOs, releases the buffer immediately, and only POJOs cross the MPSC queue to the shard.
> 3. **Memory bound:** `maxmemory` is a **deterministic logical capacity** (key count / raw payload bytes), **not** a physical object-graph weigher with `Runtime` reconciliation.
> 4. **Docs:** all engineering docs are version-controlled under [`mnemo/docs/`](.).

---

## Context

Project #3 is the **systems-engineering pillar** (Fall Guardian = AI, 3D Book Reader = frontend). A single-node, RESP-wire-compatible in-memory store, so real Redis clients (`redis-cli`, `Jedis`, `redis-benchmark`) connect to a server you wrote. The resume value is that you can **defend every number under fire** and made **senior scoping calls**. Deep design lives in [`architecture-spec.md`](architecture-spec.md); protocol in [`api-protocol.md`](api-protocol.md); measurement in [`benchmarking-methodology.md`](benchmarking-methodology.md).

**Working rule (unchanged):** the data structures are yours to implement. Non-DSA plumbing (Netty pipeline, RESP codec, Docker, CI) is scaffolded; the algorithm is never handed to you.

---

## §0 — The Adversarial Review (read this first)

### 0.1 The JVM Garbage-Collection Paradox

**Critique.** *"Flat p99 via incremental rehashing is meaningless on the JVM — every `DictEntry` is a heap object; at a high insert rate you allocate hundreds of MB/s, GC stalls every thread, p99 spikes regardless."*

He is right. The on-heap defense is **three layers: bound the pause, cut allocation at the source, minimize the bytes per op.**

**Layer 1 — Bound the pause: Generational ZGC.** Pauses are **O(1) in heap size** (concurrent mark/relocate behind load barriers), typically sub-millisecond.

```text
# JVM flags — version-aware (the repo runs on JDK 25):
-XX:+UseZGC                              # JDK 23+: generational ZGC is the DEFAULT (no +ZGenerational)
#   on JDK 21 only: add -XX:+ZGenerational
-Xms<N>g -Xmx<N>g -XX:+AlwaysPreTouch    # fixed, pre-zeroed heap → no resize/first-touch latency
-XX:SoftMaxHeapSize=<N-headroom>g        # soft target; reserves headroom for the rehash bucket array
-XX:-ZUncommit                           # don't return pages to OS (avoids re-fault latency)
-Xlog:gc*:file=gc.log:time,uptime,tags   # so you can SHOW the pause distribution
```

**Layer 2 — Cut allocation at the source: a `DictEntry` object pool (NOT open-addressing).** We keep **separate chaining permanently** so incremental rehashing stays clean (Patch 1). Reference-chasing is attacked by **reusing `DictEntry` nodes** instead of churning them:

- A per-shard **free list** of `DictEntry` (intrusive — freed nodes link via their own `next`, zero extra allocation). `acquire(...)` pops or `new`s; `release(e)` **nulls `key`/`value`/`next` references** then pushes onto the free list if under a cap.
- **Why null on free:** a pooled node survives into old gen; if it still pointed at a key/value it would retain memory and cost the collector a trace. Nulled, an idle pooled node is an **empty shell** — a small, stable set the GC scans cheaply.
- **Why it is lock-free:** the data plane is single-threaded per shard (§0.3), so the pool needs no synchronization.
- **Bounded:** cap the free list so the pool itself can't bloat.
- **Honest caveat (say it):** naive pooling can *defeat* the generational hypothesis (pooled objects live long). Mitigated by null-on-free + a cap, and **verified with async-profiler allocation flame graphs** — you measure the win, you don't assume it.

**Layer 3 — Minimize bytes per op.** Netty **pooled `ByteBuf`** recycles request/response buffers (Patch 2 keeps this fast — see §0.4); no boxing (`INCR` mutates a primitive `long`); flyweight replies (`+OK`, small integers — Redis's `OBJ_SHARED_INTEGERS = 10000`).

**Prove it:** report **GC max-pause** (from `gc.log`/JFR) and **command p99** (JMH `SampleTime`) as *two separate curves*; Epsilon GC finds the allocation ceiling.

> **Interview defense:** *"Incremental rehashing removes the algorithmic spike; ZGC bounds the GC pause sub-millisecond; and I cut allocation at the source with a per-shard `DictEntry` pool — references nulled on free, bounded, single-threaded so it needs no locks — keeping clean separate chaining instead of an open-addressing rewrite. I verified the allocation drop with async-profiler rather than assuming it, because naive pooling can backfire on a generational GC."*

### 0.2 Memory Bound — Logical Capacity, not Physical Weighing

**Critique.** *"Two tables live mid-rehash; the server hits `maxmemory` on a write that must grow the table. Crash? Corrupt? Deadlock?"*

**Patch 3 — what `maxmemory` actually means.** We **do not** try to measure the JVM object graph. `maxmemory` is a **deterministic logical capacity**, one of:

- **`maxkeys`** — maximum entry count, or
- **`maxbytes`** — total **raw payload** bytes = Σ `key.length + value.length` (the data you stored, not its heap footprint).

Maintained **O(1)**: a `long usedPayloadBytes` updated by the delta on every `put` (new − overwritten) and `remove`. Deterministic, JVM-version-independent, zero hot-path graph traversal.

**Why physical weighing is the wrong tool on the hot path:**

- The JVM has **no cheap, exact "retained size" API**. `Instrumentation.getObjectSize()` returns the *shallow* size of **one** object and needs a `-javaagent`; the true cost of an entry (`String` key → its `byte[]`, the value `byte[]`, the `DictEntry`, the chain link) requires **walking the object graph** and summing shallow sizes — **pointer-chasing, cache-miss-heavy work proportional to entry shape, per write.**
- Shallow size itself depends on JVM internals you'd have to encode and keep current: **object header** (mark word + class pointer), **8-byte field alignment/padding**, **compressed oops** (on/off at the 32 GB boundary), array base offsets, **compact strings** (Latin-1 vs UTF-16 since JDK 9). Fragile and still not free.
- `Runtime.totalMemory()/freeMemory()` measures the **whole heap** — including uncollected garbage, Netty buffers, everything — so it is **noisy and non-deterministic** (depends on when GC last ran) and **can't attribute a delta to one key**. Useless for a per-write admission decision.
- Net: graph-walk sizing turns an O(1) write into **unpredictable latency** (cache misses) — directly sabotaging the p99 goal.

**The bridge to physical safety:** set `-Xmx` to comfortably exceed `maxbytes × overhead_factor` (empirically ~1.5–3× for small entries: headers + chaining + String overhead) `+ rehash_reserve + working set`. You bound the **data** deterministically on the hot path; you bound the **heap** with a sized `-Xmx` + ZGC, and you *observe* real heap via JFR/gc-log **out-of-band**, never per-op.

**The eviction ↔ rehash collision protocol** (now keyed off the logical counter). First, the precise fact: incremental rehashing duplicates the **bucket-pointer array only** — entries are *moved*, not copied (~40 MB transient for 10M keys, bounded + pre-reservable). Then, on a write under pressure, in order:

```text
maxmemory    = logical threshold (maxbytes | maxkeys)   ← O(1) counter, the eviction trigger
rehash_reserve = headroom ≥ next bucket array            ← pre-reserved
```

1. **Admission-check** the logical delta.
2. **Evict before allocating** (allocation-free sampler, `maxmemory-samples=5` into a preallocated buffer).
3. **Defer resize to a safe point** — never start a rehash mid-eviction; never run two at once.
4. **Fail-safe grow** — refuse the bucket-array grow if it would breach the ceiling; serve at a higher load factor (slower, never broken). *A rehash is a performance optimization, never a correctness requirement.*
5. **Rehash-aware eviction** — sample both tables; delete already handles both.
6. **Last resort:** `-OOM command not allowed`; existing data always protected.

> **Interview defense:** *"`maxmemory` is a logical capacity — a payload-byte counter I keep in O(1) — not a heap estimate, because the JVM has no cheap exact retained-size API and graph-walking sizes would add cache-miss latency to every write. I size `-Xmx` with headroom and watch real heap via JFR off the hot path. Under pressure I evict before allocating, never resize mid-eviction, refuse a rehash-grow rather than breach the ceiling, and `-OOM` as the last resort — existing data is never dropped."*

### 0.3 The 16-Core Concurrency Question

I/O concurrency and data-plane concurrency are **separate decisions.** Netty's worker `EventLoopGroup` is multi-threaded for sockets; command **execution** is funneled to a **single-threaded shard executor** so the structures stay lock-free.

| Axis | A. Single-thread + keyspace sharding (shared-nothing) | B. Lock-striping (shared keyspace) |
| --- | --- | --- |
| Locks on hot path | none | every op (CAS + fences) — often ≥ the op for µs work |
| Incremental rehash | trivial per shard | brutal (thread-safe two-table migration) |
| Multi-key atomicity | atomic within a shard; cross-shard = scatter-gather | multiple stripe locks → lock-ordering or deadlock |
| Object pool (§0.1) | safe, unsynchronized (per shard) | pool must be thread-safe → contention |
| Prior art | Redis Cluster, ScyllaDB/Seastar, Redpanda | Java `ConcurrentHashMap`, Memcached |

**Primitives.** *A (recommended):* Netty boss/worker → **JCTools `MpscArrayQueue`** hand-off → per-shard single-thread executor; **shard = CRC16(key) % shards**; `{hashtag}` co-locates multi-key ops; write-back via `channel.eventLoop().execute(...)`. *B (if ever):* `StampedLock`, 64–256 stripes, hash-ordered acquisition.

> **Interview defense:** *"Lock-striping is the junior answer — for nanosecond ops the lock costs more than the work, which is why Redis/ScyllaDB/Redpanda shard shared-nothing. Netty gives me multi-threaded I/O; I shard the keyspace into single-threaded executors: full core use, zero data-plane locks, trivial per-shard rehashing, and my object pool needs no synchronization. I pay in cross-shard atomicity, bounded via hash-tags."*

### 0.4 Thread-Boundary Isolation (Patch 2)

**Rule: a Netty `ByteBuf` never crosses a thread boundary.** Alloc and release happen strictly on the EventLoop worker that owns the connection.

```text
EventLoop worker thread            │ MPSC queue (POJOs only)  │ shard executor thread
──────────────────────────────────┼──────────────────────────┼──────────────────────────
RespDecoder reads pooled ByteBuf   │                          │
  → copies frame into ParsedCommand│  ──ParsedCommand POJO──▶  │ runs command on Db
    (command + byte[][] args)      │                          │   → builds reply POJO
  → ByteBuf RELEASED here          │  ◀────reply POJO────────  │ (no Netty types)
RespEncoder allocs a pooled ByteBuf│                          │
  ← reply POJO (via eventLoop.execute) ─────────────────────────┘
  → encodes, writes, Netty releases│
```

**Why (the justification):**

- **`PooledByteBufAllocator` is per-thread-arena** (`PoolThreadCache`). Alloc/free is fastest on the *owning* EventLoop thread; a foreign (shard) thread releasing hits a different arena → lock contention / deferred-free → defeats the pooling fast path and invites fragmentation.
- **`ByteBuf` is reference-counted.** Crossing threads muddies ownership (who releases, when) and invites use-after-free / double-free / leaks. Confining each buffer to its creating EventLoop makes lifetime **trivially correct**.
- **The MPSC queue carries immutable, GC-owned POJOs** (`byte[]` copies) — no Netty lifecycle crosses the boundary; arrays are safe to hand across threads.
- **Cost:** one `byte[]` copy at decode time (cheap, cache-warm on the EventLoop) buys clean cross-thread ownership. Worth it.

> **Interview defense:** *"Netty's pooled buffers are thread-local-arena allocations and reference-counted, so I keep them inside the EventLoop. The decoder copies each frame into a plain `byte[]`-backed `ParsedCommand` and releases the buffer on the worker; only POJOs ride the MPSC queue to the shard; the encoder allocates and releases its response buffer back on the EventLoop. The arena fast-path stays intact and buffer lifetime is trivially correct."*

---

## §1 Core Idea

Sub-millisecond access to hot data (sessions, leaderboards, counters, caches) needs an in-memory data-structure server. Mnemo implements Redis's core types (String / Hash / List / Sorted-Set) + TTL + logical-capacity eviction + AOF persistence over the real RESP protocol, every structure hand-written — and, per §0, engineered so the claims hold under load. The bottleneck that forces custom DS: a naive `HashMap` + `synchronizedMap` + `TreeMap` gives stop-the-world resize spikes, lock contention, no bound, and no O(log n) `ZRANK`.

## §2 System Design

| Layer | Choice | Why |
| --- | --- | --- |
| Runtime | **Java 21 bytecode on JDK 25, Generational ZGC**, fixed pre-touched heap | Bounded pauses; on-heap only |
| Networking | **Netty 4** (`ServerBootstrap`, `ChannelPipeline`) | Rock-solid I/O; pooled `ByteBuf` confined to EventLoop (§0.4) |
| Wire protocol | **RESP2** (`ByteToMessageDecoder`/`MessageToByteEncoder`) | `redis-cli` / `Jedis` / `redis-benchmark` compatible |
| Data plane | **single-thread shard executor**, lock-free; POJOs over an MPSC queue | §0.3 / §0.4 |
| Hash table | **separate chaining (permanent)** + incremental rehashing + **`DictEntry` object pool** | clean rehash + low allocation (§0.1) |
| Memory bound | **logical capacity** (`maxbytes` / `maxkeys`), O(1) counter | deterministic, no hot-path weighing (§0.2) |
| Build / test | Gradle · JUnit5/AssertJ · **jqwik** · **Testcontainers** | comprehensive testing |
| Measurement | **JMH** · **async-profiler** · **JFR** · **redis-benchmark/memtier** | feeds the perf report |
| Persistence | hand-rolled **AOF** (+ replay/recovery) | durability; RDB is future work |
| Interface | **`redis-cli` + `INFO` + the perf report** | no UI |
| Ops | Docker · GitHub Actions · Fly.io/Railway | reuse your Fall Guardian DevOps |

**Patterns:** Command + Registry · Strategy (eviction/expiry/persistence) · Netty Pipeline (Reactor + Chain-of-Responsibility) · Object Pool (`DictEntry`) · Facade (`Db`). **No Spring** — hand-wired DI.

## §3 Algorithmic Component — you implement

| # | Structure (YOU build) | Target | Hardened note |
| --- | --- | --- | --- |
| 1 | **`Dict`** (separate chaining, permanent) | get/put/del O(1) avg; no op > a few buckets | incremental rehashing (two tables); **`DictEntry` object pool**, refs nulled on free (§0.1) |
| 2 | **`SkipList` + `ZSet`** | ZADD/ZRANK/ZRANGE O(log n); ZSCORE O(1) | **span** per forward pointer for O(log n) rank; member→score dict alongside |
| 3 | **LRU / LFU evictor** | O(1) get/put/evict | allocation-free sampler; compare exact vs approximate-LRU |
| 4 | `IntrusiveList` (Lists) | LPUSH/RPOP O(1), LRANGE O(k) | — |
| 5 | Expiry index | lazy + active | active sampler must not scan all keys |
| 6 | RESP codec (Netty) | decode/encode | copies to POJO + releases ByteBuf on the EventLoop (§0.4) |

## §4 Roadmap — 4 weeks

- **W1 Foundation ✅:** Gradle + CI; Netty server + RESP2 codec; Command/Registry; single command thread; `PING ECHO SET GET DEL EXISTS`; **`Dict` spec (chaining, red)** + jqwik. *Done — `redis-cli` set/get works in-process via EndToEndTest.*
- **W2 Structures + headline opt:** **incremental rehashing** + **JMH p99 before/after**; **`SkipList`/`ZSet`** (fuzz vs `TreeMap`); Hashes, Lists, `INCR/TYPE/KEYS`, `INFO`; **differential tests** vs real Redis. *Milestone: `redis-benchmark` first numbers.*
- **W3 Ops + §0 hardening:** TTL (lazy+active); **LRU/LFU** + the **§0.2 logical-capacity protocol**; **`DictEntry` object pool** + async-profiler allocation comparison; **AOF** + crash-recovery test; ZGC/JFR/Epsilon GC pass. *Milestone: survives `kill -9`; stays under `maxmemory` mid-rehash.*
- **W4 Concurrency + ship:** finalize single-thread executor; **shard across cores + benchmark** (the §0.4 POJO-over-MPSC boundary); Docker + Actions; **deploy**; write the **performance report** (rehash p99 before/after, **GC pause vs command p99 separated**, skiplist scaling, redis-benchmark ops/sec); 90s demo.

## §5 Interview Talking Points

1. **Flat p99 is a two-front war** — incremental rehashing (algorithmic) + ZGC & a `DictEntry` object pool (GC), measured separately. (§0.1)
2. **`maxmemory` is logical, not physical** — an O(1) payload-byte counter, because exact heap sizing is non-deterministic and cache-miss-heavy on the hot path. (§0.2)
3. **Multi-threaded I/O, single-threaded data plane** — shared-nothing sharding vs lock-striping, and why `ByteBuf` never leaves the EventLoop. (§0.3 / §0.4)
4. *(bonus)* span-augmented skip-list O(log n) rank (LC 1206 extended); AOF replay + fsync trade-off; differential testing vs real Redis.

## §6 Scope Discipline & Assumptions Audit

- **Cut to ship:** off-heap FFM slab (dropped); **open-addressing (dropped — chaining only)**; physical object-graph weighing (dropped — logical capacity); UI/Prometheus/Grafana (dropped — `redis-cli`/`INFO` + report); RDB (AOF-only v1); Pub/Sub, `MULTI/EXEC`, RESP3 (future); full lock-striping (analyzed, not built).
- **Claims to qualify:** publish *measured* numbers + methodology + hardware; "flat p99" = *command* p99 with GC pause reported separately; never "beats Redis"; differential tests scope an explicit command set.
- **Single-node:** no replication/consensus.

## §7 Verification

`./gradlew test` (green plumbing) + `./gradlew specTest` (your red DS specs), **JaCoCo ≥80%** on `store/`,`command/` · **Testcontainers differential** vs real Redis · `redis-benchmark/memtier` (p50/p99 + GC max-pause *separately*) · crash-recovery (`kill -9` → AOF replay) · capacity test (load past `maxmemory` *during* a forced rehash → bounded, no crash) · async-profiler allocation comparison (pool on/off).

## §8 Repo Structure (YOU implement marked)

```text
mnemo/  build.gradle.kts  README.md
 src/main/java/dev/devgurav/mnemo/
   server/  MnemoServer Config
   net/     MnemoChannelInitializer  RespDecoder  RespEncoder  CommandInboundHandler  ParsedCommand
   command/ Command CommandRegistry strings/* hashes/* list/* zset/* server/{Ping,Echo,Command,Info}
   store/   Db RedisObject  KeyValueStore HashMapStore
            Dict←YOU (chaining + incremental rehash)   entry/{DictEntry,DictEntryPool}←YOU
            SkipList ZSet←YOU   list/IntrusiveList←YOU   evict/{Lru,Lfu}←YOU   mem/CapacityGuard
   shard/   ShardRouter ShardExecutor (MpscArrayQueue hand-off — POJOs only)
   expire/  ExpiryService←YOU(active sampler)   persist/ Aof PersistenceStrategy   metrics/ Stats(→ INFO)
 src/test/java/...  JUnit5 · jqwik · Testcontainers
 src/jmh/java/...    JMH benchmarks (Dict rehash p99, SkipList scaling)
 docs/    architecture-spec.md · api-protocol.md · benchmarking-methodology.md · BUILD_LOG.md · (this blueprint)
 docker/  Dockerfile compose.yml      .github/workflows/ci.yml
```

## §9 Resume Payload (fill measured numbers)

> **Mnemo — Redis-compatible in-memory data store (Java 21, Netty).** Hand-built **separate-chaining hash table with incremental rehashing and a `DictEntry` object pool**, span-augmented skip list (O(log n) `ZRANK`), O(1) LRU/LFU eviction. Engineered for tail latency: **Generational ZGC + a per-shard object pool + Netty pooled buffers confined to the EventLoop** holding max GC pause **<__ms** while command p99 stays flat across resizes; **shared-nothing keyspace sharding** (multi-threaded I/O, single-threaded data plane, POJOs over an MPSC queue) for multi-core scaling; **deterministic logical-capacity** memory bound + safe eviction-during-rehash protocol; AOF crash recovery. Validated with Redis's own `redis-benchmark` (**~__k ops/sec**), property-based + differential tests vs real Redis, **__% coverage**; Docker + GitHub Actions; deployed live.

**Open decisions:** exact vs approximate LRU · `maxbytes` vs `maxkeys` default · sharding as feature vs analyzed design.
