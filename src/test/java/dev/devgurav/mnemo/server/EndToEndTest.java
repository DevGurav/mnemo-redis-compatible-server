package dev.devgurav.mnemo.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real server on an ephemeral port and drives it over a raw socket with RESP bytes —
 * exercising the whole pipeline (Netty → decoder → dispatch → encoder → store). Uses the temporary
 * HashMapStore, so it is GREEN before you implement Dict.
 */
class EndToEndTest {

    private MnemoServer server;
    private int port;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new MnemoServer(new Config(0, false)); // ephemeral port, HashMapStore
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    @Test
    void pingSetGetExistsDel() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            send(out, "*1\r\n$4\r\nPING\r\n");
            assertThat(readLine(in)).isEqualTo("+PONG");

            send(out, "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
            assertThat(readLine(in)).isEqualTo("+OK");

            send(out, "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n");
            assertThat(readLine(in)).isEqualTo("$3");
            assertThat(readLine(in)).isEqualTo("bar");

            send(out, "*2\r\n$3\r\nGET\r\n$4\r\nnope\r\n");
            assertThat(readLine(in)).isEqualTo("$-1");

            send(out, "*2\r\n$6\r\nEXISTS\r\n$3\r\nfoo\r\n");
            assertThat(readLine(in)).isEqualTo(":1");

            send(out, "*2\r\n$3\r\nDEL\r\n$3\r\nfoo\r\n");
            assertThat(readLine(in)).isEqualTo(":1");

            send(out, "*2\r\n$6\r\nEXISTS\r\n$3\r\nfoo\r\n");
            assertThat(readLine(in)).isEqualTo(":0");
        }
    }

    private static void send(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads one CRLF-terminated line, returning it without the trailing CRLF. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                sb.setLength(sb.length() - 1); // drop trailing '\r'
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
        return sb.toString();
    }
}
