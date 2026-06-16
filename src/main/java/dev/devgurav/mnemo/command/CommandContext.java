package dev.devgurav.mnemo.command;

import dev.devgurav.mnemo.store.Db;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The arguments and database handed to a {@link Command}. {@code arg(0)} is the command name.
 */
public final class CommandContext {

    private final Db db;
    private final List<byte[]> args;

    public CommandContext(Db db, List<byte[]> args) {
        this.db = db;
        this.args = args;
    }

    public Db db() { return db; }

    public int argCount() { return args.size(); }

    public byte[] arg(int i) { return args.get(i); }

    public String argString(int i) { return new String(args.get(i), StandardCharsets.UTF_8); }

    public String name() { return argString(0); }
}
