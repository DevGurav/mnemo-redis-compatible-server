# Architecture Decision Records

Each significant, hard-to-reverse decision in Mnemo gets one numbered record here. The point is to
capture **why** a path was taken and **what was rejected**, so the reasoning survives long after the
diff that implemented it — and so neither a future contributor nor an automated tool re-litigates a
settled question.

## Conventions

- One file per decision: `NNNN-short-kebab-title.md`, numbered in the order decided.
- Records are **immutable once `Accepted`**. You don't edit an accepted ADR to change its decision —
  you write a new ADR that supersedes it, and mark the old one `Superseded by NNNN`.
- Every ADR carries: **Status · Date · Context · Decision · Consequences · Alternatives considered**.
- Numbering is not commit order — a decision made early can be written up later (the W1 records here
  were backfilled alongside the W2 work).

## Index

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-hand-rolled-resp2-codec.md) | Hand-roll the RESP2 codec on Netty instead of a library | Accepted |
| [0002](0002-single-threaded-command-execution.md) | Multi-threaded I/O, single-threaded command execution | Accepted |
| [0003](0003-separate-chaining-with-entry-pool.md) | Separate chaining + `DictEntry` object pool (not open addressing) | Accepted |
| [0004](0004-incremental-rehashing.md) | Dual-table incremental rehashing | Accepted |
| [0005](0005-skiplist-over-treemap.md) | Span-augmented skip list for the sorted set (not `TreeMap`) | Accepted |
| [0006](0006-logical-maxmemory.md) | `maxmemory` as logical capacity, not physical heap weighing | Accepted (impl scheduled W3) |
| [0007](0007-typed-keyspace.md) | Separate sorted-set namespace, WRONGTYPE-guarded (unified typed keyspace deferred) | Accepted |
| [0008](0008-hash-type.md) | Hash type as a third namespace reusing `Dict`; keyspace unification deferred to W3–W4 | Accepted |
