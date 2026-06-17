package dev.devgurav.mnemo.command.server;

import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.server.ServerStats;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.HashMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code INFO} directly (so the test owns the {@link ServerStats} and can assert uptime
 * and the live connected-client count) and through a populated {@link Db} (so the Keyspace counts
 * are checked across all four value types). Plumbing test (runs in {@code ./gradlew test}).
 */
class InfoCommandTest {

    private ServerStats stats;
    private InfoCommand info;
    private Db db;

    @BeforeEach
    void setUp() {
        stats = new ServerStats();
        info = new InfoCommand(stats, 0); // unlimited maxmemory by default
        db = new Db(new HashMapStore());
    }

    /** Runs INFO with the given arguments (arg 0 is the command name) and returns the body text. */
    private String run(String... parts) {
        List<byte[]> args = new ArrayList<>();
        for (String p : parts) args.add(p.getBytes(StandardCharsets.UTF_8));
        RespValue reply = info.execute(new CommandContext(db, args));
        return new String(((RespValue.BulkString) reply).value(), StandardCharsets.UTF_8);
    }

    /** Parses {@code field:value} lines (ignoring {@code # Section} headers and blanks) into a map. */
    private static Map<String, String> fields(String body) {
        Map<String, String> m = new HashMap<>();
        for (String line : body.split("\r\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            m.put(line.substring(0, colon), line.substring(colon + 1));
        }
        return m;
    }

    @Test
    void emitsAllFourSectionHeaders() {
        String body = run("INFO");
        assertThat(body)
                .contains("# Server")
                .contains("# Clients")
                .contains("# Memory")
                .contains("# Keyspace");
    }

    @Test
    void serverSectionReportsVersionAndUptime() {
        Map<String, String> f = fields(run("INFO"));
        assertThat(f).containsEntry("mnemo_version", ServerStats.VERSION);
        assertThat(f).containsKey("jdk_version");
        // uptime is a non-negative integer
        assertThat(Long.parseLong(f.get("uptime_seconds"))).isGreaterThanOrEqualTo(0);
    }

    @Test
    void clientsSectionTracksTheLiveConnectionCount() {
        assertThat(fields(run("INFO")).get("connected_clients")).isEqualTo("0");
        stats.clientConnected();
        stats.clientConnected();
        stats.clientConnected();
        assertThat(fields(run("INFO")).get("connected_clients")).isEqualTo("3");
        stats.clientDisconnected();
        assertThat(fields(run("INFO")).get("connected_clients")).isEqualTo("2");
    }

    @Test
    void memorySectionReportsLogicalUsedBytesThatGrowWithTheKeyspace() {
        Map<String, String> empty = fields(run("INFO"));
        assertThat(empty).containsEntry("maxmemory", "0");                 // unlimited by default
        assertThat(empty).containsEntry("maxmemory_policy", "noeviction"); // no bound => no policy
        assertThat(empty).containsEntry("evicted_keys", "0");
        long usedEmpty = Long.parseLong(empty.get("used_memory"));
        assertThat(usedEmpty).isZero(); // logical accounting: an empty keyspace is 0 bytes

        db.set("k", "a-value".getBytes(StandardCharsets.UTF_8));
        long usedAfter = Long.parseLong(fields(run("INFO")).get("used_memory"));
        assertThat(usedAfter).isGreaterThan(usedEmpty); // grew by key+value+overhead
    }

    @Test
    void memorySectionReflectsAConfiguredMaxmemoryAndPolicy() {
        InfoCommand bounded = new InfoCommand(stats, 1_000_000);
        String body = new String(((RespValue.BulkString) bounded.execute(new CommandContext(
                db, List.of("INFO".getBytes(StandardCharsets.UTF_8))))).value(), StandardCharsets.UTF_8);
        Map<String, String> f = fields(body);
        assertThat(f).containsEntry("maxmemory", "1000000");
        assertThat(f).containsEntry("maxmemory_policy", "allkeys-lru");
    }

    @Test
    void keyspaceSectionCountsEveryValueType() {
        db.set("s1", "v".getBytes(StandardCharsets.UTF_8));
        db.set("s2", "v".getBytes(StandardCharsets.UTF_8));
        db.zsetForWrite("z1");
        db.hashForWrite("h1");
        db.listForWrite("l1");
        db.listForWrite("l2");
        db.listForWrite("l3");

        Map<String, String> f = fields(run("INFO"));
        assertThat(f).containsEntry("strings", "2");
        assertThat(f).containsEntry("zsets", "1");
        assertThat(f).containsEntry("hashes", "1");
        assertThat(f).containsEntry("lists", "3");
        assertThat(f).containsEntry("db0_keys", "7"); // total across all four namespaces
    }

    @Test
    void aSectionArgumentReturnsOnlyThatSection() {
        String body = run("INFO", "memory");
        assertThat(body).contains("# Memory").contains("maxmemory");
        assertThat(body)
                .doesNotContain("# Server")
                .doesNotContain("# Clients")
                .doesNotContain("# Keyspace");
    }

    @Test
    void sectionNameIsCaseInsensitive() {
        assertThat(run("INFO", "KEYSPACE")).contains("# Keyspace");
    }

    @Test
    void unknownSectionYieldsAnEmptyBody() {
        assertThat(run("INFO", "nosuchsection")).isEmpty();
    }

    @Test
    void tooManyArgumentsIsAnError() {
        RespValue reply = info.execute(new CommandContext(
                db,
                List.of("INFO".getBytes(StandardCharsets.UTF_8),
                        "a".getBytes(StandardCharsets.UTF_8),
                        "b".getBytes(StandardCharsets.UTF_8))));
        assertThat(((RespValue.ErrorReply) reply).message()).contains("wrong number of arguments");
    }
}
