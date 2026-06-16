package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code EXISTS key [key ...]} → how many of the given keys exist (counting duplicates). */
public final class ExistsCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() < 2) {
            return RespValue.error("ERR wrong number of arguments for 'exists' command");
        }
        int count = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (ctx.db().exists(ctx.argString(i))) count++;
        }
        return RespValue.integer(count);
    }
}
