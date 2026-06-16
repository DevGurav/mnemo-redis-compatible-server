package dev.devgurav.mnemo.net.resp;

import dev.devgurav.mnemo.net.ParsedCommand;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codec tests using Netty's {@link EmbeddedChannel} — no sockets, pure in-process pipeline.
 *
 * <p>{@link RespDecoder} now emits {@link ParsedCommand} POJOs (all bytes copied out of the
 * {@code ByteBuf} before hand-off). Tests verify the decoded arg arrays, not the RESP value
 * model. The encoder tests are unaffected.
 */
class RespCodecTest {

    // --- Decoder tests ---

    @Test
    void decodesMultibulkCommand() {
        EmbeddedChannel ch = new EmbeddedChannel(new RespDecoder());
        ch.writeInbound(buf("*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n"));

        ParsedCommand cmd = ch.readInbound();
        assertThat(cmd).isNotNull();
        assertThat(cmd.argCount()).isEqualTo(2);
        assertThat(text(cmd.args()[0])).isEqualTo("GET");
        assertThat(text(cmd.args()[1])).isEqualTo("foo");
        ch.finishAndReleaseAll();
    }

    @Test
    void decodesInlineCommand() {
        EmbeddedChannel ch = new EmbeddedChannel(new RespDecoder());
        ch.writeInbound(buf("PING\r\n"));

        ParsedCommand cmd = ch.readInbound();
        assertThat(cmd).isNotNull();
        assertThat(cmd.argCount()).isEqualTo(1);
        assertThat(text(cmd.args()[0])).isEqualTo("PING");
        ch.finishAndReleaseAll();
    }

    @Test
    void waitsForAnIncompleteCommandThenDecodesIt() {
        EmbeddedChannel ch = new EmbeddedChannel(new RespDecoder());

        ch.writeInbound(buf("*2\r\n$3\r\nGET\r\n$3\r\nfo")); // bulk value truncated
        Object nothingYet = ch.readInbound();
        assertThat(nothingYet).isNull();

        ch.writeInbound(buf("o\r\n")); // delivers the final byte + CRLF
        ParsedCommand cmd = ch.readInbound();
        assertThat(cmd).isNotNull();
        assertThat(text(cmd.args()[1])).isEqualTo("foo");
        ch.finishAndReleaseAll();
    }

    @Test
    void decodesMultiArgCommand() {
        EmbeddedChannel ch = new EmbeddedChannel(new RespDecoder());
        ch.writeInbound(buf("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n"));

        ParsedCommand cmd = ch.readInbound();
        assertThat(cmd).isNotNull();
        assertThat(cmd.argCount()).isEqualTo(3);
        assertThat(text(cmd.args()[0])).isEqualTo("SET");
        assertThat(text(cmd.args()[1])).isEqualTo("foo");
        assertThat(text(cmd.args()[2])).isEqualTo("bar");
        ch.finishAndReleaseAll();
    }

    // --- Encoder tests (RespValue model, unchanged) ---

    @Test
    void encodesEachReplyType() {
        assertThat(encode(RespValue.ok())).isEqualTo("+OK\r\n");
        assertThat(encode(RespValue.pong())).isEqualTo("+PONG\r\n");
        assertThat(encode(RespValue.error("ERR bad"))).isEqualTo("-ERR bad\r\n");
        assertThat(encode(RespValue.integer(42))).isEqualTo(":42\r\n");
        assertThat(encode(RespValue.bulk("hi".getBytes(StandardCharsets.UTF_8)))).isEqualTo("$2\r\nhi\r\n");
        assertThat(encode(RespValue.nullBulk())).isEqualTo("$-1\r\n");
        assertThat(encode(RespValue.emptyArray())).isEqualTo("*0\r\n");
    }

    // --- Helpers ---

    private static String encode(RespValue value) {
        EmbeddedChannel ch = new EmbeddedChannel(new RespEncoder());
        ch.writeOutbound(value);
        ByteBuf out = ch.readOutbound();
        String s = out.toString(StandardCharsets.UTF_8);
        out.release();
        ch.finishAndReleaseAll();
        return s;
    }

    private static ByteBuf buf(String s) {
        return Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
