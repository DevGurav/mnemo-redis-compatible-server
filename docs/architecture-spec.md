# Mnemo — Architecture Specification

Authoritative engineering reference for the concurrency model, threading layout, memory boundary
strategy, and JVM tuning rationale. This document is the single source of truth for structural
decisions; protocol contract is in [api-protocol.md](api-protocol.md); measurement methodology is
in [benchmarking-methodology.md](benchmarking-methodology.md).

Runtime: **Java 21 bytecode, executed on JDK 25, Generational ZGC.** Single-node.

---

## 1. Concurrency model — two isolated domains

All concurrency in Mnemo is partitioned into exactly two domains with a single, typed hand-off
point between them. No other cross-thread communication exists.

```
                       ┌─────────────────────────── Netty ───────────────────────────────┐
 clients ── TCP/RESP ──▶ boss EventLoop        : accept(), register connection             │
                       │ worker EventLoopGroup : read · RESP-decode · RESP-encode · write  │
                       └──────────────────┬──────────────────────────▲────────────────────┘
                                          │ ParsedCommand (byte[][])  │ reply POJO
                                          ▼  MpscArrayQueue            │ eventLoop.execute()
                       ┌──────────────────────────────────────────────┴────────────────────┐
                       │ shard executor — ONE dedicated thread per shard                     │
                       │   CommandRegistry.dispatch() → Db → Dict / SkipList / ... → reply  │
                       └────────────────────────────────────────────────────────────────────┘
```

### I/O domain (multi-threaded, Netty-managed)

Netty worker EventLoops handle all socket I/O, run the `RespDecoder` and `RespEncoder` pipeline
stages, and drive `Channel` lifecycle. This domain scales across CPU cores for connection
throughput and does not touch application state.

### Data-plane domain (single-threaded per shard)

Every command **execution** and every mutation of `store/` structures executes on one thread per
shard. There are no locks, no `synchronized` blocks, and no concurrent collections anywhere in
`store/`. The shard thread is the sole owner of its `Dict`, `SkipList`, `CapacityGuard`, and
eviction state.

**Consequence:** all data-structure code in `store/` may be written as plain, single-threaded
Java. Correctness is guaranteed by the threading model, not by the structures themselves.

### Hand-off point

A `MpscArrayQueue<ParsedCommand>` (JCTools) connects each Netty worker to the shard executor.
The queue is single-producer-per-worker, multi-consumer-single-actual-consumer — chosen because
the MPSC discipline eliminates CAS contention on the consumer (shard) side entirely.

**Ordering:** Netty delivers reads per-connection in arrival order; FIFO queue + single-consumer
shard preserves that order per connection. Cross-connection interleaving is non-deterministic and
correct under Redis semantics.

**Week-1 topology:** one shard, one executor thread.  
**Week-4 topology:** `ShardRouter` computes `shard = CRC16(key) % shardCount`; each shard owns
an independent thread, queue, `Db`, and structure set (shared-nothing, Redis-Cluster style).
Multi-key commands co-locate via `{hashtag}`; cross-shard ops are scatter-gather. Lock-striping
was evaluated and rejected: at nanosecond-scale op durations the lock acquisition cost exceeds
the work cost.

---

## 2. Thread-boundary isolation — the ByteBuf rule

**A Netty `ByteBuf` never crosses a thread boundary.** It is allocated, used, and released
exclusively on the EventLoop worker that owns the connection.

| Stage | Thread | Owns |
| --- | --- | --- |
| `RespDecoder` parses RESP frame → copies payload into `ParsedCommand` (`byte[][]`) → releases `ByteBuf` | EventLoop worker | inbound pooled `ByteBuf` |
| Command executes on `Db`; builds a reply POJO (`String` / `long` / `byte[]`) | shard executor | plain JVM heap objects |
| `RespEncoder` allocates a pooled `ByteBuf`, serializes reply POJO, writes; Netty releases it | EventLoop worker | outbound pooled `ByteBuf` |

