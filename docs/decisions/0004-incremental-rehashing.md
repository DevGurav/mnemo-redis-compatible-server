# 4. Dual-table incremental rehashing

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

A separate-chaining table has to grow as it fills. The naive approach rehashes the entire table in one
shot when the load factor is exceeded. On a large keyspace that is a multi-millisecond stop-the-world
stall on the single command thread ([ADR 0002](0002-single-threaded-command-execution.md)) — and on
this design, a stall on the command thread *is* a tail-latency spike for every client. Flat p99 is a
headline goal of the project, so a full-table rehash is unacceptable.

## Decision

Rehash **incrementally across two live tables**, mirroring Redis:

- `ht[0]` is the primary table; `ht[1]` is allocated at double capacity when `size > 0.75 × ht[0].len`.
- `rehashidx` is the next `ht[0]` bucket to migrate (`-1` = idle).
- Every mutating op (`put`/`remove`) piggy-backs one `rehashStep`, draining buckets a few at a time so
  the migration amortizes across normal traffic instead of one pause.
- **Reads** probe `ht[0]` then `ht[1]` on a miss, and do **not** advance the cursor — a lookup costs at
  most one extra bounded bucket probe, never a migration step.
- New keys during a rehash are inserted into `ht[1]` so they survive promotion; overwrites search both
  tables.
- Migration **re-links existing nodes by pointer** — no `pool.acquire`/`release` during a move — so a
  resize leaves pool occupancy and allocation pressure unchanged
  ([ADR 0003](0003-separate-chaining-with-entry-pool.md)).
- An empty-bucket cap (`steps × 10`) bounds how many consecutive null buckets a single step scans, so a
  sparse table can't turn one `rehashStep` into a long walk.

Implemented in [`Dict.java`](../../src/main/java/dev/devgurav/mnemo/store/Dict.java).

## Consequences

- No single operation pays for the whole resize; the per-op cost of migration is O(1) amortized.
- Correctness during the transient two-table window is the subtle part — covered by `DictTest` (24
  cases incl. mid-rehash get/put/remove/overwrite) and the jqwik `DictPropertyTest`.
- The headline measurement (rehash p99 before/after) is what the JMH `DictBenchmark` exists to produce.
  Caveat: the current benchmark doesn't yet force the table *through* a rehash under load — closing
  that gap is tracked for the W2/W3 benchmarking pass.

## Alternatives considered

- **Full-table rehash on resize** — rejected: the multi-ms stall it causes is the exact tail-latency
  failure this project is built to avoid.
- **Never resize (fixed table + longer chains)** — rejected: degrades lookups to O(n) under growth.
