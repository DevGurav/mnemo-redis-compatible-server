package dev.devgurav.mnemo.net.resp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes a {@link RespValue} reply into RESP2 bytes. The switch is exhaustive over the sealed
 * {@link RespValue} hierarchy, so adding a new reply type is a compile error until handled here.
 */
public final class RespEncoder extends MessageToByteEncoder<RespValue> {

    private static final byte[] CRLF = {'\r', '\n'};

    @Override
    protected void encode(ChannelHandlerContext ctx, RespValue msg, ByteBuf out) {
        write(out, msg);
    }

    private void write(ByteBuf out, RespValue msg) {
        switch (msg) {
            case RespValue.SimpleString s -> {
                out.writeByte('+');
                out.writeCharSequence(s.value(), StandardCharsets.UTF_8);
                out.writeBytes(CRLF);
            }
            case RespValue.ErrorReply e -> {
                out.writeByte('-');
                out.writeCharSequence(e.message(), StandardCharsets.UTF_8);
                out.writeBytes(CRLF);
            }
            case RespValue.IntegerReply i -> {
                out.writeByte(':');
                out.writeCharSequence(Long.toString(i.value()), StandardCharsets.US_ASCII);
                out.writeBytes(CRLF);
            }
            case RespValue.BulkString b -> {
                byte[] v = b.value();
                if (v == null) {
                    out.writeCharSequence("$-1", StandardCharsets.US_ASCII);
                    out.writeBytes(CRLF);
                } else {
                    out.writeByte('$');
                    out.writeCharSequence(Integer.toString(v.length), StandardCharsets.US_ASCII);
                    out.writeBytes(CRLF);
                    out.writeBytes(v);
                    out.writeBytes(CRLF);
                }
            }
            case RespValue.ArrayReply a -> {
                List<RespValue> items = a.items();
                if (items == null) {
                    out.writeCharSequence("*-1", StandardCharsets.US_ASCII);
                    out.writeBytes(CRLF);
                } else {
                    out.writeByte('*');
                    out.writeCharSequence(Integer.toString(items.size()), StandardCharsets.US_ASCII);
                    out.writeBytes(CRLF);
                    for (RespValue item : items) {
                        write(out, item);
                    }
                }
            }
        }
    }
}
