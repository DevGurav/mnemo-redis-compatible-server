package dev.devgurav.mnemo.command.sortedset;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.ZSet;

/**
 * {@code ZADD key score member [score member ...]} → the number of members newly added (not those
 * whose score was merely updated). Flags (NX/XX/GT/LT/CH/INCR) are a later week.
 */
public final class ZAddCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        int n = ctx.argCount();
        // key + at least one score/member pair, and pairs must be complete.
        if (n < 4 || (n - 2) % 2 != 0) {
            return RespValue.error("ERR wrong number of arguments for 'zadd' command");
        }

        String key = ctx.argString(1);
        if (ctx.db().isString(key) || ctx.db().isHash(key)) {
            return RespValue.error(SortedSetReplies.WRONGTYPE);
        }

        int pairs = (n - 2) / 2;

        // Parse every score up front, so a bad float in a later pair doesn't half-apply the command.
        double[] scores = new double[pairs];
        for (int i = 0; i < pairs; i++) {
            try {
                scores[i] = SortedSetReplies.parseScore(ctx.argString(2 + i * 2));
            } catch (NumberFormatException e) {
                return RespValue.error("ERR value is not a valid float");
            }
        }

        ZSet zset = ctx.db().zsetForWrite(key);
        int added = 0;
        for (int i = 0; i < pairs; i++) {
            String member = ctx.argString(3 + i * 2);
            if (zset.add(member, scores[i])) added++;
        }
        return RespValue.integer(added);
    }
}
