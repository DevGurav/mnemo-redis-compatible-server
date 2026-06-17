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
 *   <li><b>Memory</b> — logical capacity ({@code maxmemory}; 0 = unlimited until the eviction limit
 *       lands in W3, ADR 0006) and the JVM allocator's currently used bytes.</li>
 *   <li><b>Keyspace</b> — key counts, broken down across all four value types (from {@link Db}).</li>
 * </ul>
 *
 * <p>The per-type-counted logical <em>byte</em> accounting ({@code used_payload_bytes}) is the W3
 * eviction-counter work (ADR 0006) and is intentionally not faked here; {@code used_memory} reports
 * the real heap figure instead.
 */
public final class InfoCommand implements Command {

    private final ServerStats stats;

    public InfoCommand(ServerStats stats) {
        this.stats = stats;
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
        if (wants(section, "memory"))   appendMemory(sb);
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

    private void appendMemory(StringBuilder sb) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        sb.append("# Memory\r\n");
        sb.append("maxmemory:0\r\n");                       // logical capacity; 0 = unlimited (W3: ADR 0006)
        sb.append("maxmemory_policy:noeviction\r\n");
        sb.append("used_memory:").append(used).append("\r\n");
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
