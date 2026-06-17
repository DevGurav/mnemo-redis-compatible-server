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
 * Drives TTL commands (EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT, TTL, PTTL, PERSIST) through the real
 * {@link CommandRegistry}. Plumbing test — green by default.
 *
 * <p>Time-sensitive assertions are kept coarse (TTL within a window) so they don't flake under
 * slow CI machines. The key behavioural contract is: a key with a TTL in the past must not be
 * visible after expiry; a key with a far-future TTL must remain visible.
 */
class TtlCommandsTest {

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
    private static String bulk(RespValue v) {
        byte[] b = ((RespValue.BulkString) v).value();
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
    private static String error(RespValue v) { return ((RespValue.ErrorReply) v).message(); }

    // ---- EXPIRE / TTL / PERSIST basics ----

    @Test
    void expireOnMissingKeyReturnsZero() {
        assertThat(integer(run("EXPIRE", "nosuch", "60"))).isEqualTo(0);
    }

    @Test
    void expireOnExistingKeyReturnOne() {
        run("SET", "k", "v");
        assertThat(integer(run("EXPIRE", "k", "60"))).isEqualTo(1);
    }

    @Test
    void ttlReturnsRemainingSecondsApprox() {
        run("SET", "k", "v");
        run("EXPIRE", "k", "60");
        long ttl = integer(run("TTL", "k"));
        assertThat(ttl).isBetween(58L, 60L); // allow a second of wall-clock slack
    }

    @Test
    void ttlMinusOneWhenNoTtl() {
        run("SET", "k", "v");
        assertThat(integer(run("TTL", "k"))).isEqualTo(-1);
    }

    @Test
    void ttlMinusTwoWhenKeyMissing() {
        assertThat(integer(run("TTL", "nosuch"))).isEqualTo(-2);
    }

    @Test
    void pttlReturnsMsApprox() {
        run("SET", "k", "v");
        run("PEXPIRE", "k", "60000");
        long pttl = integer(run("PTTL", "k"));
        assertThat(pttl).isBetween(58_000L, 60_000L);
    }

    @Test
    void persistRemovesTtlAndReturnOne() {
        run("SET", "k", "v");
        run("EXPIRE", "k", "60");
        assertThat(integer(run("PERSIST", "k"))).isEqualTo(1);
        assertThat(integer(run("TTL", "k"))).isEqualTo(-1); // no more TTL
    }

    @Test
    void persistOnKeyWithNoTtlReturnsZero() {
        run("SET", "k", "v");
        assertThat(integer(run("PERSIST", "k"))).isEqualTo(0);
    }

    // ---- EXPIREAT / PEXPIREAT ----

    @Test
    void expireatWithFutureTimestampSetsExpiry() {
        run("SET", "k", "v");
        long futureTs = System.currentTimeMillis() / 1000 + 60;
        assertThat(integer(run("EXPIREAT", "k", String.valueOf(futureTs)))).isEqualTo(1);
        long ttl = integer(run("TTL", "k"));
        assertThat(ttl).isBetween(58L, 60L);
    }

    @Test
    void pexpireatWithFutureTimestampSetsExpiry() {
        run("SET", "k", "v");
        long futureMs = System.currentTimeMillis() + 30_000;
        assertThat(integer(run("PEXPIREAT", "k", String.valueOf(futureMs)))).isEqualTo(1);
        long pttl = integer(run("PTTL", "k"));
        assertThat(pttl).isBetween(28_000L, 30_000L);
    }

    // ---- Lazy expiry on reads ----

    @Test
    void expiredKeyIsInvisibleOnGet() throws InterruptedException {
        run("SET", "k", "v");
        run("PEXPIRE", "k", "50"); // 50 ms
        Thread.sleep(80);
        assertThat(bulk(run("GET", "k"))).isNull();
    }

    @Test
    void expiredKeyIsInvisibleOnExists() throws InterruptedException {
        run("SET", "k", "v");
        run("PEXPIRE", "k", "50");
        Thread.sleep(80);
        assertThat(integer(run("EXISTS", "k"))).isEqualTo(0);
    }

    @Test
    void expiredKeyIsInvisibleOnTtl() throws InterruptedException {
        run("SET", "k", "v");
        run("PEXPIRE", "k", "50");
        Thread.sleep(80);
        assertThat(integer(run("TTL", "k"))).isEqualTo(-2);
    }

    // ---- SET clears TTL ----

    @Test
    void setOverwritesClearsTtl() {
        run("SET", "k", "v");
        run("EXPIRE", "k", "60");
        run("SET", "k", "v2"); // should clear the TTL
        assertThat(integer(run("TTL", "k"))).isEqualTo(-1);
    }

    // ---- TTL across types ----

    @Test
    void ttlWorksOnHashKey() {
        run("HSET", "h", "f", "v");
        assertThat(integer(run("EXPIRE", "h", "60"))).isEqualTo(1);
        assertThat(integer(run("TTL", "h"))).isBetween(58L, 60L);
    }

    @Test
    void ttlWorksOnListKey() throws InterruptedException {
        run("LPUSH", "l", "x");
        run("PEXPIRE", "l", "50");
        Thread.sleep(80);
        // List should be gone
        assertThat(integer(run("EXISTS", "l"))).isEqualTo(0);
    }

    @Test
    void ttlWorksOnZSetKey() {
        run("ZADD", "z", "1", "m");
        assertThat(integer(run("EXPIRE", "z", "60"))).isEqualTo(1);
        assertThat(integer(run("TTL", "z"))).isBetween(58L, 60L);
    }

    // ---- Arity errors ----

    @Test
    void arityErrors() {
        assertThat(error(run("EXPIRE"))).contains("wrong number of arguments");
        assertThat(error(run("TTL"))).contains("wrong number of arguments");
        assertThat(error(run("PERSIST"))).contains("wrong number of arguments");
        assertThat(error(run("PTTL"))).contains("wrong number of arguments");
    }

    @Test
    void negativeExpireIsAnError() {
        run("SET", "k", "v");
        assertThat(error(run("EXPIRE", "k", "-1"))).contains("invalid expire time");
    }
}
