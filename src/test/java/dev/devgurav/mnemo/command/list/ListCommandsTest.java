package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.HashMapStore;
import dev.devgurav.mnemo.store.list.IntrusiveList;
import dev.devgurav.mnemo.store.list.ListNodePool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the list commands through the real {@link CommandRegistry}: LPUSH/RPUSH/LPOP/RPOP/LLEN/
 * LRANGE, the one-type-per-key rules against the other three namespaces, and Redis index semantics
 * for LRANGE. A separate block asserts the GC thesis directly on {@link IntrusiveList}: popped nodes
 * are recycled through the {@link ListNodePool} rather than handed to GC. Plumbing test (runs in
 * {@code ./gradlew test}).
 */
class ListCommandsTest {

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
    private static List<String> array(RespValue v) {
        List<RespValue> items = ((RespValue.ArrayReply) v).items();
        List<String> out = new ArrayList<>(items.size());
        for (RespValue item : items) out.add(str(item));
        return out;
    }

    @Test
    void rpushAppendsAndReturnsLength() {
        assertThat(integer(run("RPUSH", "l", "a", "b", "c"))).isEqualTo(3);
        assertThat(integer(run("RPUSH", "l", "d"))).isEqualTo(4);
        assertThat(array(run("LRANGE", "l", "0", "-1"))).containsExactly("a", "b", "c", "d");
    }

    @Test
    void lpushPrependsEachValueInTurn() {
        // LPUSH a b c => list becomes c b a (each value pushed to the head in order)
        assertThat(integer(run("LPUSH", "l", "a", "b", "c"))).isEqualTo(3);
        assertThat(array(run("LRANGE", "l", "0", "-1"))).containsExactly("c", "b", "a");
    }

    @Test
    void lpopAndRpopReturnEndsAndShrink() {
        run("RPUSH", "l", "a", "b", "c");
        assertThat(str(run("LPOP", "l"))).isEqualTo("a");
        assertThat(str(run("RPOP", "l"))).isEqualTo("c");
        assertThat(integer(run("LLEN", "l"))).isEqualTo(1);
        assertThat(array(run("LRANGE", "l", "0", "-1"))).containsExactly("b");
    }

    @Test
    void popOnAbsentKeyIsNil() {
        assertThat(isNil(run("LPOP", "nope"))).isTrue();
        assertThat(isNil(run("RPOP", "nope"))).isTrue();
    }

    @Test
    void poppingTheLastElementDeletesTheKey() {
        run("RPUSH", "l", "only");
        assertThat(str(run("LPOP", "l"))).isEqualTo("only");
        assertThat(integer(run("EXISTS", "l"))).isEqualTo(0);
        assertThat(simple(run("TYPE", "l"))).isEqualTo("none");
        assertThat(integer(run("LLEN", "l"))).isEqualTo(0);
    }

    @Test
    void lrangeHonoursNegativeAndOutOfRangeIndices() {
        run("RPUSH", "l", "a", "b", "c", "d", "e");
        assertThat(array(run("LRANGE", "l", "0", "-1"))).containsExactly("a", "b", "c", "d", "e");
        assertThat(array(run("LRANGE", "l", "-3", "-1"))).containsExactly("c", "d", "e");
        assertThat(array(run("LRANGE", "l", "1", "3"))).containsExactly("b", "c", "d");
        assertThat(array(run("LRANGE", "l", "-100", "100"))).containsExactly("a", "b", "c", "d", "e");
        assertThat(array(run("LRANGE", "l", "3", "1"))).isEmpty();   // start > stop
        assertThat(array(run("LRANGE", "l", "5", "10"))).isEmpty();  // start past the end
    }

    @Test
    void lrangeAndLlenOnAbsentKeyAreEmptyAndZero() {
        assertThat(array(run("LRANGE", "absent", "0", "-1"))).isEmpty();
        assertThat(integer(run("LLEN", "absent"))).isEqualTo(0);
    }

    @Test
    void typeReportsList() {
        run("RPUSH", "l", "x");
        assertThat(simple(run("TYPE", "l"))).isEqualTo("list");
    }

