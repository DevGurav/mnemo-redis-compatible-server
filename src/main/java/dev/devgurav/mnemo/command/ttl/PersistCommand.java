package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code PERSIST key} — remove the TTL from a key so it lives forever.
 * Returns {@code :1} if the TTL was removed; {@code :0} if the key has no TTL or does not exist.
 */
public final class PersistCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'persist' command");
        }
        return RespValue.integer(ctx.db().removeExpiry(ctx.argString(1)) ? 1 : 0);
    }
}
