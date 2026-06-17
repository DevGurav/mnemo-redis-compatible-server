package dev.devgurav.mnemo.server;

import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.MnemoChannelInitializer;
import dev.devgurav.mnemo.shard.ShardExecutor;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.Dict;
import dev.devgurav.mnemo.store.HashMapStore;
import dev.devgurav.mnemo.store.KeyValueStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * Mnemo server bootstrap.
 *
 * <p>Threading layout (architecture-spec.md §1):
 * <ul>
 *   <li>I/O domain — Netty {@code bossGroup} (1 thread, accept) + {@code workerGroup} (N threads,
 *       read/write/codec). Multi-threaded; scales across cores for connection throughput.</li>
 *   <li>Data-plane domain — {@link ShardExecutor} (1 thread per shard). All command execution and
 *       all keyspace mutations happen here. No locks required inside {@code store/}.</li>
 * </ul>
 *
 * <p>Implements {@link AutoCloseable}; {@link #start()} returns the actual bound port so tests can
 * run on an ephemeral port ({@code Config(0, false)}).
 */
public final class MnemoServer implements AutoCloseable {

    private final Config config;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ShardExecutor shard;
    private Channel serverChannel;

    public MnemoServer(Config config) {
        this.config = config;
    }

    /** Binds the server, starts the shard executor, and returns the actual bound port. */
    public int start() throws InterruptedException {
        KeyValueStore store = config.useDict() ? new Dict() : new HashMapStore();
        Db db = new Db(store);
        ServerStats stats = new ServerStats();
        CommandRegistry registry = CommandRegistry.standard(stats);

        shard = new ShardExecutor(0, registry, db);
        shard.start();

        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new MnemoChannelInitializer(shard, stats));

        serverChannel = bootstrap.bind(config.port()).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Blocks until the server channel closes. */
    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) serverChannel.closeFuture().sync();
    }

    @Override
    public void close() {
        if (serverChannel != null) serverChannel.close();
        if (bossGroup    != null) bossGroup.shutdownGracefully();
        if (workerGroup  != null) workerGroup.shutdownGracefully();
        if (shard        != null) shard.shutdown();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        MnemoServer server = new MnemoServer(config);
        int port = server.start();
        System.out.println("Mnemo listening on port " + port
                + " (store=" + (config.useDict() ? "Dict" : "HashMapStore") + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.awaitTermination();
    }
}
