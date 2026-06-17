package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code PEXPIREAT key unix-timestamp-ms} — set absolute expiry from a Unix timestamp in ms. */
public final class PExpireAtCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'pexpireat' command");
        }
        String key = ctx.argString(1);
        long tsMs;
        try { tsMs = Long.parseLong(ctx.argString(2)); }
        catch (NumberFormatException e) { return RespValue.error("ERR value is not an integer or out of range"); }
        if (tsMs < 0) return RespValue.error("ERR invalid expire time in 'pexpireat' command");
        if (!ctx.db().exists(key)) return RespValue.integer(0);
        ctx.db().setExpiry(key, tsMs);
        return RespValue.integer(1);
    }
}
