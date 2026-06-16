package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code INCRBY key delta} → the value after adding {@code delta} (a missing key starts at 0). */
public final class IncrByCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'incrby' command");
        }
        long delta;
        try {
            delta = Long.parseLong(ctx.argString(2));
        } catch (NumberFormatException e) {
            return RespValue.error("ERR value is not an integer or out of range");
        }
        return NumericString.apply(ctx, ctx.argString(1), delta, false);
    }
}
