# 3. Separate chaining + `DictEntry` object pool (not open addressing)

- Status: Accepted
- Date: 2026-06-16
- Deciders: Devendra Gurav

## Context

The from-scratch hash table (`Dict`) needs a collision strategy. Open addressing has better cache
locality, but it complicates incremental rehashing (tombstones, probe-sequence invalidation mid-move)
and doesn't map cleanly onto the dual-table migration Redis uses. The real cost people raise against a
chaining table on the JVM is **GC pressure**: a node object per entry means reference churn on every
insert/delete.

## Decision

Keep **separate chaining permanently**. Handle the GC concern not by switching to a primitive-array
open-addressing rewrite, but with a **`DictEntry` object pool**: nodes are acquired on insert and
released on remove/clear, with references nulled on free, bounded per shard.

## Consequences

- Incremental rehashing stays clean — nodes are spliced between tables by pointer, with no tombstones
  and no probe-sequence repair (see [ADR 0004](0004-incremental-rehashing.md)).
- Allocation churn is bounded and measurable; the W3 plan compares allocation profiles with and
  without the pool via async-profiler.
- The pool's effectiveness depends on a balanced acquire/release lifecycle; the rehash engine is
  deliberately written to re-link existing nodes **without** touching the pool, so a resize doesn't
  perturb pool occupancy.

## Alternatives considered

- **Open addressing (linear/Robin Hood)** — rejected: better locality, but the tombstone +
  probe-invalidation interaction with incremental rehashing isn't worth the complexity here.
- **Plain chaining, no pool** — rejected: leaves the GC-churn critique unanswered, which is precisely
  the tail-latency story the project wants to defend.
