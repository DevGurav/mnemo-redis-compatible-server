# ADR 0014 — Multi-shard CRC-16 key routing

**Status:** Accepted  
**Week:** 4 (concurrency)

---

## Context

The Week-1 design uses a single `ShardExecutor` thread. All command execution serialises through one
MPSC queue. This is correct and simple, but means write throughput is bounded by one core. Moving
to N shards — each with its own thread, MPSC queue, and isolated `Db` — should scale write
throughput linearly with shard count up to the point where I/O or memory bandwidth saturates.

---

## Decisions

### 1. Hash function: CRC-16/XMODEM (polynomial 0x1021, init 0x0000)

*Same algorithm Redis uses for hash-slot assignment.*

**Rationale:** The Redis Cluster specification chose CRC-16/XMODEM as a well-studied, collision-
resistant 16-bit hash. Choosing the same function lets operators predict key affinity, simplifies
reasoning for anyone familiar with Redis Cluster, and makes our hash-slot assignments directly
comparable to Redis's 16 384-slot layout. The 256-entry lookup table (`Crc16.java`) reduces each
byte to one XOR and one table read — O(key-length), no division, no branches.

**Implementation note:** Java `int` does not overflow at 16 bits the way C `uint16_t` does. Every
shift-left in the table-building loop must be masked with `& 0xFFFF` before the next MSB check;
omitting the mask corrupts bits above position 15 and produces wrong table entries (caught by the
single-byte sanity check `CRC-16([0x01]) == 0x1021`).

### 2. Routing formula: `shard = CRC16(key) % shardCount`

For single-key commands the effective routing key is `args[1]`. For empty-arity commands (PING,
INFO, COMMAND, ECHO) shard 0 is used.

### 3. Hashtag co-location: `{tag}` extraction matching Redis Cluster semantics

If the key contains `{…}` with a non-empty body, only the bytes between the braces are hashed.
`{user:42}:profile` and `{user:42}:settings` therefore land on the same shard, enabling atomic
multi-key operations without cross-shard coordination.

### 4. Broadcast commands: scatter-gather via `ScatterFuture`

Commands that must span all shards (`DBSIZE`, `KEYS`, `FLUSHDB`, `FLUSHALL`) are fanned out to
every `ShardExecutor` with a shared `ScatterFuture`. Each shard contributes its partial result; the
last to arrive merges all partials and writes one aggregated reply to the client channel.

| Command | Merge strategy |
|---------|----------------|
| `DBSIZE` | Sum of all shards' integer reply values |
| `KEYS`   | Concatenation of all shards' array replies |
| `FLUSHDB` / `FLUSHALL` | First error wins; otherwise `+OK` |

`ScatterFuture` uses an `AtomicInteger` countdown for the happens-before guarantee: only the shard
that decrements the counter to zero reads all partials (no explicit locks required).

### 5. Multi-key command limitation

Commands that accept multiple keys in one call (e.g. `DEL k1 k2`) are routed by the **first key
only**. Keys on other shards are unaffected. Callers must use `{hashtag}` co-location if they
require all keys to be on the same shard. This matches Redis Cluster's restriction and is
documented but not enforced at the protocol level.

### 6. AOF persistence is single-shard only

Appending writes from N shards to a single AOF file requires cross-thread coordination and
makes replay ordering ambiguous. AOF is silently disabled when `shardCount > 1`. A per-shard AOF
(suffix `_shard{N}`) with ordered replay would be the correct multi-shard approach but is out of
scope for this project.

---

## Rejected alternatives

**Lock-striping over shared Dict**: At nanosecond-scale op durations the lock acquisition cost
exceeds the work cost (see architecture-spec.md §1). Shared-nothing wins.

**Consistent hashing (ring)**: Adds rebalancing complexity with no benefit for a fixed shard
count. `% N` is trivially understood and O(1).
