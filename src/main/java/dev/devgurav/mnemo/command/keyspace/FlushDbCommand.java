package dev.devgurav.mnemo.command.keyspace;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code FLUSHDB} → empties the keyspace. (The {@code ASYNC}/{@code SYNC} option is a later week.) */
public final class FlushDbCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 1) {
            return RespValue.error("ERR wrong number of arguments for 'flushdb' command");
        }
        ctx.db().flush();
        return RespValue.ok();
    }
}
