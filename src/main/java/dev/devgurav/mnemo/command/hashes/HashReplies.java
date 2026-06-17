package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * Shared bits for the hash commands. A hash command is legal only when its key is absent or already
 * holds a hash; a string or sorted-set key is a {@code WRONGTYPE} error.
 */
final class HashReplies {

    private HashReplies() {}

    static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    /**
     * @return a {@code WRONGTYPE} error reply if {@code key} holds a non-hash value, or {@code null}
     *         if the key is a hash or does not exist (in which case the command may proceed).
     */
    static RespValue wrongTypeError(CommandContext ctx, String key) {
        if (ctx.db().isString(key) || ctx.db().isZSet(key)) {
            return RespValue.error(WRONGTYPE);
        }
        return null;
    }
}
