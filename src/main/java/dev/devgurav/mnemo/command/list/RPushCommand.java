package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.list.IntrusiveList;

/**
 * {@code RPUSH key value [value ...]} → the list length after the push. Each value is appended in
 * turn, so {@code RPUSH k a b c} leaves the list as {@code a b c} (matching Redis).
 */
public final class RPushCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() < 3) {
            return RespValue.error("ERR wrong number of arguments for 'rpush' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = ListReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        IntrusiveList list = ctx.db().listForWrite(key);
        for (int i = 2; i < ctx.argCount(); i++) {
            list.rpush(ctx.arg(i));
        }
        return RespValue.integer(list.llen());
    }
}
