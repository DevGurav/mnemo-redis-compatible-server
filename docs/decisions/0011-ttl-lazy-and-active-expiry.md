# 11. TTL expiry: lazy on read + active sweep on the shard thread

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

Redis supports per-key time-to-live via EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT and removal via PERSIST,
with TTL/PTTL for querying remaining time. Two expiry mechanisms cooperate: lazy expiry (check and
delete on every read) and active expiry (periodic background sweep). Mnemo must implement both while
keeping the keyspace mutations single-threaded (ADR 0002) — no timer thread may touch Db directly.

## Decision

1. **Single expiry map in Db.** A `Map<String, Long> expiries` (key → absolute epoch ms) covers all
   four namespaces (strings, zsets, hashes, lists). One map is simpler than per-namespace maps and
   lets TTL-related commands work uniformly across types without knowing which namespace holds the key.

2. **Lazy expiry before every read.** A private `expireIfNeeded(key)` is called at the top of every
   read accessor (`get`, `hash`, `list`, `zset`, `exists`, `isString`, …). If the current time is ≥
   the stored expiry, the key is removed from all four namespaces and the expiry map, and the method
   returns `true` — callers treat it as a miss. This guarantees expired keys are never returned.

3. **Active sweep driven from the shard thread, not a timer.** `TtlSweeper.sweepIfDue()` is called
   by `ShardExecutor.dispatch()` before each command, at most 10 times per second (100 ms interval),
   sampling up to 20 keys per sweep from the expiry map. This matches Redis' active-expiry budget.
   Crucially, the sweep runs on the same thread that owns `Db` — no locks, no cross-thread access,
   no `volatile`. A timer thread would need synchronisation; co-scheduling with the command thread
   avoids it entirely.

4. **SET clears TTL.** `Db.set()` calls `expiries.remove(key)` before inserting, matching Redis
   semantics where a plain SET resets any previously set expiry.

5. **delete() and flush() are TTL-aware.** Explicit delete removes from the expiry map to prevent
   ghost entries; flush clears it entirely.

6. **KEYS scan avoids ConcurrentModificationException.** `Db.keys()` checks the expiry map inline
   (read-only, no side effects) rather than calling `expireIfNeeded`, so the expiry map is not
   mutated during the `forEach` on the keyspace.

7. **Seven commands wired.** EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT, TTL, PTTL, PERSIST — all
   registered in `CommandRegistry` and tested in `TtlCommandsTest` (20 cases covering lazy expiry,
   cross-type TTL, arity errors, negative-seconds error, SET-clears-TTL).

## Consequences

- Expired keys are never observable: lazy expiry on each read is the primary guarantee; active sweep
  reclaims memory for keys that are never re-read (write-only, or abandoned).
- No new threads are added; the design stays true to the single-thread-per-shard model of ADR 0002.
- Memory of expired but unread keys is bounded by the active sweep cadence (up to 10 s in the worst
  case with 20-key samples). Sufficient for the project scope; a production system would tune the
  sample size and rate.

## Alternatives considered

- **Dedicated timer thread for active expiry** — rejected: requires synchronisation with the command
  thread, adds complexity, and contradicts ADR 0002's single-thread invariant.
- **Per-namespace expiry maps** — rejected: four separate maps with identical logic; one map and a
  check-all-namespaces delete is cleaner with identical semantics.
- **Sorted expiry index (TreeMap<Long, Set<String>>)** for O(1) active sweep — rejected: sweep
  samples randomly like Redis (not in deadline order); a sorted index adds bookkeeping cost on every
  EXPIRE call for a benefit only relevant at very high key volumes.
