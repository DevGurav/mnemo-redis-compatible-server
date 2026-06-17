package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Dict;

/** {@code HLEN key} → the number of fields in the hash, or 0 if the key is absent. */
public final class HLenCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'hlen' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = HashReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        Dict hash = ctx.db().hash(key);
        return RespValue.integer(hash == null ? 0 : hash.size());
    }
}
