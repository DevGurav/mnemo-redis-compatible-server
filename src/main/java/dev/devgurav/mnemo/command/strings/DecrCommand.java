package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code DECR key} → the value after subtracting 1 (a missing key starts at 0). */
public final class DecrCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'decr' command");
        }
        return NumericString.apply(ctx, ctx.argString(1), 1, true);
    }
}
