# 10. Random-sampling approximate LRU eviction with a logical access clock

- Status: Accepted (implements [ADR 0006])
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

[ADR 0006] settled *what* `maxmemory` means — a deterministic logical byte counter, not a heap weigh —
and deferred the implementation to W3. This is that implementation: the counter, the recency tracking,
and the eviction algorithm that enforces the bound. The design driver from the project's thesis carries
over: the steady-state command path must stay allocation-free, so the evictor cannot generate garbage.

## Decision

1. **Logical byte counter lives in the store that holds the bytes.** Each `KeyValueStore` maintains a
   running `usedMemory` (`SizeWeigher`: `key + value + ENTRY_OVERHEAD_BYTES`), updated on every
   put/overwrite/remove where the old value is already in hand — so the read is O(1) and no extra
   lookup is added. `Db.usedMemory()` exposes it and `INFO` reports it. Keeping the counter in the
   store (not `Db`) guarantees the weight *added* on insert equals the weight *subtracted* on eviction,
   because both go through the same byte-level code with the same `key` bytes — no `String`↔`byte[]`
   accounting drift.
2. **Recency is a logical clock, not wall time.** `DictEntry` gains a `long lruTime`, stamped from a
   per-`Dict` monotonically-incremented counter on every read and write. This costs one increment per
   access — no `System.currentTimeMillis()` syscall on the read path — while still ordering entries by
   recency. The approximation in "approximate LRU" comes from the *sampling*, not the clock.
3. **Eviction is Redis-style random sampling.** When `usedMemory > maxmemory`, the `Evictor` draws
   `N=5` random entries straight from the `Dict` bucket arrays (`Dict.randomEntry`), keeps the one with
   the smallest `lruTime`, and removes it; it repeats until back under budget. The sampler is **100%
   allocation-free**: the running best candidate is one `DictEntry` reference plus a `long`, randomness
   is an inlined xorshift over a `long` field, and entries are read in place — no arrays, iterators, or
   boxing.
4. **Only the string keyspace is evictable, and the bound is defined over it.** The sampler reads the
   string `Dict`; `usedMemory` weighs only that keyspace. The bound the evictor enforces is therefore
   defined over exactly what it can reclaim, so the loop always makes progress and terminates. Sorted
   sets, hashes, and lists are not weighed or evicted.
5. **Eviction runs before each command.** The shard hooks `evictor.evictIfNeeded()` ahead of dispatch
   (Redis' pre-command eviction), so a write never pushes the keyspace past budget without first
   reclaiming room. It is armed only when `maxmemory > 0` *and* the store is a `Dict`.

## Consequences

- Memory is bounded regardless of insert volume — proven by `EvictorTest` driving 50k inserts through a
  200-entry budget and asserting the resident set stays at ≤200 keys (the server cannot OOM from
  unbounded keyspace growth).
- `used_memory` in `INFO` is now a true logical figure that is 0 for an empty keyspace and grows with
  payload — replacing the JVM-heap stand-in shipped with the W2 `INFO`.
- The approximation is good but not exact LRU: a key can be evicted while a slightly-older key survives
  if the older one was never sampled. With N=5 this is close to true LRU, as in Redis.
- `ENTRY_OVERHEAD_BYTES` is an estimate, so `used_memory` tracks *logical* growth, not RSS — exactly the
  [ADR 0006] contract; the benchmarking methodology reports the logical-vs-physical relationship rather
  than equating them.

## Alternatives considered

- **Exact LRU via an intrusive recency list** (move-to-head on each access) — rejected: O(1) but it
  touches a global list on every read, adds a pointer pair per entry, and serialises the read path; the
  sampling approximation avoids all of that for a negligible accuracy cost, which is why Redis uses it.
- **Wall-clock `lruTime` cached by a periodic timer** (Redis' real `LRU_CLOCK`) — rejected for now: it
  needs a background tick thread for resolution, whereas the logical counter is syscall-free, deterministic
  (better for tests), and sufficient since recency *ordering* is all the sampler needs.
- **Track `usedMemory` in `Db` with a re-read of the old value on every `set`** — rejected: it doubles
  the hash lookup on the hot write path and reintroduces the `String`↔`byte[]` weight-consistency risk
  that keeping the counter in the store removes.

[ADR 0006]: 0006-logical-maxmemory.md
