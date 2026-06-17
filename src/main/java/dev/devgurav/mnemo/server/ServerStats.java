package dev.devgurav.mnemo.server;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide runtime counters backing the {@code INFO} command.
 *
 * <p>Two of {@code INFO}'s sections need state that lives outside the keyspace: server uptime (since
 * process start) and the number of currently connected clients. The keyspace stats come straight
 * from {@link dev.devgurav.mnemo.store.Db}; everything here is server-level.
 *
 * <p>Thread-safety: {@code connectedClients} is touched from Netty worker threads on connect /
 * disconnect (via the pipeline's connection-counter handler) and read from the shard thread when
 * {@code INFO} executes, so it is an {@link AtomicInteger}. {@code startNanos} is final.
 */
public final class ServerStats {

    /** Reported build version; kept in sync with {@code build.gradle.kts}. */
    public static final String VERSION = "0.1.0";

    private final long startNanos = System.nanoTime();
    private final AtomicInteger connectedClients = new AtomicInteger();

    /** Whole seconds elapsed since this {@code ServerStats} (i.e. the server) was created. */
    public long uptimeSeconds() {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }

    /** Records a new connection. Called from the Netty pipeline on {@code channelActive}. */
    public void clientConnected() {
        connectedClients.incrementAndGet();
    }

    /** Records a dropped connection. Called from the Netty pipeline on {@code channelInactive}. */
    public void clientDisconnected() {
        connectedClients.decrementAndGet();
    }

    /** The number of clients currently connected. */
    public int connectedClients() {
        return connectedClients.get();
    }
}
