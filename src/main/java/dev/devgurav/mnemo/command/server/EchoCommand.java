package dev.devgurav.mnemo.command.server;

import dev.devgurav.mnemo.command.Command;
import dev.devgurav.mnemo.command.CommandContext;
import dev.devgurav.mnemo.net.resp.RespValue;

/** {@code ECHO message} → the message as a bulk string. */
public final class EchoCommand implements Command {
    @Override
    public RespValue execute(CommandContext ctx) {
        if (ctx.argCount() != 2) {
            return RespValue.error("ERR wrong number of arguments for 'echo' command");
        }
        return RespValue.bulk(ctx.arg(1));
    }
}
