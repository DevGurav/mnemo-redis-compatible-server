# Glossary

Domain terms used across Mnemo's code and docs, so each means exactly one thing.

- **RESP / RESP2** — REdis Serialization Protocol, the wire format Redis clients speak. Mnemo
  implements it by hand so real clients connect unmodified. See [api-protocol.md](api-protocol.md).
- **Multibulk** — the RESP array framing real clients (`redis-cli`, `Jedis`) use to send a command.
- **Inline command** — the bare-text command form a human can type into a raw socket; Mnemo's decoder
  accepts both this and multibulk.
- **Command thread** — the single thread that owns the keyspace and executes every command serially,
  so the data plane needs no locks ([ADR 0002](decisions/0002-single-threaded-command-execution.md)).
- **EventLoop** — Netty's I/O thread. `ByteBuf` allocation/release stays here; only plain POJOs cross
  to the command thread.
- **`Dict`** — the from-scratch separate-chaining hash table that backs the keyspace.
- **Separate chaining** — collision strategy where each bucket holds a linked list of entries (vs.
  open addressing) ([ADR 0003](decisions/0003-separate-chaining-with-entry-pool.md)).
- **Load factor** — `size / capacity`; crossing 0.75 arms a resize.
- **Incremental rehashing** — growing the table by draining buckets from an old table (`ht[0]`) into a
  double-sized new one (`ht[1]`) a few at a time across normal traffic, instead of one stop-the-world
  pass ([ADR 0004](decisions/0004-incremental-rehashing.md)).
- **`rehashidx`** — cursor for the next `ht[0]` bucket to migrate; `-1` means no rehash in progress.
- **`DictEntry` pool** — bounded object pool that recycles hash-table nodes to cap GC churn.
- **Skip list** — probabilistic ordered structure giving O(log n) search/rank; the spine of `ZSet`
  ([ADR 0005](decisions/0005-skiplist-over-treemap.md)).
- **Span** — on a skip-list forward pointer, the number of level-0 edges it crosses; summing spans
  along a search path yields a node's exact rank in O(log n).
- **`ZSet` (sorted set)** — member→score map (via `Dict`) paired with a rank-ordered skip list;
  backs `ZADD`/`ZRANK`/`ZRANGE`.
- **Rank** — 1-based position of a member in score order (rank 1 = lowest score, ties by member bytes).
- **`maxmemory` (logical)** — eviction's notion of "full": an O(1) payload-byte counter, deliberately
  not physical heap bytes ([ADR 0006](decisions/0006-logical-maxmemory.md)).
- **Shard / shared-nothing** — the W4 scaling plan: N command threads, each owning a disjoint slice of
  the keyspace, fed work over an MPSC queue — no shared locks.
- **Spec test vs. plumbing test** — structure specs (red until implemented) vs. scaffold tests
  (always green); see [testing.md](testing.md).
