package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code PTTL key} — returns the remaining TTL in milliseconds.
 * {@code :-1} if the key exists but has no TTL; {@code :-2} if the key does not exist.
 */
public final class PTtlCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'pttl' command");
        }
        return RespValue.integer(ctx.db().remainingTtlMs(ctx.argString(1)));
    }
}
