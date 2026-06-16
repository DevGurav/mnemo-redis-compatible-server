package dev.devgurav.mnemo.command;

import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * A single Redis command (Command pattern). Implementations run on the single command thread,
 * so they may touch the {@link dev.devgurav.mnemo.store.Db} without synchronization.
 */
@FunctionalInterface
public interface Command {
    RespValue execute(CommandContext ctx);
}
