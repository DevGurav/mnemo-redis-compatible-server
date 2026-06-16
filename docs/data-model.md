# Data model

Mnemo is an in-memory keyspace: a single map from key to a typed value. There is no on-disk schema yet
(AOF persistence is W3); this document describes the in-memory shape.

## Keys

UTF-8 strings. Inside `Dict` a key is stored as its raw `byte[]` alongside the cached `int` hash, and
compared by `Arrays.equals` — so lookups never re-decode the string.

## Value types

| Type | Backing structure | Status |
|------|-------------------|--------|
| **String** | raw `byte[]` value in `Dict` | live (`SET`/`GET`) |
| **Sorted set (ZSet)** | `Dict` (member → 8-byte score) + span-augmented `SkipList` | structure built + tested; commands not yet wired |
| Hash | — | planned (W2) |
| List | — | planned (W2) |

## Sorted-set encoding

A `ZSet` keeps two views of one logical dataset:

- **`scoreIndex` (`Dict`)** — `member → score`, where the score is stored as **8 big-endian bytes**
  (`Double.doubleToRawLongBits`) so the `Dict`'s existing `byte[]` value type is reused with no wrapper
  object. The raw-bits encoding round-trips negative zero and NaN payloads intact.
- **`rankIndex` (`SkipList`)** — `(score, member)` pairs in ascending order; ties broken by unsigned
  lexicographic order on member bytes (Redis semantics).

Every write mutates both views together (single-threaded, so atomic by construction). Reads route to
whichever view is cheaper: O(1) membership/score via the `Dict`, O(log n) rank/range via the skip list.

## Memory accounting

Capacity for eviction is tracked **logically** (payload-byte counter), not by weighing the object
graph — see [ADR 0006](decisions/0006-logical-maxmemory.md). Eviction itself lands in W3.
