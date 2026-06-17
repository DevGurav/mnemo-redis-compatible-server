package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code EXPIREAT key unix-timestamp-seconds} — set absolute expiry from a Unix timestamp. */
public final class ExpireAtCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'expireat' command");
        }
        String key = ctx.argString(1);
        long ts;
        try { ts = Long.parseLong(ctx.argString(2)); }
        catch (NumberFormatException e) { return RespValue.error("ERR value is not an integer or out of range"); }
        if (ts < 0) return RespValue.error("ERR invalid expire time in 'expireat' command");
        if (!ctx.db().exists(key)) return RespValue.integer(0);
        ctx.db().setExpiry(key, ts * 1_000L); // convert seconds to ms
        return RespValue.integer(1);
    }
}
