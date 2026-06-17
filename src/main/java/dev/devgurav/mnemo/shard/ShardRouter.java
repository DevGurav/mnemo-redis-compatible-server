package dev.devgurav.mnemo.shard;

import dev.devgurav.mnemo.net.ParsedCommand;
import dev.devgurav.mnemo.net.resp.RespValue;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Routes incoming {@link ParsedCommand}s to the appropriate {@link ShardExecutor} based on
 * CRC-16 key hashing, or fans them out to all shards for global operations (ADR 0014).
 *
 * <h2>Routing rules</h2>
 * <ul>
 *   <li><b>Single-key commands</b> (SET, GET, HSET, …) — {@code shard = CRC16(args[1]) % N}.
 *       The key is args[1]; for keyless invocations (arity 1) shard 0 is used.</li>
 *   <li><b>Broadcast commands</b> (DBSIZE, KEYS, FLUSHDB, FLUSHALL) — fanned out to all N
 *       shards via a {@link ScatterFuture} that merges the partial results before replying.</li>
 *   <li><b>Metadata commands</b> (PING, ECHO, INFO, COMMAND) — always routed to shard 0 since
 *       they do not touch the keyspace.</li>
 * </ul>
 *
 * <h2>Multi-key limitation</h2>
 * <p>Commands that accept multiple keys in a single call (e.g. {@code DEL key1 key2}) are routed
 * by the <em>first</em> key only. Keys on other shards will not be affected. Callers that require
 * cross-shard atomicity must use {@code {hashtag}} to co-locate related keys.
 */
public final class ShardRouter {

    /** Commands that must execute on every shard; results are merged before replying. */
    private static final Set<String> BROADCAST_COMMANDS = Set.of(
            "DBSIZE", "KEYS", "FLUSHDB", "FLUSHALL"
    );

    /** Commands that carry no key and are always routed to shard 0. */
    private static final Set<String> SHARD0_COMMANDS = Set.of(
            "PING", "ECHO", "INFO", "COMMAND"
    );

    private final ShardExecutor[] shards;

    public ShardRouter(ShardExecutor[] shards) {
        if (shards == null || shards.length == 0) throw new IllegalArgumentException("need ≥1 shard");
        this.shards = shards.clone();
    }

    public int shardCount() { return shards.length; }

    public ShardExecutor shard(int index) { return shards[index]; }

    /**
     * Route or fan-out the command. Returns {@code true} if it was accepted; {@code false} if
     * at least one shard's queue was full (backpressure — the caller should signal the client).
     */
    public boolean route(ParsedCommand cmd, ChannelHandlerContext ctx) {
        if (shards.length == 1) {
            return shards[0].offer(cmd); // fast path — skip all routing overhead
        }

        String name = commandName(cmd.args());

        if (BROADCAST_COMMANDS.contains(name)) {
            return fanOut(cmd, ctx, name);
        }

        int idx = SHARD0_COMMANDS.contains(name) ? 0 : shardIndexFor(cmd.args());
        return shards[idx].offer(cmd);
    }

    /** All shards → {@link ShardExecutor#shutdown()}. */
    public void shutdownAll() {
        for (ShardExecutor s : shards) s.shutdown();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean fanOut(ParsedCommand cmd, ChannelHandlerContext ctx, String name) {
        ScatterFuture scatter = switch (name) {
            case "DBSIZE"            -> ScatterFuture.dbSize(ctx, shards.length);
            case "FLUSHDB", "FLUSHALL" -> ScatterFuture.flush(ctx, shards.length);
            case "KEYS"              -> ScatterFuture.keys(ctx, shards.length);
            default                  -> ScatterFuture.flush(ctx, shards.length); // fallback OK
        };

        boolean accepted = true;
        for (int i = 0; i < shards.length; i++) {
            ParsedCommand scattered = new ParsedCommand(cmd.args(), ctx, scatter, i);
            if (!shards[i].offer(scattered)) accepted = false;
        }
        return accepted;
    }

    private int shardIndexFor(byte[][] args) {
        if (args.length < 2) return 0;
        int crc = Crc16.compute(args[1]);
        return crc % shards.length;
    }

    private static String commandName(byte[][] args) {
        if (args.length == 0) return "";
        return new String(args[0], StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
    }
}
