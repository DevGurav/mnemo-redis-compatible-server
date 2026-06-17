# 13. Append-only file (AOF) persistence and crash-recovery replay

- Status: Accepted
- Date: 2026-06-17
- Deciders: Devendra Gurav

## Context

An in-memory store that loses all data on restart is unsuitable for any durability claim. Redis
provides two persistence mechanisms: RDB (periodic point-in-time snapshots) and AOF (append-only
log of every write command). AOF offers better durability guarantees and simpler crash recovery —
a clean replay of the log reconstructs exact state — at the cost of larger files and a per-write
`fsync` overhead. For this project's scope, AOF is the right choice: no background thread is
needed, the replay path reuses the existing command registry, and a periodic `force()` call is
sufficient for crash safety.

## Decision

1. **Write commands are appended as RESP multibulk frames.** `AofWriter` opens the file with
   `StandardOpenOption.APPEND` and writes each logged command as `*N\r\n($len\r\ndata\r\n) × N`,
   matching the RESP wire format exactly. Using the same encoding as the network protocol means the
   replayer can reuse `CommandRegistry.dispatch()` without a separate parser.

2. **A static whitelist of write commands lives in `ShardExecutor`.** After each successful
   dispatch (reply is not an `ErrorReply`), if the command name is in `WRITE_COMMANDS` (SET, DEL,
   INCR/DECR/INCRBY/DECRBY, HSET, HDEL, LPUSH/RPUSH/LPOP/RPOP, ZADD, EXPIRE/PEXPIRE/EXPIREAT/
   PEXPIREAT/PERSIST, FLUSHDB/FLUSHALL), `AofWriter.append()` is called with the raw `argList`.
   Logging read-only commands would inflate the AOF with no replay value; logging errors would
   replay commands that fail in the same way and produce noise.

3. **`channel.force(false)` after every append.** Flushes data to OS buffers without syncing file
   metadata — durable enough that a JVM crash cannot lose the last write (the OS holds it), while
   avoiding the higher cost of `fdatasync`/`fsync` that syncs inode timestamps too. Acceptable for
   a single-node store; a production deployment would tune this with a `fsync-every-N-ms` option.

4. **Replay is synchronous during startup, before the server socket opens.** `AofReplayer.replay()`
   reads the file with `BufferedInputStream`, parses frames with a minimal inline RESP reader, and
   calls `registry.dispatch(db, args)` for each. Errors on individual commands are swallowed so one
   corrupt entry does not abort recovery. The socket is bound only after replay completes, so no
   client ever observes partial state.

5. **AOF path is a `Config` field.** `null` disables AOF entirely (the default). Enabled via
   `--aof-path` / `MNEMO_AOF_PATH`. `MnemoServer.close()` closes the `AofWriter` so in-flight
   buffers are flushed on clean shutdown.

6. **EXPIRE/PEXPIRE timestamps drift on replay.** Relative-TTL commands (EXPIRE seconds,
   PEXPIRE ms) are logged as-is and replayed relative to the restart time — keys get a fresh TTL
   window rather than the remaining one from before shutdown. EXPIREAT/PEXPIREAT (absolute
   timestamps) replay exactly. For the project scope this is acceptable; a production AOF rewriter
   would convert EXPIRE → EXPIREAT at write time as Redis does.

## Consequences

- Data survives server restarts and `kill -9` crashes (subject to OS buffer flush, which
  `force(false)` guarantees for JVM-level crashes).
- No new threads, no background snapshot goroutine, no serialisation format to maintain separately
  from the protocol — the RESP codec is the AOF codec.
- AOF files grow monotonically; no compaction / rewrite step is implemented. For project demo use,
  file size is not a concern.

## Alternatives considered

- **RDB (binary snapshot)** — rejected: needs a serialisation format, a background fork/thread,
  and a restore parser; AOF reuses the existing protocol codec and command registry with far less
  code.
- **`FileChannel.force(true)` (full fsync with metadata)** — rejected: higher per-write cost for
  no extra durability benefit in the single-node case (inode timestamps do not affect recovery).
- **Write to a separate writer thread** — rejected: the AOF writer only runs on successful write
  commands; it is fast enough on the command path given the single-shard model of ADR 0002, and
  moving to another thread would require cross-thread coordination that contradicts that model.
