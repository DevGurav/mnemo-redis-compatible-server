package dev.devgurav.mnemo.command.keyspace;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code FLUSHALL} → empties the keyspace. Single-node with one logical database, so it behaves the
 * same as {@code FLUSHDB} today; it stays a distinct command for client compatibility.
 */
public final class FlushAllCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 1) {
            return RespValue.error("ERR wrong number of arguments for 'flushall' command");
        }
        ctx.db().flush();
        return RespValue.ok();
    }
}