Only **POJOs** — `ParsedCommand` inbound, reply POJO outbound — traverse the `MpscArrayQueue`.

**Rationale:**

- `PooledByteBufAllocator` maintains **per-thread arenas** (`PoolThreadCache`). Allocating on
  thread A and releasing on thread B forces arena lock acquisition on B, defeats the cache-local
  free path, and fragments the arena. Confining a buffer to its originating EventLoop preserves
  the O(1) amortized allocation contract.
- `ByteBuf` is **reference-counted**. Cross-thread ownership introduces shared mutable reference
  counts, opening use-after-free, double-free, and leak vectors. Single-thread confinement makes
  lifetime trivially correct by construction.
- The `MpscArrayQueue` therefore carries only **immutable, GC-managed `byte[]` arrays** — safe
  to publish across threads without any additional synchronization.
- **Accepted cost:** one `byte[]` copy at decode time. This copy is cheap (cache-warm, sequential
  bytes), and the clean ownership model is worth it unconditionally.

---

## 3. Memory management — logical capacity bounds

`maxmemory` in Mnemo is a **deterministic logical capacity limit**, not an attempt to measure
the JVM object graph at runtime.

Two modes (configured at startup, immutable thereafter):

- **`maxbytes`** (default) — Σ(`key.length` + `value.length`) across all live entries.
- **`maxkeys`** — live entry count.

A `CapacityGuard` holds a single `long usedPayloadBytes` (or `long usedKeys`) updated O(1) on
every `put` (delta = new payload − displaced payload) and `remove`. No heap scanning. No JVM
introspection. Deterministic and JVM-version-independent.

**Why logical, not physical:**

- No cheap exact retained-size API exists. `Instrumentation.getObjectSize()` is shallow and
  requires a `-javaagent`; a true retained-size walk (following `DictEntry → String → byte[]`
  chains) is pointer-chasing, cache-miss-heavy, and unacceptable on the write path.
- Shallow object size is a function of JVM internals: object header layout, 8-byte alignment,
  compressed-oops state at the 32 GiB heap boundary, compact-string encoding. Encoding these
  constants makes the limit fragile across JDK versions.
- `Runtime.freeMemory()` and `totalMemory()` are whole-heap, non-deterministic, and include
  uncollected garbage, Netty buffer pool arenas, and class data. They cannot attribute a
  per-write delta to a key. Unusable for admission control.
- A per-write graph walk degrades O(1) amortized put to an unpredictable, cache-miss latency
  spike — directly contradicting the p99 goal.

**Physical safety bridge:** size `-Xmx ≥ maxbytes × overhead_factor + rehash_reserve +
working_set`, where `overhead_factor` ≈ 1.5–3× for small entries (object headers, alignment,
pool nodes, string wrappers). Bound the **data** logically on the hot path; bound the **heap**
with `-Xmx` + ZGC; observe physical heap out-of-band via JFR and `gc.log`.

### Eviction ↔ rehash collision protocol

Incremental rehashing duplicates the bucket-pointer array only (entries migrate, not copy),
making the resize cost bounded and reservable in advance. Under write pressure:

1. Admission-check the logical payload delta via `CapacityGuard`.
2. **Evict before allocating** — run the sampler (allocation-free, preallocated candidate buffer,
   `maxmemory-samples = 5`) before touching the bucket array.
3. **Defer resize** — never start a rehash mid-eviction pass; never run two rehashes concurrently.
4. **Fail-safe growth** — refuse a bucket-array resize if it would breach
   `maxmemory + rehash_reserve`; serve at an elevated load factor (slower lookups, never
   incorrect). A rehash is never required for correctness, only for performance.
5. **Rehash-aware sampling** — eviction candidates are sampled from both `ht[0]` and `ht[1]`
   during an active rehash.
6. **Last resort** — return `-OOM`; existing entries are always protected.

---

## 4. Hash table design — separate chaining with `DictEntry` object pool

