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
 * Drives {@code KEYS pattern} through the real {@link CommandRegistry}. Plumbing test — green by
 * default. Covers the glob-pattern metacharacters ({@code *}, {@code ?}, {@code [charset]},
 * {@code [^charset]}) and the cross-namespace scan (strings, zsets, hashes, lists).
 */
class KeysCommandTest {

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

    private static List<String> keys(RespValue v) {
        List<String> result = new ArrayList<>();
        for (RespValue item : ((RespValue.ArrayReply) v).items()) {
            result.add(new String(((RespValue.BulkString) item).value(), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String error(RespValue v) { return ((RespValue.ErrorReply) v).message(); }

    // --- basic ---

    @Test
    void emptyKeyspaceReturnsEmptyArray() {
        assertThat(keys(run("KEYS", "*"))).isEmpty();
    }

    @Test
    void starPatternReturnsAllKeys() {
        run("SET", "foo", "1");
        run("SET", "bar", "2");
        run("SET", "baz", "3");
        assertThat(keys(run("KEYS", "*"))).containsExactlyInAnyOrder("foo", "bar", "baz");
    }

    @Test
    void keysSpansAllFourNamespaces() {
        run("SET", "s", "v");
        run("ZADD", "z", "1", "m");
        run("HSET", "h", "f", "v");
        run("LPUSH", "l", "x");
        assertThat(keys(run("KEYS", "*"))).containsExactlyInAnyOrder("s", "z", "h", "l");
    }

    // --- glob patterns ---

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        run("SET", "hello", "1");
        run("SET", "hallo", "1");
        run("SET", "hxllo", "1");
        run("SET", "hllo", "1");   // 4 chars — should NOT match h?llo
        run("SET", "world", "1");
        assertThat(keys(run("KEYS", "h?llo")))
                .containsExactlyInAnyOrder("hello", "hallo", "hxllo");
    }

    @Test
    void starMatchesZeroOrMoreCharacters() {
        run("SET", "hello", "1");
        run("SET", "hallo", "1");
        run("SET", "hxllo", "1");
        run("SET", "hllo", "1");
        run("SET", "world", "1");
        assertThat(keys(run("KEYS", "h*llo")))
                .containsExactlyInAnyOrder("hello", "hallo", "hxllo", "hllo");
    }

    @Test
    void prefixStarPattern() {
        run("SET", "user:1", "a");
        run("SET", "user:2", "b");
        run("SET", "session:1", "c");
        assertThat(keys(run("KEYS", "user:*")))
                .containsExactlyInAnyOrder("user:1", "user:2");
    }

    @Test
    void characterClassMatchesListedChars() {
        run("SET", "hello", "1");
        run("SET", "hallo", "1");
        run("SET", "hillo", "1");
        assertThat(keys(run("KEYS", "h[ae]llo")))
                .containsExactlyInAnyOrder("hello", "hallo");
    }

    @Test
    void negatedCharacterClassExcludesListedChars() {
        run("SET", "hello", "1");
        run("SET", "hallo", "1");
        run("SET", "hillo", "1");
        assertThat(keys(run("KEYS", "h[^ae]llo")))
                .containsExactlyInAnyOrder("hillo");
    }

    @Test
    void characterClassWithRange() {
        run("SET", "key1", "1");
        run("SET", "key2", "1");
        run("SET", "key9", "1");
        run("SET", "keyA", "1");
        assertThat(keys(run("KEYS", "key[1-3]")))
                .containsExactlyInAnyOrder("key1", "key2");
    }

    @Test
    void noMatchReturnsEmptyArray() {
        run("SET", "foo", "1");
        assertThat(keys(run("KEYS", "bar*"))).isEmpty();
    }

    @Test
    void exactMatchPattern() {
        run("SET", "exact", "1");
        run("SET", "exactmore", "1");
        assertThat(keys(run("KEYS", "exact"))).containsExactly("exact");
    }

    // --- arity ---

    @Test
    void arityErrorWhenPatternMissing() {
        assertThat(error(run("KEYS"))).contains("wrong number of arguments");
    }

    @Test
    void arityErrorWhenTooManyArgs() {
        assertThat(error(run("KEYS", "*", "extra"))).contains("wrong number of arguments");
    }
}
