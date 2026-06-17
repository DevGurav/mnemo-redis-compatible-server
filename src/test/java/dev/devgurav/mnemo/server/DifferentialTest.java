package dev.devgurav.mnemo.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle / differential tests: run identical command sequences against a live Mnemo instance and a
 * real {@code redis:7-alpine} instance (via Testcontainers) and assert their RESP responses are
 * identical. Guards against any Mnemo command returning a response that diverges from the Redis
 * specification.
 *
 * <p>Run with: {@code ./gradlew differentialTest}. Requires Docker; excluded from the default
 * {@code test} task to keep CI portable on machines without a container daemon.
 */
@Testcontainers
@Tag("differential")
class DifferentialTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static MnemoServer mnemo;
    private static int mnemoPort;

    @BeforeAll
    static void startMnemo() throws InterruptedException {
        mnemo = new MnemoServer(new Config(0, false)); // ephemeral port, HashMapStore
        mnemoPort = mnemo.start();
    }

    @AfterAll
    static void stopMnemo() {
        if (mnemo != null) mnemo.close();
    }

    @BeforeEach
    void flushBoth() throws IOException {
        // reset both stores to empty before each test
        send(mnemoPort, "FLUSHALL");
        send(redisPort(), "FLUSHALL");
    }

    // -------------------------------------------------------------------------
    // Test scenarios
    // -------------------------------------------------------------------------

    @Test
    void ping() throws IOException {
        assertSame("PING");
    }

    @Test
    void stringSetGetDelExists() throws IOException {
        assertSame("SET", "foo", "bar");
        assertSame("GET", "foo");
        assertSame("GET", "nosuchkey");
        assertSame("EXISTS", "foo");
        assertSame("DEL", "foo");
        assertSame("EXISTS", "foo");
        assertSame("DEL", "nosuchkey");
    }

    @Test
    void integerOperations() throws IOException {
        assertSame("SET", "ctr", "10");
        assertSame("INCR", "ctr");
        assertSame("INCRBY", "ctr", "5");
        assertSame("DECR", "ctr");
        assertSame("DECRBY", "ctr", "3");
    }

    @Test
    void wrongTypeErrorMatchesRedis() throws IOException {
        assertSame("LPUSH", "mylist", "a");
        assertSame("GET", "mylist");         // WRONGTYPE
        assertSame("ZADD", "mylist", "1", "m"); // WRONGTYPE
        assertSame("HSET", "mylist", "f", "v"); // WRONGTYPE

        assertSame("SET", "mystr", "v");
        assertSame("LPUSH", "mystr", "x");  // WRONGTYPE
        assertSame("ZADD", "mystr", "1", "m"); // WRONGTYPE
    }

    @Test
    void hashCommands() throws IOException {
        assertSame("HSET", "user", "name", "Alice", "age", "30");
        assertSame("HGET", "user", "name");
        assertSame("HGET", "user", "age");
        assertSame("HGET", "user", "missing");
        assertSame("HLEN", "user");
        assertSame("HDEL", "user", "age");
        assertSame("HGET", "user", "age");
        assertSame("HLEN", "user");
        assertSameHash("HGETALL", "user");
    }

    @Test
    void listCommands() throws IOException {
        assertSame("RPUSH", "items", "a", "b", "c");
        assertSame("LLEN", "items");
        assertSame("LRANGE", "items", "0", "-1");
        assertSame("LRANGE", "items", "1", "2");
        assertSame("LPOP", "items");
        assertSame("RPOP", "items");
        assertSame("LRANGE", "items", "0", "-1");
        assertSame("LLEN", "items");
    }

    @Test
    void sortedSetCommands() throws IOException {
        assertSame("ZADD", "scores", "1.0", "alice", "2.0", "bob", "3.0", "carol");
        assertSame("ZRANK", "scores", "alice");
        assertSame("ZRANK", "scores", "bob");
        assertSame("ZRANK", "scores", "missing");
        assertSame("ZRANGE", "scores", "0", "-1");
        assertSame("ZRANGE", "scores", "0", "1");
    }

    @Test
    void keysGlobPatterns() throws IOException {
        assertSame("SET", "hello", "1");
        assertSame("SET", "hallo", "1");
        assertSame("SET", "hxllo", "1");
        assertSame("SET", "world", "1");

        assertSameElements("KEYS", "*");
        assertSameElements("KEYS", "h?llo");
        assertSameElements("KEYS", "h*llo");
        assertSameElements("KEYS", "h[ae]llo");
        assertSameElements("KEYS", "world");
        assertSameElements("KEYS", "nomatch*");
    }

    @Test
    void keysCrossNamespace() throws IOException {
        send(mnemoPort, "SET", "s", "v");
        send(redisPort(), "SET", "s", "v");
        send(mnemoPort, "ZADD", "z", "1", "m");
        send(redisPort(), "ZADD", "z", "1", "m");
        send(mnemoPort, "HSET", "h", "f", "v");
        send(redisPort(), "HSET", "h", "f", "v");
        send(mnemoPort, "LPUSH", "l", "x");
        send(redisPort(), "LPUSH", "l", "x");

        assertSameElements("KEYS", "*");
    }

    @Test
    void typeCommand() throws IOException {
        assertSame("SET", "s", "v");
        assertSame("ZADD", "z", "1", "m");
        assertSame("HSET", "h", "f", "v");
        assertSame("LPUSH", "l", "x");

        assertSame("TYPE", "s");
        assertSame("TYPE", "z");
        assertSame("TYPE", "h");
        assertSame("TYPE", "l");
        assertSame("TYPE", "none");
    }

    @Test
    void dbsizeAndFlush() throws IOException {
        assertSame("DBSIZE");
        assertSame("SET", "a", "1");
        assertSame("SET", "b", "2");
        assertSame("DBSIZE");
        assertSame("FLUSHDB");
        assertSame("DBSIZE");
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    /** Send {@code args} to both Mnemo and Redis; assert they return the identical RESP string. */
    private void assertSame(String... args) throws IOException {
        String m = send(mnemoPort, args);
        String r = send(redisPort(), args);
        assertThat(m)
                .as("RESP mismatch for %s — Mnemo: %s  Redis: %s", Arrays.toString(args), m, r)
                .isEqualTo(r);
    }

    /**
     * Send an array-returning command to both; compare element sets (order-independent).
     * Used for {@code KEYS} where the key order is unspecified.
     */
    private void assertSameElements(String... args) throws IOException {
        List<String> m = parseElements(send(mnemoPort, args));
        List<String> r = parseElements(send(redisPort(), args));
        Collections.sort(m);
        Collections.sort(r);
        assertThat(m)
                .as("Element-set mismatch for %s", Arrays.toString(args))
                .isEqualTo(r);
    }

    /**
     * Send {@code HGETALL}-style commands to both; compare as field→value maps (order-independent).
     */
    private void assertSameHash(String... args) throws IOException {
        Map<String, String> m = parseMap(send(mnemoPort, args));
        Map<String, String> r = parseMap(send(redisPort(), args));
        assertThat(m)
                .as("Hash mismatch for %s", Arrays.toString(args))
                .isEqualTo(r);
    }

    // -------------------------------------------------------------------------
    // Wire helpers
    // -------------------------------------------------------------------------

    private int redisPort() { return redis.getMappedPort(6379); }

    /** Open a fresh connection, send {@code args} as a RESP multibulk, read one RESP reply. */
    private static String send(int port, String... args) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            out.write(toRespBytes(args));
            out.flush();
            return readResp(s.getInputStream());
        }
    }

    private static byte[] toRespBytes(String... args) {
        StringBuilder sb = new StringBuilder().append('*').append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Read one complete RESP value from {@code in}.
     * Array elements are joined with {@code '\n'} so the result is a single comparable string.
     */
    private static String readResp(InputStream in) throws IOException {
        String header = readLine(in);
        if (header.isEmpty()) return header;
        char type = header.charAt(0);
        return switch (type) {
            case '+', '-', ':' -> header;
            case '$' -> {
                int len = Integer.parseInt(header.substring(1));
                if (len < 0) yield header; // null bulk string
                byte[] buf = in.readNBytes(len + 2); // data + CRLF
                yield header + "\n" + new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int n = Integer.parseInt(header.substring(1));
                if (n < 0) yield header; // null array
                StringBuilder sb = new StringBuilder(header);
                for (int i = 0; i < n; i++) sb.append("\n").append(readResp(in));
                yield sb.toString();
            }
            default -> header;
        };
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') { sb.setLength(sb.length() - 1); return sb.toString(); }
            sb.append((char) c);
            prev = c;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // RESP response parsers
    // -------------------------------------------------------------------------

    /**
     * Parse an array RESP string ({@code "*N\n$len\nval\n..."}) into a flat list of element values.
     * Non-array responses are wrapped in a singleton list.
     */
    private static List<String> parseElements(String resp) {
        if (!resp.startsWith("*")) return new ArrayList<>(List.of(resp));
        String[] parts = resp.split("\n");
        int n = Integer.parseInt(parts[0].substring(1));
        if (n <= 0) return new ArrayList<>();
        List<String> result = new ArrayList<>(n);
        int i = 1;
        while (i < parts.length) {
            if (parts[i].startsWith("$") && i + 1 < parts.length) {
                result.add(parts[i + 1]);
                i += 2;
            } else {
                result.add(parts[i]);
                i++;
            }
        }
        return result;
    }

    /** Parse an interleaved field-value array ({@code HGETALL}) into a {@link Map}. */
    private static Map<String, String> parseMap(String resp) {
        List<String> elems = parseElements(resp);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < elems.size(); i += 2) {
            map.put(elems.get(i), elems.get(i + 1));
        }
        return map;
    }
}
