package dev.devgurav.mnemo.command.sortedset;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.ZSet;

/**
 * {@code ZRANK key member} → the member's 0-based rank (lowest score = rank 0), or a null bulk
 * string if the member — or the key — is absent.
 */
public final class ZRankCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 3) {
            return RespValue.error("ERR wrong number of arguments for 'zrank' command");
        }

        String key = ctx.argString(1);
        if (ctx.db().isString(key) || ctx.db().isHash(key) || ctx.db().isList(key)) {
            return RespValue.error(SortedSetReplies.WRONGTYPE);
        }

        ZSet zset = ctx.db().zset(key);
        if (zset == null) {
            return RespValue.nullBulk();
        }

        // ZSet.rank is 1-based (0 = absent → returns -1); Redis ZRANK is 0-based.
        int rank = zset.rank(ctx.argString(2));
        return rank == -1 ? RespValue.nullBulk() : RespValue.integer(rank - 1);
    }
}
