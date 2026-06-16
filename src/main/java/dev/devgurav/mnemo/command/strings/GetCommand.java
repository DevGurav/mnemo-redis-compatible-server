package dev.devgurav.mnemo.command.strings;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code GET key} → the value, or a null bulk string if absent. */
public final class GetCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'get' command");
        }
        byte[] value = ctx.db().get(ctx.argString(1));
        return value == null ? RespValue.nullBulk() : RespValue.bulk(value);
    }
}
