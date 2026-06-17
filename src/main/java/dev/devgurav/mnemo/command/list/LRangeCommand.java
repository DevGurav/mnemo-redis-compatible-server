package dev.devgurav.mnemo.command.list;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.list.IntrusiveList;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code LRANGE key start stop} → the elements between {@code start} and {@code stop} inclusive, as
 * an array of bulk strings. Negative indices count from the end ({@code -1} is the last element);
 * out-of-range bounds are clamped. An absent key (or empty range) yields an empty array.
 */
public final class LRangeCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 4) {
            return RespValue.error("ERR wrong number of arguments for 'lrange' command");
        }

        String key = ctx.argString(1);
        RespValue wrongType = ListReplies.wrongTypeError(ctx, key);
        if (wrongType != null) return wrongType;

        int start;
        int stop;
        try {
            start = Integer.parseInt(ctx.argString(2).trim());
            stop  = Integer.parseInt(ctx.argString(3).trim());
        } catch (NumberFormatException e) {
            return RespValue.error("ERR value is not an integer or out of range");
        }

        IntrusiveList list = ctx.db().list(key);
        if (list == null) return RespValue.emptyArray();

        List<byte[]> slice = list.lrange(start, stop);
        List<RespValue> items = new ArrayList<>(slice.size());
        for (byte[] element : slice) {
            items.add(RespValue.bulk(element));
        }
        return RespValue.array(items);
    }
}
