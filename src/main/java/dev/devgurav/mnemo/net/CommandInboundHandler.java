package dev.devgurav.mnemo.net;

import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.shard.ShardRouter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Receives a {@link ParsedCommand} on a Netty worker thread and routes it to the appropriate
 * {@link dev.devgurav.mnemo.shard.ShardExecutor} via {@link ShardRouter}.
 *
 * <p>For single-key commands the router picks one shard by {@code CRC16(key) % N}. For broadcast
 * commands (DBSIZE, KEYS, FLUSHDB, FLUSHALL) the router fans the command out to all shards with a
 * {@link dev.devgurav.mnemo.shard.ScatterFuture} that aggregates the partial replies before
 * writing one response to the client (ADR 0014).
 *
 * <p>No locks, no synchronization. The MPSC queue inside each {@code ShardExecutor} is the sole
 * cross-thread hand-off point (architecture-spec.md §1). No {@code ByteBuf} references cross this
 * boundary — the decoder already copied all bytes into {@code ParsedCommand.args}.
 */
public final class CommandInboundHandler extends ChannelInboundHandlerAdapter {

    private final ShardRouter router;

    public CommandInboundHandler(ShardRouter router) {
        this.router = router;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ParsedCommand cmd)) return;
        if (!router.route(cmd, ctx)) {
            ctx.writeAndFlush(RespValue.error("ERR server overloaded, try again"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.writeAndFlush(RespValue.error("ERR " + cause.getMessage()));
        ctx.close();
    }
}
