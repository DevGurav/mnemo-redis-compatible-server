package dev.devgurav.mnemo.command.keyspace;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code KEYS pattern} — returns all keys matching a Redis-style glob pattern.
 *
 * <p>Scans all four namespaces (strings, zsets, hashes, lists). Order is unspecified, matching
 * Redis semantics. O(N) where N is the total number of keys across all namespaces — avoid on large
 * keyspaces in production; use {@code SCAN} for incremental iteration (not yet implemented).
 */
public final class KeysCommand implements Command {

    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'keys' command");
        }
        String pattern = ctx.argString(1);
        List<String> matched = ctx.db().keys(key -> GlobPattern.matches(key, pattern));
        List<RespValue> elements = new ArrayList<>(matched.size());
        for (String k : matched) {
            elements.add(RespValue.bulk(k.getBytes(StandardCharsets.UTF_8)));
        }
        return RespValue.array(elements);
    }
}
