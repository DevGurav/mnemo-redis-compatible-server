package dev.devgurav.mnemo.net;

import dev.devgurav.mnemo.server.ServerStats;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * A {@code @Sharable} pipeline handler that keeps {@link ServerStats#connectedClients()} accurate by
 * bumping the counter on {@code channelActive} and decrementing it on {@code channelInactive}.
 *
 * <p>One instance is shared across every connection (it holds no per-channel state), so it is marked
 * {@link io.netty.channel.ChannelHandler.Sharable}. The {@link ServerStats} counter is atomic,
 * which is what makes concurrent connects/disconnects across Netty worker threads safe.
 */
@io.netty.channel.ChannelHandler.Sharable
public final class ConnectionCounterHandler extends ChannelInboundHandlerAdapter {

    private final ServerStats stats;

    public ConnectionCounterHandler(ServerStats stats) {
        this.stats = stats;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        stats.clientConnected();
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stats.clientDisconnected();
        ctx.fireChannelInactive();
    }
}
