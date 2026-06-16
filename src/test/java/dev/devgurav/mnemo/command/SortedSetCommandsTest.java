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
 * Drives the sorted-set commands through the real {@link CommandRegistry} against a {@link Db},
 * covering the wire contract (0-based ranks, negative/clamped ranges, WITHSCORES, score formatting)
 * and the one-type-per-key rules (WRONGTYPE, SET-overwrites, cross-type DEL/EXISTS). Plumbing test —
 * green by default.
 */
class SortedSetCommandsTest {

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
    private static boolean isNil(RespValue v) {
        return v instanceof RespValue.BulkString b && b.value() == null;
    }
    private static List<String> arr(RespValue v) {
        List<String> out = new ArrayList<>();
        for (RespValue item : ((RespValue.ArrayReply) v).items()) {
            out.add(new String(((RespValue.BulkString) item).value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void zaddCountsOnlyNewMembers() {
        assertThat(integer(run("ZADD", "s", "1", "a", "2", "b", "3", "c"))).isEqualTo(3);
        // re-score b (not new) and add d (new) in one call → 1 new
        assertThat(integer(run("ZADD", "s", "5", "b", "4", "d"))).isEqualTo(1);
        assertThat(db.zset("s").size()).isEqualTo(4);
    }

    @Test
    void zrankIsZeroBasedAndNilWhenAbsent() {
        run("ZADD", "s", "10", "a", "20", "b", "30", "c");
        assertThat(integer(run("ZRANK", "s", "a"))).isEqualTo(0);
        assertThat(integer(run("ZRANK", "s", "c"))).isEqualTo(2);
        assertThat(isNil(run("ZRANK", "s", "missing"))).isTrue();
        assertThat(isNil(run("ZRANK", "absent-key", "a"))).isTrue();
    }

    @Test
    void zrankReflectsScoreReordering() {
        run("ZADD", "s", "1", "a", "2", "b");
        assertThat(integer(run("ZRANK", "s", "b"))).isEqualTo(1);
        run("ZADD", "s", "0", "b"); // b becomes the lowest score
        assertThat(integer(run("ZRANK", "s", "b"))).isEqualTo(0);
        assertThat(integer(run("ZRANK", "s", "a"))).isEqualTo(1);
    }

    @Test
    void zrangeHandlesNegativeAndOutOfBoundIndices() {
        run("ZADD", "s", "1", "a", "2", "b", "3", "c");
        assertThat(arr(run("ZRANGE", "s", "0", "-1"))).containsExactly("a", "b", "c");
        assertThat(arr(run("ZRANGE", "s", "0", "1"))).containsExactly("a", "b");
        assertThat(arr(run("ZRANGE", "s", "-2", "-1"))).containsExactly("b", "c");
        assertThat(arr(run("ZRANGE", "s", "5", "10"))).isEmpty();
        assertThat(arr(run("ZRANGE", "absent-key", "0", "-1"))).isEmpty();
    }

    @Test
    void zrangeWithScoresFormatsIntegralScoresWithoutDecimal() {
        run("ZADD", "s", "1", "a", "2.5", "b");
        assertThat(arr(run("ZRANGE", "s", "0", "-1", "WITHSCORES")))
                .containsExactly("a", "1", "b", "2.5");
    }

    @Test
    void sortedSetCommandsAgainstAStringAreWrongType() {
        run("SET", "k", "v");
        assertThat(error(run("ZADD", "k", "1", "a"))).startsWith("WRONGTYPE");
        assertThat(error(run("ZRANK", "k", "a"))).startsWith("WRONGTYPE");
        assertThat(error(run("ZRANGE", "k", "0", "-1"))).startsWith("WRONGTYPE");
    }

    @Test
    void getAgainstASortedSetIsWrongType() {
        run("ZADD", "k", "1", "a");
        assertThat(error(run("GET", "k"))).startsWith("WRONGTYPE");
    }

    @Test
    void setOverwritesASortedSet() {
        run("ZADD", "k", "1", "a");
        assertThat(run("SET", "k", "v")).isInstanceOf(RespValue.SimpleString.class);
        assertThat(db.isZSet("k")).isFalse();
        assertThat(new String(db.get("k"), StandardCharsets.UTF_8)).isEqualTo("v");
    }

    @Test
    void existsAndDelSpanSortedSets() {
        run("ZADD", "k", "1", "a");
        assertThat(integer(run("EXISTS", "k"))).isEqualTo(1);
        assertThat(integer(run("DEL", "k"))).isEqualTo(1);
        assertThat(integer(run("EXISTS", "k"))).isEqualTo(0);
    }

    @Test
    void zaddRejectsInvalidFloatWithoutPartialApply() {
        assertThat(error(run("ZADD", "s", "1", "a", "notanumber", "b"))).contains("not a valid float");
        // the whole command is rejected — nothing was created
        assertThat(db.isZSet("s")).isFalse();
    }

    @Test
    void arityErrorsAreReported() {
        assertThat(error(run("ZADD", "s", "1"))).contains("wrong number of arguments");
        assertThat(error(run("ZRANK", "s"))).contains("wrong number of arguments");
        assertThat(error(run("ZRANGE", "s", "0"))).contains("wrong number of arguments");
    }
}
