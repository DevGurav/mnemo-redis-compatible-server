package dev.devgurav.mnemo.command.ttl;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code PEXPIRE key milliseconds} — set a TTL in milliseconds. */
public final class PExpireCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'pexpire' command");
        }
        String key = ctx.argString(1);
        long ms;
        try { ms = Long.parseLong(ctx.argString(2)); }
        catch (NumberFormatException e) { return RespValue.error("ERR value is not an integer or out of range"); }
        if (ms < 0) return RespValue.error("ERR invalid expire time in 'pexpire' command");
        if (!ctx.db().exists(key)) return RespValue.integer(0);
        ctx.db().setExpiry(key, System.currentTimeMillis() + ms);
        return RespValue.integer(1);
    }
}
