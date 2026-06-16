package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code INCR key} → the value after adding 1 (a missing key starts at 0). */
public final class IncrCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'incr' command");
        }
        return NumericString.apply(ctx, ctx.argString(1), 1, false);
    }
}
