package dev.devgurav.mnemo.net.resp;

import dev.devgurav.mnemo.net.ParsedCommand;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes inbound RESP2 bytes into {@link ParsedCommand} POJOs and emits them downstream.
 *
 * <p>Thread-boundary contract (architecture-spec.md §2): every argument byte is copied from the
 * pooled {@code ByteBuf} into a plain {@code byte[]} before {@link ParsedCommand} is constructed.
 * No {@code ByteBuf} reference escapes this handler. Netty retains and manages the cumulation
 * buffer internally; downstream pipeline stages and the shard executor never see it.
 *
 * <p>Supports:
 * <ul>
 *   <li>RESP multibulk ({@code *N\r\n$L\r\n<bytes>\r\n…}) — the format sent by {@code redis-cli},
 *       Jedis, and any RESP2-compliant client.</li>
 *   <li>Inline ({@code GET foo\r\n}) — telnet-style, useful for manual debugging.</li>
 * </ul>
 *
 * <p>When a complete command is not yet buffered the reader index is reset and Netty waits for
 * more bytes before calling {@code decode} again.
 */
public final class RespDecoder extends ByteToMessageDecoder {

    private static final int MAX_INLINE_BYTES = 64 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        byte[][] args = parseArgs(in);
        if (args == null) {
            in.resetReaderIndex(); // incomplete frame — wait for more bytes
            return;
        }
        if (args.length == 0) return; // empty inline line; ignore silently
        out.add(new ParsedCommand(args, ctx));
    }

    // --- Parsing ---

    private byte[][] parseArgs(ByteBuf in) {
        if (!in.isReadable()) return null;
        return in.getByte(in.readerIndex()) == '*'
                ? parseMultibulk(in)
                : parseInline(in);
    }

    private byte[][] parseMultibulk(ByteBuf in) {
        String header = readLine(in);
        if (header == null) return null;

        int count = Integer.parseInt(header.substring(1).trim());
        if (count <= 0) return new byte[0][];

        byte[][] args = new byte[count][];
        for (int i = 0; i < count; i++) {
            String lenLine = readLine(in);
            if (lenLine == null) return null;
            if (lenLine.isEmpty() || lenLine.charAt(0) != '$') {
                throw new IllegalArgumentException("Protocol error: expected '$', got: " + lenLine);
            }
            int len = Integer.parseInt(lenLine.substring(1).trim());
            if (len < 0) {
                args[i] = null; // null bulk string
                continue;
            }
            if (in.readableBytes() < len + 2) return null; // need payload + CRLF
            args[i] = new byte[len];
            in.readBytes(args[i]);
            in.skipBytes(2); // consume trailing CRLF
        }
        return args;
    }

    private byte[][] parseInline(ByteBuf in) {
        String line = readLine(in);
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return new byte[0][];
        String[] tokens = trimmed.split("\\s+");
        byte[][] args = new byte[tokens.length][];
        for (int i = 0; i < tokens.length; i++) {
            args[i] = tokens[i].getBytes(StandardCharsets.UTF_8);
        }
        return args;
    }

    /**
     * Scans for the next {@code \r\n} in {@code in} without advancing the reader index until a
     * complete line is found.
     *
     * @return the line content (excluding {@code \r\n}), or {@code null} if no CRLF is buffered.
     */
    private String readLine(ByteBuf in) {
        int start  = in.readerIndex();
        int writer = in.writerIndex();
        for (int i = start; i < writer - 1; i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') {
                int lineLen = i - start;
                if (lineLen > MAX_INLINE_BYTES) {
                    throw new IllegalArgumentException("Protocol error: line exceeds max length");
                }
                byte[] b = new byte[lineLen];
                in.readBytes(b);
                in.skipBytes(2); // CRLF
                return new String(b, StandardCharsets.UTF_8);
            }
        }
        if (writer - start > MAX_INLINE_BYTES) {
            throw new IllegalArgumentException("Protocol error: line exceeds max length");
        }
        return null;
    }
}
