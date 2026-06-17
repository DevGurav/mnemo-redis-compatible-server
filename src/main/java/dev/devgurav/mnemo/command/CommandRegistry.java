package dev.devgurav.mnemo.command;

import dev.devgurav.mnemo.command.hashes.HDelCommand;
import dev.devgurav.mnemo.command.hashes.HGetAllCommand;
import dev.devgurav.mnemo.command.hashes.HGetCommand;
import dev.devgurav.mnemo.command.hashes.HLenCommand;
import dev.devgurav.mnemo.command.hashes.HSetCommand;
import dev.devgurav.mnemo.command.keyspace.DbSizeCommand;
import dev.devgurav.mnemo.command.keyspace.FlushAllCommand;
import dev.devgurav.mnemo.command.keyspace.FlushDbCommand;
import dev.devgurav.mnemo.command.keyspace.TypeCommand;
import dev.devgurav.mnemo.command.server.CommandCommand;
import dev.devgurav.mnemo.command.server.EchoCommand;
import dev.devgurav.mnemo.command.server.PingCommand;
import dev.devgurav.mnemo.command.sortedset.ZAddCommand;
import dev.devgurav.mnemo.command.sortedset.ZRangeCommand;
import dev.devgurav.mnemo.command.sortedset.ZRankCommand;
import dev.devgurav.mnemo.command.strings.DecrByCommand;
import dev.devgurav.mnemo.command.strings.DecrCommand;
import dev.devgurav.mnemo.command.strings.DelCommand;
import dev.devgurav.mnemo.command.strings.ExistsCommand;
import dev.devgurav.mnemo.command.strings.GetCommand;
import dev.devgurav.mnemo.command.strings.IncrByCommand;
import dev.devgurav.mnemo.command.strings.IncrCommand;
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
        r.register("INCR", new IncrCommand());
        r.register("DECR", new DecrCommand());
        r.register("INCRBY", new IncrByCommand());
        r.register("DECRBY", new DecrByCommand());
        r.register("ZADD", new ZAddCommand());
        r.register("ZRANK", new ZRankCommand());
        r.register("ZRANGE", new ZRangeCommand());
        r.register("HSET", new HSetCommand());
        r.register("HGET", new HGetCommand());
        r.register("HGETALL", new HGetAllCommand());
        r.register("HDEL", new HDelCommand());
        r.register("HLEN", new HLenCommand());
        r.register("TYPE", new TypeCommand());
        r.register("DBSIZE", new DbSizeCommand());
        r.register("FLUSHDB", new FlushDbCommand());
        r.register("FLUSHALL", new FlushAllCommand());
        return r;
    }
}
