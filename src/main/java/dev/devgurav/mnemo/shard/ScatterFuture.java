package dev.devgurav.mnemo.shard;

import dev.devgurav.mnemo.net.resp.RespValue;
import io.netty.channel.ChannelHandlerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Collects partial {@link RespValue} replies from N shards, merges them, and writes the
 * single aggregated reply back to the originating client channel (ADR 0014).
 *
 * <p>Thread-safe: each shard calls {@link #contribute} from its own thread; only the last
 * caller (countdown reaches zero) writes to the channel. The {@code RespValue[]} partials array
 * is written by exactly one thread per slot (shard index), then read by the last thread — the
 * implicit happens-before established by {@link AtomicInteger#decrementAndGet()} reaching zero
 * guarantees all writes are visible before the merge.
 */
public final class ScatterFuture {

    private final ChannelHandlerContext ctx;
    private final RespValue[] partials;
    private final AtomicInteger remaining;
    private final Function<RespValue[], RespValue> merger;

    private ScatterFuture(ChannelHandlerContext ctx, int shardCount,
                          Function<RespValue[], RespValue> merger) {
        this.ctx       = ctx;
        this.partials  = new RespValue[shardCount];
        this.remaining = new AtomicInteger(shardCount);
        this.merger    = merger;
    }

    /**
     * Called by shard {@code shardIndex} when it has finished executing the scatter command.
     * The last shard to call this method merges all partials and writes the final reply.
     */
    public void contribute(int shardIndex, RespValue reply) {
        partials[shardIndex] = reply;
        if (remaining.decrementAndGet() == 0) {
            ctx.channel().writeAndFlush(merger.apply(partials));
        }
    }

    // -------------------------------------------------------------------------
    // Factory methods for each broadcast command type
    // -------------------------------------------------------------------------

    /** {@code DBSIZE} → sum of all shards' integer key counts. */
    static ScatterFuture dbSize(ChannelHandlerContext ctx, int shards) {
        return new ScatterFuture(ctx, shards, parts -> {
            long sum = 0;
            for (RespValue p : parts) if (p instanceof RespValue.IntegerReply r) sum += r.value();
            return RespValue.integer(sum);
        });
    }

    /** {@code FLUSHDB} / {@code FLUSHALL} → first error wins, otherwise +OK. */
    static ScatterFuture flush(ChannelHandlerContext ctx, int shards) {
        return new ScatterFuture(ctx, shards, parts -> {
            for (RespValue p : parts) if (p instanceof RespValue.ErrorReply e) return e;
            return RespValue.ok();
        });
    }

    /** {@code KEYS} → union of all shards' key arrays. */
    static ScatterFuture keys(ChannelHandlerContext ctx, int shards) {
        return new ScatterFuture(ctx, shards, parts -> {
            List<RespValue> all = new ArrayList<>();
            for (RespValue p : parts) {
                if (p instanceof RespValue.ArrayReply a && a.items() != null) {
                    all.addAll(a.items());
                }
            }
            return RespValue.array(all);
        });
    }
}
