package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Dict;

/** {@code HGET key field} → the field's value, or a null bulk string if the field or key is absent. */
public final class HGetCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'hget' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = HashReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        Dict hash = ctx.db().hash(key);
        if (hash == null) return RespValue.nullBulk();

        byte[] value = hash.get(ctx.argString(2));
        return value == null ? RespValue.nullBulk() : RespValue.bulk(value);
    }
}
