# 6. `maxmemory` as logical capacity, not physical heap weighing

- Status: Accepted (implementation scheduled W3)
- Date: 2026-06-16
- Deciders: Devendra Gurav

## Context

Eviction (LRU/LFU) needs a notion of "full." The intuitive definition is physical heap bytes, but on
the JVM the live size of an object graph is non-deterministic (header padding, alignment, compressed
oops, per-entry overhead) and measuring it on the hot path means either walking object graphs or
reconciling against `Runtime` — both cache-miss-heavy and slow, on the exact path that must stay fast.

## Decision

Define `maxmemory` as a **deterministic logical capacity**: an O(1) running counter of payload bytes
(and/or key count) maintained on each insert/delete. Eviction triggers off that counter. Real heap
behaviour is observed **out of band** via JFR, not on the request path.

## Consequences

- The eviction trigger is O(1) and deterministic — no object-graph weighing in the hot path.
- The number is *logical*, so it deliberately doesn't equal RSS; the benchmarking methodology accounts
  for this and reports the relationship rather than pretending they're identical.
- This decision is locked but **not yet implemented** — it lands with LRU/LFU in W3
  ([roadmap](../roadmap.md)). Recorded now so the eviction work starts from a settled definition.

## Alternatives considered

- **Physical heap accounting (`Runtime`/instrumentation agent)** — rejected: non-deterministic and too
  slow for the hot path; fine as an out-of-band observation, not as the trigger.
