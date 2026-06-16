package dev.devgurav.mnemo.command;

import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.HashMapStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the keyspace introspection / admin commands ({@code TYPE}, {@code DBSIZE}, {@code FLUSHDB},
 * {@code FLUSHALL}) through the real {@link CommandRegistry}. Plumbing test — green by default.
 */
class KeyspaceCommandsTest {

    private CommandRegistry registry;
    private Db db;

    @BeforeEach
    void setUp() {
        registry = CommandRegistry.standard();
        db = new Db(new HashMapStore());
    }

    private RespValue run(String... parts) {
        List<byte[]> args = new ArrayList<>();
        for (String p : parts) args.add(p.getBytes(StandardCharsets.UTF_8));
        return registry.dispatch(db, args);
    }

    private static long integer(RespValue v) { return ((RespValue.IntegerReply) v).value(); }
    private static String simple(RespValue v) { return ((RespValue.SimpleString) v).value(); }
    private static String error(RespValue v) { return ((RespValue.ErrorReply) v).message(); }

    @Test
    void typeReportsTheValueType() {
        run("SET", "s", "v");
        run("ZADD", "z", "1", "a");
        assertThat(simple(run("TYPE", "s"))).isEqualTo("string");
        assertThat(simple(run("TYPE", "z"))).isEqualTo("zset");
        assertThat(simple(run("TYPE", "missing"))).isEqualTo("none");
    }

    @Test
    void typeFollowsAnOverwrite() {
        run("ZADD", "k", "1", "a");
        assertThat(simple(run("TYPE", "k"))).isEqualTo("zset");
        run("SET", "k", "v"); // SET overwrites any type
        assertThat(simple(run("TYPE", "k"))).isEqualTo("string");
    }

    @Test
    void dbsizeCountsKeysAcrossTypes() {
        assertThat(integer(run("DBSIZE"))).isEqualTo(0);
        run("SET", "s", "v");
        run("ZADD", "z", "1", "a");
        assertThat(integer(run("DBSIZE"))).isEqualTo(2);
        run("DEL", "s");
        assertThat(integer(run("DBSIZE"))).isEqualTo(1);
    }

    @Test
    void flushdbEmptiesTheKeyspace() {
        run("SET", "s", "v");
        run("ZADD", "z", "1", "a");
        assertThat(simple(run("FLUSHDB"))).isEqualTo("OK");
        assertThat(integer(run("DBSIZE"))).isEqualTo(0);
    }

    @Test
    void flushallEmptiesTheKeyspace() {
        run("SET", "s", "v");
        assertThat(simple(run("FLUSHALL"))).isEqualTo("OK");
        assertThat(integer(run("DBSIZE"))).isEqualTo(0);
    }

    @Test
    void arityErrorsAreReported() {
        assertThat(error(run("TYPE"))).contains("wrong number of arguments");
        assertThat(error(run("DBSIZE", "extra"))).contains("wrong number of arguments");
        assertThat(error(run("FLUSHDB", "ASYNC"))).contains("wrong number of arguments");
    }
}
