package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.resp.RespValue;
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
 * Drives the hash commands through the real {@link CommandRegistry}: HSET/HGET/HGETALL/HDEL/HLEN,
 * the one-type-per-key rules, and — the headline case — HGETALL returning every field while the
 * backing hash {@code Dict} is mid incremental rehash. Plumbing test (runs in {@code ./gradlew test}).
 */
class HashCommandsTest {

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
    private static String error(RespValue v) { return ((RespValue.ErrorReply) v).message(); }
    private static String simple(RespValue v) { return ((RespValue.SimpleString) v).value(); }
    private static boolean isNil(RespValue v) {
        return v instanceof RespValue.BulkString b && b.value() == null;
    }
    private static String str(RespValue v) {
        return new String(((RespValue.BulkString) v).value(), StandardCharsets.UTF_8);
    }
    private static Map<String, String> hgetallMap(RespValue v) {
        List<RespValue> items = ((RespValue.ArrayReply) v).items();
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < items.size(); i += 2) m.put(str(items.get(i)), str(items.get(i + 1)));
        return m;
    }

    @Test
    void hsetCountsOnlyNewFields() {
        assertThat(integer(run("HSET", "h", "f1", "v1", "f2", "v2"))).isEqualTo(2);
        // f1 updated (not counted), f3 new (counted)
        assertThat(integer(run("HSET", "h", "f1", "V1", "f3", "v3"))).isEqualTo(1);
        assertThat(integer(run("HLEN", "h"))).isEqualTo(3);
        assertThat(str(run("HGET", "h", "f1"))).isEqualTo("V1");
    }

    @Test
    void hgetMissingFieldOrKeyIsNil() {
        run("HSET", "h", "f1", "v1");
        assertThat(isNil(run("HGET", "h", "absent"))).isTrue();
        assertThat(isNil(run("HGET", "absent-key", "f1"))).isTrue();
    }

    @Test
    void hgetallReturnsAllFieldValuePairs() {
        run("HSET", "h", "a", "1", "b", "2", "c", "3");
        assertThat(hgetallMap(run("HGETALL", "h")))
                .containsOnly(Map.entry("a", "1"), Map.entry("b", "2"), Map.entry("c", "3"));
    }

    @Test
    void hgetallOnAbsentKeyIsEmptyArray() {
        assertThat(((RespValue.ArrayReply) run("HGETALL", "nope")).items()).isEmpty();
    }

    @Test
    void hdelRemovesFieldsAndDropsAnEmptiedHash() {
        run("HSET", "h", "f1", "v1", "f2", "v2");
        assertThat(integer(run("HDEL", "h", "f1", "absent"))).isEqualTo(1);
        assertThat(integer(run("HLEN", "h"))).isEqualTo(1);
        // removing the last field deletes the key (Redis semantics)
        assertThat(integer(run("HDEL", "h", "f2"))).isEqualTo(1);
        assertThat(integer(run("EXISTS", "h"))).isEqualTo(0);
        assertThat(simple(run("TYPE", "h"))).isEqualTo("none");
    }

    @Test
    void hlenOnAbsentKeyIsZero() {
        assertThat(integer(run("HLEN", "absent"))).isEqualTo(0);
    }

    @Test
    void typeReportsHash() {
        run("HSET", "h", "f", "v");
        assertThat(simple(run("TYPE", "h"))).isEqualTo("hash");
    }

    @Test
    void hashCommandsAgainstAStringOrZsetAreWrongType() {
        run("SET", "s", "v");
        assertThat(error(run("HSET", "s", "f", "v"))).startsWith("WRONGTYPE");
        assertThat(error(run("HGET", "s", "f"))).startsWith("WRONGTYPE");
        assertThat(error(run("HGETALL", "s"))).startsWith("WRONGTYPE");
        assertThat(error(run("HDEL", "s", "f"))).startsWith("WRONGTYPE");
        assertThat(error(run("HLEN", "s"))).startsWith("WRONGTYPE");

        run("ZADD", "z", "1", "a");
        assertThat(error(run("HSET", "z", "f", "v"))).startsWith("WRONGTYPE");
    }

    @Test
    void stringAndZsetCommandsAgainstAHashAreWrongType() {
        run("HSET", "h", "f", "v");
        assertThat(error(run("GET", "h"))).startsWith("WRONGTYPE");
        assertThat(error(run("INCR", "h"))).startsWith("WRONGTYPE");
        assertThat(error(run("ZADD", "h", "1", "a"))).startsWith("WRONGTYPE");
    }

    @Test
    void setOverwritesAHash() {
        run("HSET", "h", "f", "v");
        assertThat(run("SET", "h", "x")).isInstanceOf(RespValue.SimpleString.class);
        assertThat(simple(run("TYPE", "h"))).isEqualTo("string");
        assertThat(str(run("GET", "h"))).isEqualTo("x");
    }

    @Test
    void arityErrorsAreReported() {
        assertThat(error(run("HSET", "h", "f"))).contains("wrong number of arguments");
        assertThat(error(run("HGET", "h"))).contains("wrong number of arguments");
        assertThat(error(run("HDEL", "h"))).contains("wrong number of arguments");
    }

    @Test
    void hgetallReturnsEveryFieldAcrossRehashStates() {
        // The backing hash Dict resizes — and is mid incremental rehash — for many of these field
        // counts. HGETALL must merge both internal tables and return every field, every time.
        for (int n = 1; n <= 100; n++) {
            String key = "h" + n;
            String[] args = new String[2 + n * 2];
            args[0] = "HSET";
            args[1] = key;
            for (int i = 0; i < n; i++) {
                args[2 + i * 2]     = "f" + i;
                args[2 + i * 2 + 1] = "v" + i;
            }
            assertThat(integer(run(args))).isEqualTo(n); // all fields new

            Map<String, String> all = hgetallMap(run("HGETALL", key));
            assertThat(all).hasSize(n);
            for (int i = 0; i < n; i++) assertThat(all).containsEntry("f" + i, "v" + i);
        }
    }
}
