package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code DEL key [key ...]} → the number of keys actually removed. */
public final class DelCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() < 2) {
            return RespValue.error("ERR wrong number of arguments for 'del' command");
        }
        int removed = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (ctx.db().delete(ctx.argString(i))) removed++;
        }
        return RespValue.integer(removed);
    }
}
