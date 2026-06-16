package dev.devgurav.mnemo.net;

import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.shard.ShardExecutor;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Receives a {@link ParsedCommand} on a Netty worker thread and hands it to the
 * {@link ShardExecutor} via its lock-free {@code MpscArrayQueue}.
 *
 * <p>This is the thread-boundary crossing point (architecture-spec.md §1): multi-threaded I/O
 * (Netty workers) hands off to single-threaded execution (the shard) through a typed POJO queue.
 * No locks are acquired. No {@code ByteBuf} references are involved — the decoder already copied
 * all bytes into {@code ParsedCommand.args} before this handler is invoked.
 *
 * <p>Per-connection command order is preserved: Netty delivers {@code channelRead} calls in
 * arrival order, and the MPSC queue is FIFO on the consumer side.
 */
public final class CommandInboundHandler extends ChannelInboundHandlerAdapter {

    private final ShardExecutor shard;

    public CommandInboundHandler(ShardExecutor shard) {
        this.shard = shard;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ParsedCommand cmd)) return;
        if (!shard.offer(cmd)) {
            // Queue full: backpressure. Reply inline on the EventLoop thread rather than dropping
            // silently. The shard is overloaded — the client should reduce pipeline depth.
            ctx.writeAndFlush(RespValue.error("ERR server overloaded, try again"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.writeAndFlush(RespValue.error("ERR " + cause.getMessage()));
        ctx.close();
    }
}
