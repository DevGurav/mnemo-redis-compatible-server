package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * Shared bits for the list commands. A list command is legal only when its key is absent or already
 * holds a list; a string, sorted-set, or hash key is a {@code WRONGTYPE} error.
 */
final class ListReplies {

    private ListReplies() {}

    static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    /**
     * @return a {@code WRONGTYPE} error reply if {@code key} holds a non-list value, or {@code null}
     *         if the key is a list or does not exist (in which case the command may proceed).
     */
    static RespValue wrongTypeError(CommandContext ctx, String key) {
        if (ctx.db().isString(key) || ctx.db().isZSet(key) || ctx.db().isHash(key)) {
            return RespValue.error(WRONGTYPE);
        }
        return null;
    }
}
