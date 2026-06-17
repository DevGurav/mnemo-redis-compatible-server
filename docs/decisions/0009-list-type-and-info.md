# 9. List type as a fourth namespace with a node pool, and `INFO` observability

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

Two things close out Week 2: the list value type (the fourth and final W2 type, after strings,
sorted sets, and hashes) and the `INFO` introspection endpoint promised in
[observability.md](../observability.md) and [api-protocol.md](../api-protocol.md) §5.

[ADR 0008] predicted that at the fourth type the repeated cross-type `WRONGTYPE` guard "stops
scaling" and named the W3–W4 keyspace/sharding rework as where the namespaces should fold into a
single typed keyspace `Dict`. This ADR records the decision to ship the list type under the existing
parallel-namespace model anyway and hold that unification, as approved.

## Decision

1. **A list is a hand-built `IntrusiveList`.** A doubly-linked list with head/tail pointers gives O(1)
   `LPUSH`/`RPUSH`/`LPOP`/`RPOP`; `LRANGE` walks from the nearer end. It lives in a fourth `Db`
   namespace (`lists`) beside `zsets` and `hashes`.
2. **Nodes are pooled, exactly like `DictEntry`.** Each `IntrusiveList` owns a `ListNodePool` — the
   structural twin of `DictEntryPool`: an intrusive free list linked through each node's `next`, with
   `release` nulling `value`/`prev`/`next` *before* the capacity check. Push pulls a shell from the
   pool; pop returns it. This keeps a steady-depth queue allocation-free in the hot path (the
   zero-allocation thesis, [ADR 0003]).
3. **Keep the parallel-namespace model; defer unification to the Week-4 sharding rework.** The
   one-type-per-key invariant stays enforced at the command layer — every string/zset/hash command
   now also checks `isList`, and the list commands check the other three. This is the scaling cost
   [ADR 0008] foresaw, accepted once more so the keyspace `Dict` is designed deliberately alongside
   sharding rather than bolted on under this feature. This revises [ADR 0008]'s "unify in W3–W4" to
   "unify with the W4 sharding rework".
4. **`INFO` reports only metrics backed by real state today.** Server (version/JDK/GC/uptime),
   Clients (a live connected-channel counter kept by a `@Sharable` pipeline handler), Memory, and
   Keyspace (per-type key counts). For Memory, `maxmemory:0` advertises "no logical limit configured"
   and `used_memory` reports the real JVM heap figure. The *logical payload-byte* counter
   (`used_payload_bytes`) is deliberately **not** emitted yet: it is the W3 eviction-counter work
   ([ADR 0006]) and faking it now would misrepresent the eviction story.

## Consequences

- Lists ship reusing the proven pool pattern; the only new structure is the linked list itself,
  unit-tested for ordering, Redis `LRANGE` index semantics, empty-key deletion, the cross-type guards,
  and — directly on `IntrusiveList` — that popped nodes return to the pool.
- The cross-type guard is now four-way in every command. This is the last type that will pay that
  cost: the W4 keyspace `Dict` with a centralized `Db.typeOf(key)` collapses all of it.
- `INFO` is wired to a single `ServerStats` shared between the registry (so `InfoCommand` reads it)
  and the Netty pipeline (so the connection counter writes it). The counter is atomic because connect
  /disconnect happen on worker threads while `INFO` reads on the shard thread.

## Alternatives considered

- **Unify the keyspace into a typed-value `Dict` now** — rejected again for the same reason as
  [ADR 0008]: it is a `Dict` value-type refactor plus every command's type handling, not needed to
  ship lists, and better designed with sharding.
- **A growable array (`ArrayDeque`/ring buffer) for the list** — rejected: head pops would be O(n) or
  need a ring buffer whose growth reallocates, and it would not exercise the intrusive-pool pattern
  the project is built to demonstrate.
- **Emit `used_payload_bytes` from a keyspace walk on each `INFO`** — rejected: it would either fake
  the W3 logical counter or add byte-accounting iteration to every store implementation now; `INFO` is
  not hot-path, but the number would still pre-empt a decision ([ADR 0006]) that isn't built yet.

[ADR 0003]: 0003-separate-chaining-with-entry-pool.md
[ADR 0006]: 0006-logical-maxmemory.md
[ADR 0008]: 0008-hash-type.md
