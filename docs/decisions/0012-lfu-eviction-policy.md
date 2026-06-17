# 12. LFU eviction policy via Morris counter on DictEntry

- Status: Accepted (extends [ADR 0010])
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

[ADR 0010] implemented approximate-LRU eviction. Redis also ships `allkeys-lfu` as an alternative
policy that evicts the *least-frequently-used* key rather than the least-recently-used one. LFU
better protects scan-resistant hot keys (a key accessed 1 000 times should survive one cold scan
over many keys that are each accessed once). The challenge is encoding frequency cheaply: a 64-bit
counter per entry would work but costs space and does not degrade stale keys whose access rate has
since dropped.

## Decision

1. **Morris (probabilistic) counter in 8 bits.** `DictEntry` gains a `public int lfu` field (using
   `int` to avoid signedness issues; only 0–255 are stored). New entries start at `LFU_INIT = 5`
   (matching Redis `LFU_INIT_VAL`) so freshly-inserted keys are not immediately evicted.

2. **Log-scale increment on every access.** `Dict.incrementLfu(current)` returns `current + 1`
   with probability `1 / (current × 10 + 1)`, or `current` otherwise. At low counts the counter
   increments quickly (near-certainty at count 0); at high counts it saturates slowly toward 255.
   This approximates log₁₀ of the true access count with a single `Math.random()` call — no
   separate counter, no decay thread.

3. **Called on every Dict read and write.** `getBytes()` and `overwrite()` both call
   `incrementLfu`, so frequency tracks both GET-style reads and SET-style overwrites. New inserts
   in `putBytes()` set `lfu = DictEntry.LFU_INIT` directly (no probabilistic increment for the
   first access).

4. **Policy is a Config field.** `EvictionPolicy` enum (`NOEVICTION`, `ALLKEYS_LRU`,
   `ALLKEYS_LFU`) is parsed from `--eviction-policy` / `MNEMO_EVICTION_POLICY` at startup.
   `Evictor.sampleVictim()` reads `entry.lruTime` for LRU or `entry.lfu` for LFU, so the same
   random-sampling loop services both policies without branching overhead on the common case.

5. **InfoCommand reports the live policy.** `INFO memory` emits `maxmemory_policy` from the
   configured `EvictionPolicy.toConfigString()` (e.g. `allkeys-lfu`) rather than a hardcoded
   string, so operators can verify the running configuration.

## Consequences

- Hot keys are protected from eviction even after long idle periods (frequency beats recency for
  access-skewed workloads).
- The counter never truly decays: a key that was hot in the past but is now cold will eventually
  be evicted once enough cold-then-heated keys accumulate higher counts. A production implementation
  could add a decay pass (halve lfu periodically), but this is out of scope for the project.
- `Math.random()` on the increment path is deterministic in benchmarks but is a static JVM call;
  if zero-allocation on the hot path becomes a measured concern, replace with the same xorshift64
  used by the `Evictor` RNG.

## Alternatives considered

- **Exact frequency counter (long per entry)** — rejected: does not decay, costs 8 bytes per entry
  on top of `lruTime`, and the integer precision is wasted since only rank matters for eviction.
- **Separate frequency table (Map<String, Long>)** outside `DictEntry` — rejected: O(1) hash
  lookup on every read for a counter that lives naturally next to the entry; keeping it in the node
  avoids a second hash probe.
- **Count–Min Sketch** — rejected: overkill for single-shard scope; Morris counter gives the
  needed approximation at zero per-entry overhead beyond one `int`.

[ADR 0010]: 0010-random-sampling-lru-eviction.md
