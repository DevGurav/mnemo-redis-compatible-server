package dev.devgurav.mnemo.net;

import dev.devgurav.mnemo.net.resp.RespDecoder;
import dev.devgurav.mnemo.net.resp.RespEncoder;
import dev.devgurav.mnemo.server.ServerStats;
import dev.devgurav.mnemo.shard.ShardRouter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

/**
 * Builds the per-connection Netty pipeline.
 *
 * <p>Pipeline stages in order:
 * <ol>
 *   <li>{@link ConnectionCounterHandler} — tracks live connections for {@code INFO clients}.</li>
 *   <li>{@link RespDecoder} — copies RESP2 bytes into a {@link ParsedCommand} POJO.</li>
 *   <li>{@link RespEncoder} — serialises a {@link dev.devgurav.mnemo.net.resp.RespValue} reply
 *       POJO into RESP2 wire bytes.</li>
 *   <li>{@link CommandInboundHandler} — routes the {@code ParsedCommand} to the correct shard
 *       (or fans it out for global commands) via {@link ShardRouter}.</li>
 * </ol>
 */
public final class MnemoChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ShardRouter router;
    private final ConnectionCounterHandler connectionCounter;

    public MnemoChannelInitializer(ShardRouter router, ServerStats stats) {
        this.router = router;
        this.connectionCounter = new ConnectionCounterHandler(stats);
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
          .addLast("conn-counter",  connectionCounter)
          .addLast("resp-decoder",  new RespDecoder())
          .addLast("resp-encoder",  new RespEncoder())
          .addLast("command",       new CommandInboundHandler(router));
    }
}
