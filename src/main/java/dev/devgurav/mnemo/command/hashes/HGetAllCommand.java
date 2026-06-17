package dev.devgurav.mnemo.command.hashes;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Dict;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code HGETALL key} → a flat array of alternating field and value bulk strings; an empty array if
 * the key is absent.
 *
 * <p>Serialises the field/value pairs by walking the hash's backing {@link Dict} via
 * {@link Dict#forEach}, which yields every entry exactly once even while that {@code Dict} is in the
 * middle of an incremental rehash (entries split across both tables).
 */
public final class HGetAllCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'hgetall' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = HashReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        Dict hash = ctx.db().hash(key);
        if (hash == null) return RespValue.emptyArray();

        List<RespValue> out = new ArrayList<>(hash.size() * 2);
        hash.forEach((field, value) -> {
            out.add(RespValue.bulk(field));
            out.add(RespValue.bulk(value));
        });
        return RespValue.array(out);
    }
}
