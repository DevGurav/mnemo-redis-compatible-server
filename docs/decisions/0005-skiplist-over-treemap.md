# 5. Span-augmented skip list for the sorted set (not `TreeMap`)

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

The sorted set (`ZSet`, backing `ZADD`/`ZRANK`/`ZRANGE`) needs ordered storage with **rank** queries:
"what is the 1-based position of this member?" and "give me members at ranks [a, b]". A balanced BST
like `java.util.TreeMap` gives ordered iteration and O(log n) lookup, but **not** O(log n) rank — a
plain `TreeMap` has to walk to count positions, making `ZRANK` O(n). Redis uses a skip list precisely
to get O(log n) rank, and matching its data structure also matches its tie-break and range semantics.

## Decision

Implement a probabilistic **skip list with span accounting** as the rank-ordered spine of `ZSet`:

- Ordered by `score` ascending, ties broken by **unsigned lexicographic** order on member bytes
  (Redis semantics).
- Each forward pointer carries a `span` = number of level-0 edges it crosses. Summing spans along the
  search path yields exact rank in O(log n); the same spans drive `getByRank`/`range`.
- `MAX_LEVEL = 32`, promotion probability `P = 0.25` (Redis defaults).
- `ZSet` pairs the skip list with a `Dict` (member → 8-byte big-endian score) so membership and score
  lookup stay O(1) while rank/range stay O(log n). Every write mutates both structures together.

Implemented in [`SkipList.java`](../../src/main/java/dev/devgurav/mnemo/store/SkipList.java) and
[`ZSet.java`](../../src/main/java/dev/devgurav/mnemo/store/ZSet.java).

## Consequences

- `ZRANK`/`ZRANGE` are genuinely O(log n), not O(n) — the property that makes a real sorted set useful.
- Span maintenance on insert/delete is the error-prone part; it's covered by 35 `ZSetTest` cases
  including randomized cross-checks of rank/range against a brute-force reference.
- The skip list is intentionally **not** thread-safe — it lives behind the single command thread
  ([ADR 0002](0002-single-threaded-command-execution.md)), so no synchronization is needed or wanted.
- The structure is built and unit-tested; wiring the `Z*` commands through the RESP registry is the
  remaining step before it's reachable over the wire.

## Alternatives considered

- **`java.util.TreeMap`** — rejected: O(n) rank, and it would mean *not* building the structure the
  project exists to build by hand.
- **Order-statistics balanced BST (augmented red-black tree)** — rejected: gives O(log n) rank too,
  but a hand-written augmented RB-tree is more fiddly than a skip list and diverges from Redis, which
  costs us differential-testing parity.