**Separate chaining permanently** (open-addressing is excluded). Separate chaining keeps
incremental rehashing correct and simple: two tables `ht[0]` / `ht[1]`, a cursor that migrates
a bounded number of buckets per operation, with reads and removes consulting both tables during
migration.

GC reference-chasing pressure is addressed with a **`DictEntry` object pool**, not a
primitive-array layout rewrite:

```
DictEntry { int hash; String key; byte[] value; DictEntry next; }
```

A per-shard **intrusive free list** (freed nodes link through their own `next` field — zero
additional allocation):

- `acquire(hash, key, value, next)` — pop from the free list, or `new` on miss.
- `release(entry)` — null `key`, `value`, and `next`; push onto the free list if `size < maxPooled`.

**Null-on-free is not optional:** a pooled node survives promotion into old-gen. If its reference
fields are not nulled, the node retains a strong path to key and value objects — preventing their
collection and inflating old-gen occupancy. Nulled, the pooled shell is an empty object that ZGC
traces in a single pointer read.

**Invariants:**

- No synchronization — owned exclusively by the shard thread.
- Bounded by `maxPooled` — the pool cannot bloat unboundedly between workload bursts.
- Empirically verified: async-profiler allocation flame graphs must show `DictEntry` allocation
  absent from the steady-state hot path. Do not assume correctness; measure it
  (see [benchmarking-methodology.md](benchmarking-methodology.md) §2).

---

## 5. JVM tuning — Generational ZGC

Objective: keep GC max-pause below a fixed ceiling (target: < 5 ms at p99.9) independent of
heap size and allocation rate. ZGC's concurrent mark-and-relocate cycle achieves this; the
generational extension (default on JDK 23+) reduces allocation pressure on the concurrent cycle
by promoting long-lived entries to old-gen early.

```text
# Production flag set (document version of every published benchmark run)
-XX:+UseZGC                          # generational is default on JDK 23+; on JDK 21 add -XX:+ZGenerational
-Xms<N>g -Xmx<N>g                   # equal min/max — eliminates heap-resize pauses
-XX:+AlwaysPreTouch                  # fault all pages at startup; no page-fault jitter under load
-XX:SoftMaxHeapSize=<N - headroom>g  # ZGC soft ceiling; triggers GC before hard limit
-XX:-ZUncommit                       # retain physical pages after shrink; prevents OS re-fault on re-grow
-Xlog:gc*:file=gc.log:time,uptime,tags
```

**Separate reporting discipline:** GC max-pause (from `gc.log` or JFR) and command p99 (from
JMH `SampleTime` mode) are always reported as two independent numbers. Conflating them hides
whether a latency spike originates in the data plane or in a GC pause.

---

## 6. Design patterns

| Pattern | Application |
| --- | --- |
| Command + Registry | Each Redis command is a `Command` implementation; `CommandRegistry` dispatches by name |
| Strategy | Eviction policy (LRU / LFU), expiry scan, persistence backend |
| Reactor (Netty Pipeline) | Chain-of-Responsibility for `RespDecoder → CommandRouter → RespEncoder` |
| Object Pool | `DictEntry` free list; bounded, intrusive, shard-local |
| Facade | `Db` presents a typed command API over the raw `KeyValueStore` |

No dependency injection framework. All wiring is explicit constructor injection in
`MnemoServer.bootstrap()`.

---

## 7. Package map

```
server/     MnemoServer bootstrap, Config, ServerConfig
net/        Netty pipeline — RespDecoder, RespEncoder, CommandRouter, ParsedCommand
command/    Command interface, CommandRegistry, handler implementations (Ping, Set, Get, Del, …)
store/      Db, KeyValueStore, Dict (chaining + pool), SkipList
store/mem/  CapacityGuard, eviction sampler
shard/      ShardRouter (Week 4), ShardExecutor
expire/     TTL wheel (Week 3)
persist/    AOF writer (Week 4)
metrics/    INFO stats collector
```
