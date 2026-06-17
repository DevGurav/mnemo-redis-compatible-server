package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Dict;

/**
 * {@code HDEL key field [field ...]} → the number of fields removed. When the last field is removed
 * the key itself is deleted, matching Redis (an empty hash does not linger).
 */
public final class HDelCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() < 3) {
            return RespValue.error("ERR wrong number of arguments for 'hdel' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = HashReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        Dict hash = ctx.db().hash(key);
        if (hash == null) return RespValue.integer(0);

        int removed = 0;
        for (int i = 2; i < ctx.argCount(); i++) {
            if (hash.remove(ctx.argString(i))) removed++;
        }
        if (hash.size() == 0) {
            ctx.db().delete(key); // drop the now-empty hash from the keyspace
        }
        return RespValue.integer(removed);
    }
}
