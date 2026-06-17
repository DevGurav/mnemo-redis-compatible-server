# 8. Hash type as a third keyspace namespace, reusing `Dict`

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

Hashes are the third keyspace value type, after strings and sorted sets. A Redis hash is a map from
field to value — which is exactly what the hand-built `Dict` already is. [ADR 0007] modelled the first
two types as parallel namespaces in `Db` and explicitly named "adding Hashes or Lists" as the trigger
to reconsider that and fold everything into a single typed-value keyspace dictionary. So this decision
has to answer both *how to store a hash* and *whether the third type forces the unification*.

## Decision

1. **A hash is a `Dict`.** Each hash key maps to its own `Dict` instance (field → value), reusing the
   incremental-rehashing table wholesale — no new structure. `HGETALL` reads it through a new
   `Dict.forEach(BiConsumer<byte[],byte[]>)` that is correct mid-rehash (it walks `ht[0]` and, while
   rehashing, `ht[1]`; already-migrated buckets are drained to null, so every entry is yielded once).
2. **Keep the parallel-namespace model for now** — add a `hashes` map to `Db` alongside `zsets`,
   rather than performing the [ADR 0007] unification. The one-type-per-key invariant stays enforced at
   the command layer (`isString`/`isZSet`/`isHash` → `WRONGTYPE`; `SET` overwrites any type;
   `DEL`/`EXISTS` span all three).

## Consequences

- Hashes ship reusing proven code; the only genuinely new logic is the `Dict` iterator, which is unit-
  tested directly against an active rehash (entries split across both tables).
- **The cost [ADR 0007] predicted is now real:** the cross-type `WRONGTYPE` guard is repeated in each
  string and sorted-set command (each now also checks `isHash`). At a fourth type this stops scaling.
- **Unification is deferred once more, deliberately**, to the W3–W4 keyspace/sharding work, where a
  single keyspace `Dict` holding type-tagged values is the natural design — and where a centralized
  `Db.typeOf(key)` would collapse the scattered guards. Recorded here so the next type starts from a
  settled position. This revises [ADR 0007]'s "unify at the third type" trigger to "unify with the
  keyspace rework"; [ADR 0007] otherwise stands.

## Alternatives considered

- **Unify the keyspace into a typed-value `Dict` now** — rejected for this step: it's a refactor of
  `Dict`'s value type plus every existing command's type handling, not needed to ship Hashes, and
  better designed deliberately alongside eviction/sharding than bolted on under a feature.
- **Back the hash with `java.util.LinkedHashMap`** — rejected: it would mean *not* reusing the
  structure the project exists to build, and `Dict` already does the job (and exercises the iterator).

[ADR 0007]: 0007-typed-keyspace.md
