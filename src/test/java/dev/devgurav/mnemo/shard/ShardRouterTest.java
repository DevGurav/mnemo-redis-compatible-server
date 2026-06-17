package dev.devgurav.mnemo.shard;

import dev.devgurav.mnemo.server.Config;
import dev.devgurav.mnemo.server.MnemoServer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level tests for multi-shard routing via {@link ShardRouter}.
 *
 * <p>Each test boots a real {@link MnemoServer} with {@code shardCount=4} on an ephemeral port
 * and speaks raw RESP2 over a {@link Socket}, identical to the pattern used in EndToEndTest.
 */
class ShardRouterTest {

    // -------------------------------------------------------------------------
    // CRC-16 unit tests
    // -------------------------------------------------------------------------

    @Test
    void crc16EmptyKeyProducesZero() {
        assertThat(Crc16.compute(new byte[0])).isZero();
    }

    @Test
    void crc16KnownValues() {
        // Single-byte input: CRC-16/XMODEM of [0x01] equals the polynomial itself (0x1021).
        assertThat(Crc16.compute(new byte[]{0x01})).isEqualTo(0x1021);
        // Multi-byte: confirmed value from the same algorithm Redis uses in hash-slot computation.
        assertThat(Crc16.compute("foo".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xAF96);
    }

    @Test
    void crc16HashtagExtraction() {
        // {foo}:bar should hash the same as {foo}:baz — only "foo" inside braces is hashed.
        byte[] a = "{foo}:bar".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{foo}:baz".getBytes(StandardCharsets.UTF_8);
        assertThat(Crc16.compute(a)).isEqualTo(Crc16.compute(b));
    }

    @Test
    void crc16HashtagMatchesBareKey() {
        // {foo}:suffix should hash to the same value as "foo" alone.
        int bareHash    = Crc16.compute("foo".getBytes(StandardCharsets.UTF_8));
        int taggedHash  = Crc16.compute("{foo}:anything".getBytes(StandardCharsets.UTF_8));
        assertThat(bareHash).isEqualTo(taggedHash);
    }

    @Test
    void crc16EmptyBracesHashesWholeKey() {
        // "{}" means no hashtag; the entire key "{}" is hashed.
        int wholeKey = Crc16.compute("{}foo".getBytes(StandardCharsets.UTF_8));
        int bare     = Crc16.compute("foo".getBytes(StandardCharsets.UTF_8));
        assertThat(wholeKey).isNotEqualTo(bare);
    }

    // -------------------------------------------------------------------------
    // ShardRouter distribution test
    // -------------------------------------------------------------------------

    @Test
    void routerDistributesKeysAcrossAllShards() {
        int n = 4;

        // Count how many of 1000 keys land on each shard using CRC16 % N (same formula as ShardRouter).
        int[] counts = new int[n];
        for (int i = 0; i < 1000; i++) {
            byte[] key = ("key:" + i).getBytes(StandardCharsets.UTF_8);
            int crc = Crc16.compute(key);
            counts[crc % n]++;
        }

        // With a good hash function each shard should receive ≥50 keys out of 1000.
        for (int i = 0; i < n; i++) {
            assertThat(counts[i]).as("shard %d should receive at least 50 of 1000 keys", i)
                    .isGreaterThan(50);
        }
    }

    // -------------------------------------------------------------------------
    // Multi-shard end-to-end tests
    // -------------------------------------------------------------------------

    @Test
    void setGetWorkAcrossMultipleShards() throws Exception {
        Config cfg = new Config(0, false, 0, dev.devgurav.mnemo.store.evict.EvictionPolicy.NOEVICTION,
                null, 4);
        try (MnemoServer server = new MnemoServer(cfg)) {
            int port = server.start();

            try (Socket sock = new Socket("127.0.0.1", port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {

                // These keys will hash to different shards — all should be readable back.
                String[] keys = {"alpha", "bravo", "charlie", "delta", "echo"};
                for (String k : keys) {
                    send(out, "SET", k, "val-" + k);
                    assertThat(readLine(in)).isEqualTo("+OK");
                }
                for (String k : keys) {
                    send(out, "GET", k);
                    assertThat(readLine(in)).isEqualTo("$" + ("val-" + k).length());
                    assertThat(readLine(in)).isEqualTo("val-" + k);
                }
            }
        }
    }

    @Test
    void dbSizeSumsAllShards() throws Exception {
        Config cfg = new Config(0, false, 0, dev.devgurav.mnemo.store.evict.EvictionPolicy.NOEVICTION,
                null, 4);
        try (MnemoServer server = new MnemoServer(cfg)) {
            int port = server.start();

            try (Socket sock = new Socket("127.0.0.1", port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {

                // Insert 8 keys — likely spread across multiple shards.
                for (int i = 0; i < 8; i++) {
                    send(out, "SET", "k" + i, "v");
                    assertThat(readLine(in)).isEqualTo("+OK");
                }

                send(out, "DBSIZE");
                String dbsizeLine = readLine(in);
                assertThat(dbsizeLine).startsWith(":");
                int total = Integer.parseInt(dbsizeLine.substring(1));
                assertThat(total).isEqualTo(8);
            }
        }
    }

    @Test
    void flushDbClearsAllShards() throws Exception {
        Config cfg = new Config(0, false, 0, dev.devgurav.mnemo.store.evict.EvictionPolicy.NOEVICTION,
                null, 4);
        try (MnemoServer server = new MnemoServer(cfg)) {
            int port = server.start();

            try (Socket sock = new Socket("127.0.0.1", port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {

                for (int i = 0; i < 8; i++) {
                    send(out, "SET", "flush" + i, "v");
                    readLine(in); // discard OK
                }

                send(out, "FLUSHDB");
                assertThat(readLine(in)).isEqualTo("+OK");

                send(out, "DBSIZE");
                assertThat(readLine(in)).isEqualTo(":0");
            }
        }
    }

    @Test
    void hashtagCoLocatesKeysOnSameShard() {
        // Both keys share the {order} hashtag; they should map to the same shard index.
        int n = 8;
        byte[] key1 = "{order}:items".getBytes(StandardCharsets.UTF_8);
        byte[] key2 = "{order}:total".getBytes(StandardCharsets.UTF_8);
        assertThat(Crc16.compute(key1) % n).isEqualTo(Crc16.compute(key2) % n);
    }

    // -------------------------------------------------------------------------
    // RESP helpers (identical to EndToEndTest)
    // -------------------------------------------------------------------------

    private static void send(PrintWriter out, String... parts) {
        out.print("*" + parts.length + "\r\n");
        for (String p : parts) {
            byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
            out.print("$" + bytes.length + "\r\n" + p + "\r\n");
        }
        out.flush();
    }

    private static String readLine(BufferedReader in) throws IOException {
        return in.readLine();
    }
}
