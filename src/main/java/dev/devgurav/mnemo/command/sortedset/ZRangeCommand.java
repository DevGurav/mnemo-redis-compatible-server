package dev.devgurav.mnemo.command.sortedset;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.ZSet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ZRANGE key start stop [WITHSCORES]} → members in ascending score order whose 0-based rank
 * falls in {@code [start, stop]}. Indices may be negative (from the end: {@code -1} is the last
 * member) and are clamped to the set's bounds. The index-based form only (no {@code BYSCORE}/
 * {@code BYLEX}) for now.
 */
public final class ZRangeCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        int n = ctx.argCount();
        if (n != 4 && n != 5) {
            return RespValue.error("ERR wrong number of arguments for 'zrange' command");
        }

        boolean withScores = false;
        if (n == 5) {
            if (!ctx.argString(4).equalsIgnoreCase("WITHSCORES")) {
                return RespValue.error("ERR syntax error");
            }
            withScores = true;
        }

        String key = ctx.argString(1);
        if (ctx.db().isString(key) || ctx.db().isHash(key)) {
            return RespValue.error(SortedSetReplies.WRONGTYPE);
        }

        long start;
        long stop;
        try {
            start = Long.parseLong(ctx.argString(2).trim());
            stop  = Long.parseLong(ctx.argString(3).trim());
        } catch (NumberFormatException e) {
            return RespValue.error("ERR value is not an integer or out of range");
        }

        ZSet zset = ctx.db().zset(key);
        if (zset == null) {
            return RespValue.emptyArray();
        }

        long size = zset.size();
        if (start < 0) start += size;
        if (stop  < 0) stop  += size;
        if (start < 0) start = 0;
        if (stop >= size) stop = size - 1;
        if (size == 0 || start > stop || start >= size) {
            return RespValue.emptyArray();
        }

        // ZSet.rangeByRank is 1-based inclusive; our normalized [start, stop] is 0-based inclusive.
        List<String> members = zset.rangeByRank((int) (start + 1), (int) (stop + 1));

        List<RespValue> reply = new ArrayList<>(withScores ? members.size() * 2 : members.size());
        for (String member : members) {
            reply.add(bulk(member));
            if (withScores) {
                reply.add(bulk(SortedSetReplies.formatScore(zset.score(member))));
            }
        }
        return RespValue.array(reply);
    }

    private static RespValue bulk(String s) {
        return RespValue.bulk(s.getBytes(StandardCharsets.UTF_8));
    }
}
