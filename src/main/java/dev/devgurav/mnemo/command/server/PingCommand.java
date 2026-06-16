package dev.devgurav.mnemo.command.server;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code PING [message]} → {@code PONG}, or echoes the message if provided. */
public final class PingCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() >= 2) return RespValue.bulk(ctx.arg(1));
        return RespValue.pong();
    }
}
