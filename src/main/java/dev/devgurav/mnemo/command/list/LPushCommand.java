package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.list.IntrusiveList;

/**
 * {@code LPUSH key value [value ...]} → the list length after the push. Each value is prepended in
 * turn, so {@code LPUSH k a b c} leaves the list as {@code c b a} (matching Redis).
 */
public final class LPushCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() < 3) {
            return RespValue.error("ERR wrong number of arguments for 'lpush' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = ListReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        IntrusiveList list = ctx.db().listForWrite(key);
        for (int i = 2; i < ctx.argCount(); i++) {
            list.lpush(ctx.arg(i));
        }
        return RespValue.integer(list.llen());
    }
}
