package dev.devgurav.mnemo.net.resp;

import java.util.List;

/**
 * RESP2 value model. A sealed interface with nested record subtypes — the {@code permits}
 * clause is omitted because all subtypes are declared in this file.
 *
 * <p>This is the wire vocabulary Mnemo speaks: simple strings ({@code +OK}), errors ({@code -ERR}),
 * integers ({@code :1}), bulk strings ({@code $3\r\nfoo}), and arrays ({@code *2...}).
 */
public sealed interface RespValue {

    record SimpleString(String value) implements RespValue {}

    record ErrorReply(String message) implements RespValue {}

    record IntegerReply(long value) implements RespValue {}

    /** {@code value == null} encodes a RESP null bulk string ({@code $-1\r\n}). */
    record BulkString(byte[] value) implements RespValue {}

    /** {@code items == null} encodes a RESP null array ({@code *-1\r\n}). */
    record ArrayReply(List<RespValue> items) implements RespValue {}

    // --- Convenience factories ---

    static RespValue ok() { return new SimpleString("OK"); }

    static RespValue pong() { return new SimpleString("PONG"); }

    static RespValue error(String message) { return new ErrorReply(message); }

    static RespValue integer(long value) { return new IntegerReply(value); }

    static RespValue bulk(byte[] value) { return new BulkString(value); }

    static RespValue nullBulk() { return new BulkString(null); }

    static RespValue array(List<RespValue> items) { return new ArrayReply(items); }

    static RespValue emptyArray() { return new ArrayReply(List.of()); }
}
