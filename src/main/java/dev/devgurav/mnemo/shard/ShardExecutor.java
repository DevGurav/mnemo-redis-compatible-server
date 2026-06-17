package dev.devgurav.mnemo.shard;

import dev.devgurav.mnemo.aof.AofWriter;
import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.ParsedCommand;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.evict.Evictor;
import dev.devgurav.mnemo.ttl.TtlSweeper;
import org.jctools.queues.MpscArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The data-plane executor for one shard.
 *
 * <p>Owns a single dedicated thread ({@code mnemo-shard-<id>}) that runs a {@code while(true)}
 * event loop draining an {@link MpscArrayQueue} of {@link ParsedCommand}s. All command execution
 * and all mutations of {@link dev.devgurav.mnemo.store.Dict} happen on this thread — no locks,
 * no {@code synchronized}, no concurrent collections required anywhere in {@code store/}.
 *
 * <p>Netty worker threads (potentially several, one per CPU) are the producers; this thread is
 * the sole consumer. JCTools' {@link MpscArrayQueue} is chosen specifically for this topology:
 * its MPSC discipline eliminates CAS contention on the consumer side entirely, giving O(1)
 * amortised {@code poll} with no coordination cost for the shard thread.
 *
 * <p>After executing a command the shard calls {@link io.netty.channel.ChannelHandlerContext#writeAndFlush}
 * on the originating context (carried inside {@link ParsedCommand}). Netty dispatches the write
 * back to the owning EventLoop thread automatically; no ByteBufs are touched here.
 *
 * <p>Week 1: a single shard covers all keys. Week 4: a {@code ShardRouter} will instantiate N
 * of these, mapping {@code CRC16(key) % N} to the appropriate instance.
 *
 * @see dev.devgurav.mnemo.net.CommandInboundHandler
 * @see <a href="../docs/architecture-spec.md">architecture-spec.md §1</a>
 */
public final class ShardExecutor {

    private static final int QUEUE_CAPACITY = 4096;

    /**
     * Commands that mutate state and must be recorded in the AOF. Read-only commands
     * (GET, EXISTS, TTL, INFO, KEYS, …) are not in this set.
     */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "SET", "DEL",
            "INCR", "DECR", "INCRBY", "DECRBY",
            "HSET", "HDEL",
            "LPUSH", "RPUSH", "LPOP", "RPOP",
            "ZADD",
            "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST",
            "FLUSHDB", "FLUSHALL"
    );

    private final MpscArrayQueue<ParsedCommand> queue;
    private final CommandRegistry registry;
    private final Db db;
    private final Evictor evictor;     // null when maxmemory is unset or the store is not evictable
    private final TtlSweeper sweeper;  // null when no TTL-aware Db is provided
    private final AofWriter aof;       // null when AOF persistence is disabled
    private final Thread thread;

    private volatile boolean running;

    public ShardExecutor(int id, CommandRegistry registry, Db db) {
        this(id, registry, db, null, null, null);
    }

    public ShardExecutor(int id, CommandRegistry registry, Db db, Evictor evictor) {
        this(id, registry, db, evictor, null, null);
    }

    public ShardExecutor(int id, CommandRegistry registry, Db db, Evictor evictor, TtlSweeper sweeper) {
        this(id, registry, db, evictor, sweeper, null);
    }

    public ShardExecutor(int id, CommandRegistry registry, Db db, Evictor evictor,
                         TtlSweeper sweeper, AofWriter aof) {
        this.queue    = new MpscArrayQueue<>(QUEUE_CAPACITY);
        this.registry = registry;
        this.db       = db;
        this.evictor  = evictor;
        this.sweeper  = sweeper;
        this.aof      = aof;
        this.thread   = new Thread(this::loop, "mnemo-shard-" + id);
        this.thread.setDaemon(true);
    }

    /** Starts the shard's event-loop thread. Must be called exactly once before {@link #offer}. */
    public void start() {
        running = true;
        thread.start();
    }

    /**
     * Enqueues a command for execution on the shard thread. Called from Netty worker threads.
     *
     * @param cmd the parsed command to execute; must not be {@code null}
     * @return {@code true} if the command was accepted; {@code false} if the queue is full
     */
    public boolean offer(ParsedCommand cmd) {
        return queue.offer(cmd);
    }

    /**
     * Signals the shard thread to stop after draining any remaining commands. Blocks until the
     * thread has exited.
     */
    public void shutdown() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Private event loop ---

    private void loop() {
        while (running) {
            ParsedCommand cmd = queue.poll();
            if (cmd == null) {
                Thread.onSpinWait();
                continue;
            }
            dispatch(cmd);
        }
        // Drain remaining items before exiting so no command is silently dropped on shutdown.
        ParsedCommand cmd;
        while ((cmd = queue.poll()) != null) {
            dispatch(cmd);
        }
    }

    private void dispatch(ParsedCommand cmd) {
        List<byte[]> argList = Arrays.asList(cmd.args());
        RespValue reply;
        try {
            if (sweeper != null) sweeper.sweepIfDue();
            if (evictor != null) evictor.evictIfNeeded();
            reply = registry.dispatch(db, argList);
        } catch (RuntimeException e) {
            reply = RespValue.error("ERR " + e.getMessage());
        }
        // Log successful write commands to the AOF (before flushing to the client).
        if (aof != null && !(reply instanceof RespValue.ErrorReply) && !argList.isEmpty()) {
            String name = new String(argList.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            if (WRITE_COMMANDS.contains(name)) {
                aof.append(argList);
            }
        }
        // Reply: scatter (fan-out) commands contribute to the future; others write directly.
        if (cmd.isScattered()) {
            cmd.scatter().contribute(cmd.shardIndex(), reply);
        } else {
            cmd.ctx().channel().writeAndFlush(reply);
        }
    }
}
