package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code SET key value} → {@code OK}. (Options like EX/NX/GET arrive in a later week.) */
public final class SetCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'set' command");
        }
        ctx.db().set(ctx.argString(1), ctx.arg(2));
        return RespValue.ok();
    }
}
