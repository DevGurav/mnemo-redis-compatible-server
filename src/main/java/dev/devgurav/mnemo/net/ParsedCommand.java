package dev.devgurav.mnemo.net;

import io.netty.channel.ChannelHandlerContext;

/**
 * An immutable POJO representation of a fully-parsed client command, safe to hand off across
 * the Netty-worker → shard-executor thread boundary.
 *
 * <p>No {@code ByteBuf} references are held. All argument bytes are copied out of the inbound
 * buffer by {@link dev.devgurav.mnemo.net.resp.RespDecoder} on the EventLoop thread before
 * this record is constructed. See architecture-spec.md §2 for the thread-boundary isolation
 * contract.
 *
 * <p>{@code args[0]} is always the command name bytes (e.g. {@code "SET"} as UTF-8); subsequent
 * elements are the command arguments in order. {@code ctx} is the originating channel context;
 * {@link io.netty.channel.ChannelHandlerContext#writeAndFlush} is thread-safe and dispatches
 * back to the owning EventLoop automatically.
 */
public record ParsedCommand(byte[][] args, ChannelHandlerContext ctx) {

    /** Convenience: the number of argument slots including the command name at index 0. */
    public int argCount() {
        return args.length;
    }
}
