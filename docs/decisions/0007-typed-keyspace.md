# 7. Typed keyspace: a separate sorted-set namespace, WRONGTYPE-guarded

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

Through Week 1 the keyspace held one value type — strings — so `Db` was a thin wrapper over a
`KeyValueStore` of `key → byte[]`. Wiring `ZADD`/`ZRANK`/`ZRANGE` means a key can now hold a sorted
set instead, and Redis's contract is that **a key holds exactly one type**: a type-mismatched command
returns `WRONGTYPE`, `SET` overwrites whatever was there, and `DEL`/`EXISTS` work regardless of type.

The question is how to model multiple value types. The principled end state is a single keyspace
dictionary whose values are a typed union (string | sorted set | hash | list | …) — i.e. generalize
`Dict` from `byte[]` values to `Object`/typed values. That's a real refactor of the hand-built hash
table and its entry pool, and it isn't what this step is about.

## Decision

Keep the existing string keyspace as-is and add a **second namespace** in `Db` for sorted sets
(`key → ZSet`). Enforce the one-type-per-key invariant at the command layer:

- `Db.isString(key)` / `Db.isZSet(key)` let a handler detect a mismatch and return `WRONGTYPE`
  (sorted-set commands guard against string keys; `GET` guards against sorted-set keys).
- `Db.set` removes any sorted set under the key before storing the string (Redis overwrite semantics).
- `Db.delete` / `Db.exists` / `Db.size` / `Db.flush` span both namespaces.

The sorted-set namespace is a plain `HashMap<String, ZSet>` for now. The *interesting* hand-built
structures (the string `Dict`, and the `SkipList`/`Dict` inside each `ZSet`) are unchanged; only the
outer key→value lookup for this one type is a library map.

## Consequences

- The Z-commands ship now without blocking on a keyspace refactor, and cross-type behaviour
  (`WRONGTYPE`, `SET` overwrite, `DEL`/`EXISTS`) is correct and tested.
- The invariant lives in the command layer rather than the type system, so a future value type must
  remember to add its own guards. That's the cost of not unifying yet.
- **Deferred:** fold both namespaces into a single typed keyspace dictionary (one `Dict` whose values
  carry a type tag) when the third value type lands or when the keyspace/sharding work in W3–W4 forces
  it. *Trigger: adding Hashes or Lists — at three types the parallel-namespace approach stops paying.*

## Alternatives considered

- **Generalize `Dict` to typed `Object` values now** — rejected for this step: it's a refactor of the
  hash table and entry pool that isn't needed to make the sorted set reachable, and is better done
  once there are several types to justify the type-tag design.
- **One map keyed by `(type, key)` or type-prefixed keys** — rejected: leaks the type model into key
  encoding and complicates `DEL`/`EXISTS`/scan semantics.
