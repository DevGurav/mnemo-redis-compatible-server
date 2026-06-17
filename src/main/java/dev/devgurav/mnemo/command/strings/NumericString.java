package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

import java.nio.charset.StandardCharsets;

/**
 * Shared logic for the integer-counter commands ({@code INCR}/{@code DECR}/{@code INCRBY}/
 * {@code DECRBY}). Redis stores the counter as its decimal-string value, so each operation parses
 * the current string, applies the delta with overflow checking, and writes the result back as a
 * decimal string.
 */
final class NumericString {

    private NumericString() {}

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";

    /**
     * Apply {@code operand} to the integer value at {@code key} (adding, or subtracting when
     * {@code subtract} is set) and store the decimal-string result. A missing key starts at 0.
     *
     * @return the new value as an integer reply, or an error reply (WRONGTYPE / not-an-integer /
     *         overflow)
     */
    static RespValue apply(CommandContext ctx, String key, long operand, boolean subtract) {
        if (ctx.db().isZSet(key) || ctx.db().isHash(key)) {
            return RespValue.error(WRONGTYPE);
        }

        byte[] current = ctx.db().get(key);
        long value;
        if (current == null) {
            value = 0;
        } else {
            try {
                value = Long.parseLong(new String(current, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not an integer or out of range");
            }
        }

        long result;
        try {
            result = subtract ? Math.subtractExact(value, operand) : Math.addExact(value, operand);
        } catch (ArithmeticException e) {
            return RespValue.error("ERR increment or decrement would overflow");
        }

        ctx.db().set(key, Long.toString(result).getBytes(StandardCharsets.UTF_8));
        return RespValue.integer(result);
    }
}
