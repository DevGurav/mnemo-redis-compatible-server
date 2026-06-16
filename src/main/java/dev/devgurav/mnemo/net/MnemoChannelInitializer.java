package dev.devgurav.mnemo.net;

import dev.devgurav.mnemo.net.resp.RespDecoder;
import dev.devgurav.mnemo.net.resp.RespEncoder;
import dev.devgurav.mnemo.shard.ShardExecutor;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

/**
 * Builds the per-connection Netty pipeline.
 *
 * <p>Pipeline stages in order:
 * <ol>
 *   <li>{@link RespDecoder} — copies RESP2 bytes into a {@link ParsedCommand} POJO, consuming
 *       no {@code ByteBuf} references past the handler boundary.</li>
 *   <li>{@link RespEncoder} — serialises a {@link dev.devgurav.mnemo.net.resp.RespValue} reply
 *       POJO into RESP2 wire bytes; allocates a pooled {@code ByteBuf} that Netty releases after
 *       the write completes.</li>
 *   <li>{@link CommandInboundHandler} — enqueues the {@code ParsedCommand} onto the shard's
 *       {@code MpscArrayQueue} for execution on the shard thread.</li>
 * </ol>
 */
public final class MnemoChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ShardExecutor shard;

    public MnemoChannelInitializer(ShardExecutor shard) {
        this.shard = shard;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
          .addLast("resp-decoder", new RespDecoder())
          .addLast("resp-encoder", new RespEncoder())
          .addLast("command",      new CommandInboundHandler(shard));
    }
}
