package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Dict;

/**
 * {@code HSET key field value [field value ...]} → the number of fields that were newly added
 * (existing fields whose value is updated are not counted).
 */
public final class HSetCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        int n = ctx.argCount();
        if (n < 4 || (n - 2) % 2 != 0) {
            return RespValue.error("ERR wrong number of arguments for 'hset' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = HashReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        Dict hash = ctx.db().hashForWrite(key);
        int added = 0;
        for (int i = 2; i < n; i += 2) {
            String field = ctx.argString(i);
            if (!hash.containsKey(field)) added++;
            hash.put(field, ctx.arg(i + 1));
        }
        return RespValue.integer(added);
    }
}
