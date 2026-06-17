package dev.devgurav.mnemo.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-recovery test: write keys through a live server that has AOF enabled, shut the server
 * down, boot a fresh instance from the same AOF file, and assert the keys are still readable.
 *
 * <p>Uses ephemeral ports and a JUnit {@code @TempDir} so it is self-contained and leaves no
 * state behind. Plumbing test — runs in {@code ./gradlew test}.
 */
class AofPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void keysSurviveRestartViaAof() throws Exception {
        Path aofFile = tempDir.resolve("mnemo.aof");
        Config cfg = new Config(0, false, 0, dev.devgurav.mnemo.store.evict.EvictionPolicy.NOEVICTION,
                aofFile.toString());

        // Phase 1: start server, write data, shut down cleanly.
        int port;
        try (MnemoServer s1 = new MnemoServer(cfg)) {
            port = s1.start();
            try (Socket sock = connect(port)) {
                send(sock, "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
                assertThat(readLine(sock)).isEqualTo("+OK");

                send(sock, "*3\r\n$3\r\nSET\r\n$5\r\nhello\r\n$5\r\nworld\r\n");
                assertThat(readLine(sock)).isEqualTo("+OK");

                send(sock, "*4\r\n$4\r\nHSET\r\n$6\r\nmyhash\r\n$5\r\nfield\r\n$5\r\nvalue\r\n");
                assertThat(readLine(sock)).isEqualTo(":1");

                send(sock, "*3\r\n$5\r\nLPUSH\r\n$6\r\nmylist\r\n$4\r\nitem\r\n");
                assertThat(readLine(sock)).isEqualTo(":1");
            }
        } // MnemoServer.close() flushes and closes the AofWriter

        // AOF file must be non-empty after the writes.
        assertThat(aofFile).exists();
        assertThat(aofFile.toFile().length()).isGreaterThan(0);

        // Phase 2: fresh server instance from the same AOF — replay restores state before bind.
        try (MnemoServer s2 = new MnemoServer(cfg)) {
            port = s2.start();
            try (Socket sock = connect(port)) {
                send(sock, "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n");
                assertThat(readLine(sock)).isEqualTo("$3");
                assertThat(readLine(sock)).isEqualTo("bar");

                send(sock, "*2\r\n$3\r\nGET\r\n$5\r\nhello\r\n");
                assertThat(readLine(sock)).isEqualTo("$5");
                assertThat(readLine(sock)).isEqualTo("world");

                send(sock, "*3\r\n$4\r\nHGET\r\n$6\r\nmyhash\r\n$5\r\nfield\r\n");
                assertThat(readLine(sock)).isEqualTo("$5");
                assertThat(readLine(sock)).isEqualTo("value");

                send(sock, "*2\r\n$4\r\nLLEN\r\n$6\r\nmylist\r\n");
                assertThat(readLine(sock)).isEqualTo(":1");
            }
        }
    }

    @Test
    void deletedKeyDoesNotSurviveRestart() throws Exception {
        Path aofFile = tempDir.resolve("mnemo2.aof");
        Config cfg = new Config(0, false, 0, dev.devgurav.mnemo.store.evict.EvictionPolicy.NOEVICTION,
                aofFile.toString());

        try (MnemoServer s1 = new MnemoServer(cfg)) {
            int port = s1.start();
            try (Socket sock = connect(port)) {
                send(sock, "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n");
                assertThat(readLine(sock)).isEqualTo("+OK");

                send(sock, "*2\r\n$3\r\nDEL\r\n$3\r\nkey\r\n");
                assertThat(readLine(sock)).isEqualTo(":1");
            }
        }

        try (MnemoServer s2 = new MnemoServer(cfg)) {
            int port = s2.start();
            try (Socket sock = connect(port)) {
                send(sock, "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");
                assertThat(readLine(sock)).isEqualTo("$-1"); // nil — key was deleted and replayed
            }
        }
    }

    // --- Socket helpers (same pattern as EndToEndTest) ---

    private static Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5_000);
        return s;
    }

    private static void send(Socket sock, String s) throws IOException {
        OutputStream out = sock.getOutputStream();
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(Socket sock) throws IOException {
        InputStream in = sock.getInputStream();
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
        return sb.toString();
    }
}
