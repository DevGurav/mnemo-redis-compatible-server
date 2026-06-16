package dev.devgurav.mnemo.command;

import dev.devgurav.mnemo.command.server.CommandCommand;
import dev.devgurav.mnemo.command.server.EchoCommand;
import dev.devgurav.mnemo.command.server.PingCommand;
import dev.devgurav.mnemo.command.sortedset.ZAddCommand;
import dev.devgurav.mnemo.command.sortedset.ZRangeCommand;
import dev.devgurav.mnemo.command.sortedset.ZRankCommand;
import dev.devgurav.mnemo.command.strings.DelCommand;
import dev.devgurav.mnemo.command.strings.ExistsCommand;
import dev.devgurav.mnemo.command.strings.GetCommand;
import dev.devgurav.mnemo.command.strings.SetCommand;
import dev.devgurav.mnemo.net.resp.RespValue;
import dev.devgurav.mnemo.store.Db;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps command names to {@link Command} handlers and dispatches to them (Command + Registry).
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new HashMap<>();

    public void register(String name, Command command) {
        commands.put(name.toUpperCase(Locale.ROOT), command);
    }

    public RespValue dispatch(Db db, List<byte[]> args) {
        if (args.isEmpty()) return RespValue.error("ERR empty command");
        String name = new String(args.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        Command command = commands.get(name);
        if (command == null) {
            return RespValue.error("ERR unknown command '" + name + "'");
        }
        return command.execute(new CommandContext(db, args));
    }

    /** The default command set for week 1. */
    public static CommandRegistry standard() {
        CommandRegistry r = new CommandRegistry();
        r.register("PING", new PingCommand());
        r.register("ECHO", new EchoCommand());
        r.register("COMMAND", new CommandCommand());
        r.register("SET", new SetCommand());
        r.register("GET", new GetCommand());
        r.register("DEL", new DelCommand());
        r.register("EXISTS", new ExistsCommand());
        r.register("ZADD", new ZAddCommand());
        r.register("ZRANK", new ZRankCommand());
        r.register("ZRANGE", new ZRangeCommand());
        return r;
    }
}