    @Test
    void listCommandsAgainstOtherTypesAreWrongType() {
        run("SET", "s", "v");
        assertThat(error(run("LPUSH", "s", "x"))).startsWith("WRONGTYPE");
        assertThat(error(run("RPUSH", "s", "x"))).startsWith("WRONGTYPE");
        assertThat(error(run("LPOP", "s"))).startsWith("WRONGTYPE");
        assertThat(error(run("RPOP", "s"))).startsWith("WRONGTYPE");
        assertThat(error(run("LLEN", "s"))).startsWith("WRONGTYPE");
        assertThat(error(run("LRANGE", "s", "0", "-1"))).startsWith("WRONGTYPE");

        run("ZADD", "z", "1", "a");
        assertThat(error(run("LPUSH", "z", "x"))).startsWith("WRONGTYPE");
        run("HSET", "h", "f", "v");
        assertThat(error(run("LPUSH", "h", "x"))).startsWith("WRONGTYPE");
    }

    @Test
    void otherTypeCommandsAgainstAListAreWrongType() {
        run("RPUSH", "l", "x");
        assertThat(error(run("GET", "l"))).startsWith("WRONGTYPE");
        assertThat(error(run("INCR", "l"))).startsWith("WRONGTYPE");
        assertThat(error(run("ZADD", "l", "1", "a"))).startsWith("WRONGTYPE");
        assertThat(error(run("ZRANGE", "l", "0", "-1"))).startsWith("WRONGTYPE");
        assertThat(error(run("HSET", "l", "f", "v"))).startsWith("WRONGTYPE");
    }

    @Test
    void setOverwritesAList() {
        run("RPUSH", "l", "x");
        assertThat(run("SET", "l", "v")).isInstanceOf(RespValue.SimpleString.class);
        assertThat(simple(run("TYPE", "l"))).isEqualTo("string");
        assertThat(str(run("GET", "l"))).isEqualTo("v");
    }

    @Test
    void arityErrorsAreReported() {
        assertThat(error(run("LPUSH", "l"))).contains("wrong number of arguments");
        assertThat(error(run("LPOP", "l", "extra"))).contains("wrong number of arguments");
        assertThat(error(run("LRANGE", "l", "0"))).contains("wrong number of arguments");
    }

    @Test
    void lrangeRejectsNonIntegerBounds() {
        run("RPUSH", "l", "a");
        assertThat(error(run("LRANGE", "l", "x", "1"))).contains("not an integer");
    }

    // --- The GC thesis: popped nodes are recycled, not allocated afresh ---

    @Test
    void poppedNodesAreReturnedToThePool() {
        ListNodePool pool = new ListNodePool();
        IntrusiveList list = new IntrusiveList(pool);

        list.rpush("a".getBytes(StandardCharsets.UTF_8));
        list.rpush("b".getBytes(StandardCharsets.UTF_8));
        assertThat(pool.poolSize()).isZero(); // nothing recycled yet

        list.lpop();
        list.rpop();
        assertThat(pool.poolSize()).isEqualTo(2); // both shells returned to the free list

        // A subsequent push reuses a pooled node rather than allocating — pool depth drops.
        list.rpush("c".getBytes(StandardCharsets.UTF_8));
        assertThat(pool.poolSize()).isEqualTo(1);
        assertThat(list.llen()).isEqualTo(1);
        assertThat(new String(list.lpop(), StandardCharsets.UTF_8)).isEqualTo("c");
    }

    @Test
    void recycledNodesNullOutTheirOldValue() {
        // Push-pop churn at a steady depth must not pin old byte[]s through pooled shells:
        // the pool nulls value/prev/next on release. We assert the list stays correct across churn.
        IntrusiveList list = new IntrusiveList();
        for (int round = 0; round < 50; round++) {
            list.rpush(("v" + round).getBytes(StandardCharsets.UTF_8));
            assertThat(new String(list.lpop(), StandardCharsets.UTF_8)).isEqualTo("v" + round);
            assertThat(list.llen()).isZero();
        }
    }
}
