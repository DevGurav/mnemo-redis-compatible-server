package dev.devgurav.mnemo.command.server;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.server.ServerStats;
import dev.devgurav.mnemo.store.Db;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@code INFO [section]} → a Redis-compatible bulk string of {@code field:value} lines grouped under
 * {@code # Section} headers (api-protocol.md §5). With no argument every section is returned; with a
 * section name only that section is returned (unknown names yield an empty bulk string, as Redis).
 *
 * <p>Four sections are emitted today:
 * <ul>
 *   <li><b>Server</b> — version, JDK, GC, and uptime (from {@link ServerStats}).</li>
 *   <li><b>Clients</b> — currently connected channels (from {@link ServerStats}).</li>
 *   <li><b>Memory</b> — the logical-byte budget ({@code maxmemory}; 0 = unlimited), the live logical
 *       {@code used_memory} of the evictable keyspace ({@link Db#usedMemory()}, ADR 0006), and the
 *       cumulative {@code evicted_keys} count.</li>
 *   <li><b>Keyspace</b> — key counts, broken down across all four value types (from {@link Db}).</li>
 * </ul>
 */
public final class InfoCommand implements Command {

    private final ServerStats stats;
    private final long maxmemory;

    public InfoCommand(ServerStats stats, long maxmemory) {
        this.stats = stats;
        this.maxmemory = maxmemory;
    }

    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() > 2) {
            return RespValue.error("ERR wrong number of arguments for 'info' command");
        }
        String section = ctx.argCount() == 2 ? ctx.argString(1).toLowerCase(Locale.ROOT) : "all";

        StringBuilder sb = new StringBuilder(512);
        if (wants(section, "server"))   appendServer(sb);
        if (wants(section, "clients"))  appendClients(sb);
        if (wants(section, "memory"))   appendMemory(sb, ctx.db());
        if (wants(section, "keyspace")) appendKeyspace(sb, ctx.db());

        return RespValue.bulk(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean wants(String requested, String section) {
        return requested.equals("all") || requested.equals(section);
    }

    private void appendServer(StringBuilder sb) {
        sb.append("# Server\r\n");
        sb.append("mnemo_version:").append(ServerStats.VERSION).append("\r\n");
        sb.append("jdk_version:").append(System.getProperty("java.version")).append("\r\n");
        sb.append("gc:ZGC-generational\r\n");
        sb.append("uptime_seconds:").append(stats.uptimeSeconds()).append("\r\n");
        sb.append("\r\n");
    }

    private void appendClients(StringBuilder sb) {
        sb.append("# Clients\r\n");
        sb.append("connected_clients:").append(stats.connectedClients()).append("\r\n");
        sb.append("\r\n");
    }

    private void appendMemory(StringBuilder sb, Db db) {
        sb.append("# Memory\r\n");
        sb.append("maxmemory:").append(maxmemory).append("\r\n");          // 0 = unlimited
        sb.append("maxmemory_policy:")
          .append(maxmemory > 0 ? "allkeys-lru" : "noeviction").append("\r\n");
        sb.append("used_memory:").append(db.usedMemory()).append("\r\n");  // logical bytes (ADR 0006)
        sb.append("evicted_keys:").append(db.evictedKeys()).append("\r\n");
        sb.append("\r\n");
    }

    private void appendKeyspace(StringBuilder sb, Db db) {
        sb.append("# Keyspace\r\n");
        sb.append("db0_keys:").append(db.size()).append("\r\n");
        sb.append("strings:").append(db.stringCount()).append("\r\n");
        sb.append("zsets:").append(db.zsetCount()).append("\r\n");
        sb.append("hashes:").append(db.hashCount()).append("\r\n");
        sb.append("lists:").append(db.listCount()).append("\r\n");
        sb.append("\r\n");
    }
}
