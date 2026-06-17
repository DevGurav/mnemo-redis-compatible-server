package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code EXPIRE key seconds} — set a TTL in seconds.
 * Returns {@code :1} if the key exists and the TTL was set; {@code :0} if the key does not exist.
 */
public final class ExpireCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'expire' command");
        }
        String key = ctx.argString(1);
        long secs;
        try { secs = Long.parseLong(ctx.argString(2)); }
        catch (NumberFormatException e) { return RespValue.error("ERR value is not an integer or out of range"); }
        if (secs < 0) return RespValue.error("ERR invalid expire time in 'expire' command");
        if (!ctx.db().exists(key)) return RespValue.integer(0);
        ctx.db().setExpiry(key, System.currentTimeMillis() + secs * 1_000L);
        return RespValue.integer(1);
    }
}
