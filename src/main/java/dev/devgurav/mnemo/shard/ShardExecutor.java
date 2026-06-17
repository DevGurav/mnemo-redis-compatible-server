package dev.devgurav.mnemo.shard;

import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.ParsedCommand;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.evict.Evictor;
import org.jctools.queues.MpscArrayQueue;

import java.util.Arrays;
import java.util.List;

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

    private final MpscArrayQueue<ParsedCommand> queue;
    private final CommandRegistry registry;
    private final Db db;
    private final Evictor evictor; // null when maxmemory is unset or the store is not evictable
    private final Thread thread;

    private volatile boolean running;

    public ShardExecutor(int id, CommandRegistry registry, Db db) {
        this(id, registry, db, null);
    }

    public ShardExecutor(int id, CommandRegistry registry, Db db, Evictor evictor) {
        this.queue    = new MpscArrayQueue<>(QUEUE_CAPACITY);
        this.registry = registry;
        this.db       = db;
        this.evictor  = evictor;
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
                // Queue is empty. Hint the CPU to yield pipeline resources to other threads
                // during the spin; lower power consumption and better HT sibling performance.
                // Week 2: replace with a LockSupport.parkNanos approach to trade a few
                // microseconds of latency for a zero-CPU idle path.
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
            // Enforce the maxmemory bound before the command runs, so a write never grows the
            // keyspace past the budget without first reclaiming room (Redis pre-command eviction).
            if (evictor != null) evictor.evictIfNeeded();
            reply = registry.dispatch(db, argList);
        } catch (RuntimeException e) {
            reply = RespValue.error("ERR " + e.getMessage());
        }
        // channel().writeAndFlush() starts from the pipeline tail, passing through RespEncoder.
        // ctx.writeAndFlush() would start from the decoder's position, skipping the encoder.
        cmd.ctx().channel().writeAndFlush(reply);
    }
}
