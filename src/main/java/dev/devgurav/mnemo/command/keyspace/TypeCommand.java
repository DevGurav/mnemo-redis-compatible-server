package dev.devgurav.mnemo.command.keyspace;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * {@code TYPE key} → the key's value type as a simple string: {@code string}, {@code zset},
 * {@code hash}, {@code list}, or {@code none} when the key does not exist.
 */
public final class TypeCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'type' command");
        }
        String key = ctx.argString(1);
        String type = ctx.db().isString(key) ? "string"
                    : ctx.db().isZSet(key)   ? "zset"
                    : ctx.db().isHash(key)   ? "hash"
                    : ctx.db().isList(key)   ? "list"
                    : "none";
        return new RespValue.SimpleString(type);
    }
}
