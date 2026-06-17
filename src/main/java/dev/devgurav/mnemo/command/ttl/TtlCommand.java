package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code TTL key} — returns the remaining TTL in seconds.
 * {@code :-1} if the key exists but has no TTL; {@code :-2} if the key does not exist.
 */
public final class TtlCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'ttl' command");
        }
        long ms = ctx.db().remainingTtlMs(ctx.argString(1));
        if (ms == -2) return RespValue.integer(-2);
        if (ms == -1) return RespValue.integer(-1);
        long secs = (ms + 999) / 1_000; // ceiling division so 1 ms remaining → 1 s
        return RespValue.integer(secs);
    }
}
