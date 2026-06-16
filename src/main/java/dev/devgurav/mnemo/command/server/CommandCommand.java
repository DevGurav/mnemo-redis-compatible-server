package dev.devgurav.mnemo.command.server;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/**
 * Minimal {@code COMMAND} handler. {@code redis-cli} issues {@code COMMAND DOCS} / {@code COMMAND COUNT}
 * on connect; replying with an empty array keeps the CLI happy without a full command table (a
 * later addition).
 */
public final class CommandCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        return RespValue.emptyArray();
    }
}
