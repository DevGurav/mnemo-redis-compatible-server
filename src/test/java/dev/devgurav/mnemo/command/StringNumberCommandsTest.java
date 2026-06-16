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
 * Drives the integer-counter commands through the real {@link CommandRegistry}: missing-key-is-zero,
 * the decimal-string round-trip with GET, INCRBY/DECRBY deltas, and the error paths
 * (not-an-integer, overflow, WRONGTYPE). Plumbing test — green by default.
 */
class StringNumberCommandsTest {

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
    private static String bulk(RespValue v) {
        return new String(((RespValue.BulkString) v).value(), StandardCharsets.UTF_8);
    }

    @Test
    void incrOnMissingKeyStartsAtZero() {
        assertThat(integer(run("INCR", "n"))).isEqualTo(1);
        assertThat(integer(run("INCR", "n"))).isEqualTo(2);
        // stored as its decimal string, so GET sees it
        assertThat(bulk(run("GET", "n"))).isEqualTo("2");
    }

    @Test
    void decrOnMissingKeyGoesNegative() {
        assertThat(integer(run("DECR", "n"))).isEqualTo(-1);
        assertThat(integer(run("DECR", "n"))).isEqualTo(-2);
    }

    @Test
    void incrByAndDecrByApplyDeltas() {
        assertThat(integer(run("INCRBY", "n", "10"))).isEqualTo(10);
        assertThat(integer(run("DECRBY", "n", "3"))).isEqualTo(7);
        assertThat(integer(run("INCRBY", "n", "-2"))).isEqualTo(5);
    }

    @Test
    void incrOnAnExistingStringNumberContinues() {
        run("SET", "n", "100");
        assertThat(integer(run("INCR", "n"))).isEqualTo(101);
    }

    @Test
    void incrOnNonIntegerValueIsAnError() {
        run("SET", "n", "hello");
        assertThat(error(run("INCR", "n"))).contains("not an integer");
        // the bad value is left untouched
        assertThat(bulk(run("GET", "n"))).isEqualTo("hello");
    }

    @Test
    void incrByRejectsANonIntegerDelta() {
        assertThat(error(run("INCRBY", "n", "1.5"))).contains("not an integer");
    }

    @Test
    void overflowIsRejected() {
        run("SET", "n", String.valueOf(Long.MAX_VALUE));
        assertThat(error(run("INCR", "n"))).contains("overflow");
        // value unchanged after the rejected increment
        assertThat(bulk(run("GET", "n"))).isEqualTo(String.valueOf(Long.MAX_VALUE));
    }

    @Test
    void underflowIsRejected() {
        run("SET", "n", String.valueOf(Long.MIN_VALUE));
        assertThat(error(run("DECR", "n"))).contains("overflow");
    }

    @Test
    void incrOnASortedSetIsWrongType() {
        run("ZADD", "n", "1", "a");
        assertThat(error(run("INCR", "n"))).startsWith("WRONGTYPE");
        assertThat(error(run("DECRBY", "n", "2"))).startsWith("WRONGTYPE");
    }

    @Test
    void arityErrorsAreReported() {
        assertThat(error(run("INCR"))).contains("wrong number of arguments");
        assertThat(error(run("INCRBY", "n"))).contains("wrong number of arguments");
    }
}
