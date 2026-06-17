package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.list.IntrusiveList;

/**
 * {@code LPOP key} → the head element as a bulk string, or a null bulk string when the key is
 * absent or empty. When the pop empties the list the key is deleted, matching Redis (an empty list
 * does not linger).
 */
public final class LPopCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'lpop' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = ListReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        IntrusiveList list = ctx.db().list(key);
        if (list == null) return RespValue.nullBulk();

        byte[] value = list.lpop();
        if (list.llen() == 0) {
            ctx.db().delete(key); // drop the now-empty list from the keyspace
        }
        return value == null ? RespValue.nullBulk() : RespValue.bulk(value);
    }
}
