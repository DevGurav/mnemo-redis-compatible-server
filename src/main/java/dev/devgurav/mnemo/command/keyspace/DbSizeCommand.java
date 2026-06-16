package dev.devgurav.mnemo.command.keyspace;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code DBSIZE} → the number of keys in the keyspace, across all value types. */
public final class DbSizeCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 1) {
            return RespValue.error("ERR wrong number of arguments for 'dbsize' command");
        }
        return RespValue.integer(ctx.db().size());
    }
}
