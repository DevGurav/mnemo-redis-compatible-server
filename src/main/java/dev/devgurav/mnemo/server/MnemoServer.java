package dev.devgurav.mnemo.server;

import dev.devgurav.mnemo.aof.AofReplayer;
import dev.devgurav.mnemo.aof.AofWriter;
import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.MnemoChannelInitializer;
import dev.devgurav.mnemo.shard.ShardExecutor;
import dev.devgurav.mnemo.shard.ShardRouter;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.Dict;
import dev.devgurav.mnemo.store.HashMapStore;
import dev.devgurav.mnemo.store.KeyValueStore;
import dev.devgurav.mnemo.store.evict.Evictor;
import dev.devgurav.mnemo.store.evict.EvictionPolicy;
import dev.devgurav.mnemo.ttl.TtlSweeper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Mnemo server bootstrap.
 *
 * <p>Threading layout (architecture-spec.md §1):
 * <ul>
 *   <li>I/O domain — Netty {@code bossGroup} (1 thread, accept) + {@code workerGroup} (N threads,
 *       read/write/codec). Multi-threaded; scales across connections.</li>
 *   <li>Data-plane domain — {@link ShardExecutor} (1 thread per shard, {@code shardCount} shards).
 *       All command execution and all keyspace mutations happen here. No locks inside {@code store/}.
 *       The {@link ShardRouter} routes each command to the correct shard by {@code CRC16(key) % N};
 *       broadcast commands (DBSIZE, FLUSHDB, KEYS) fan out to all shards via
 *       {@link dev.devgurav.mnemo.shard.ScatterFuture} (ADR 0014).</li>
 * </ul>
 *
 * <p>AOF persistence is single-shard only: when {@code shardCount > 1} and {@code aofPath} is
 * set, the AOF is silently disabled (cross-shard append-log merging is out of scope).
 */
public final class MnemoServer implements AutoCloseable {

    private final Config config;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ShardRouter router;
    private final AofWriter[] aofs;     // one per shard; null entries if AOF disabled
    private Channel serverChannel;

    public MnemoServer(Config config) {
        this.config = config;
        this.aofs   = new AofWriter[Math.max(1, config.shardCount())];
    }

    /** Binds the server, starts all shard executors, and returns the actual bound port. */
    public int start() throws InterruptedException {
        int n = Math.max(1, config.shardCount());
        EvictionPolicy policy = config.evictionPolicy();
        ServerStats stats = new ServerStats();
        CommandRegistry registry = CommandRegistry.standard(stats, config.maxmemory(), policy);

        ShardExecutor[] shards = new ShardExecutor[n];
        for (int i = 0; i < n; i++) {
            KeyValueStore store = config.useDict() ? new Dict() : new HashMapStore();
            Db db = new Db(store);

            Evictor evictor = (config.maxmemory() > 0 && store instanceof Dict dict)
                    ? new Evictor(dict, db, config.maxmemory(), Evictor.DEFAULT_SAMPLE_SIZE, policy)
                    : null;

            TtlSweeper sweeper = new TtlSweeper(db);

            // AOF only available in single-shard mode.
            AofWriter aof = null;
            if (n == 1 && config.aofPath() != null) {
                Path aofPath = Path.of(config.aofPath());
                try {
                    int replayed = AofReplayer.replay(aofPath, registry, db);
                    if (replayed > 0) {
                        System.out.println("AOF: replayed " + replayed + " commands from " + aofPath);
                    }
                    aof = new AofWriter(aofPath);
                    aofs[0] = aof;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to initialise AOF at " + aofPath, e);
                }
            }

            shards[i] = new ShardExecutor(i, registry, db, evictor, sweeper, aof);
            shards[i].start();
        }

        router      = new ShardRouter(shards);
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new MnemoChannelInitializer(router, stats));

        serverChannel = bootstrap.bind(config.port()).sync().channel();

        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        System.out.println("Mnemo listening on port " + port
                + " (store=" + (config.useDict() ? "Dict" : "HashMapStore")
                + ", shards=" + n
                + ", maxmemory=" + (config.maxmemory() > 0 ? config.maxmemory() + " bytes" : "unlimited")
                + ", eviction=" + policy.toConfigString()
                + ", aof=" + (config.aofPath() != null && n == 1 ? config.aofPath() : "disabled")
                + ")");
        return port;
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
        if (router       != null) router.shutdownAll();
        for (AofWriter aof : aofs) {
            if (aof != null) try { aof.close(); } catch (IOException ignored) {}
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        MnemoServer server = new MnemoServer(config);
        int port = server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.awaitTermination();
    }
}
