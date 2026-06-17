package dev.devgurav.mnemo.aof;

import dev.devgurav.mnemo.command.CommandRegistry;
import dev.devgurav.mnemo.store.Db;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays all RESP multibulk frames stored in an append-only file back through the
 * {@link CommandRegistry}, reconstructing the server state after a crash or restart (ADR 0013).
 *
 * <p>Runs synchronously during startup, before the server socket opens, so no clients can
 * observe partial state. Errors during a single command are silently skipped so one corrupt
 * entry does not abort the entire replay.
 */
public final class AofReplayer {

    private AofReplayer() {}

    /**
     * Replay all commands in {@code path} through {@code registry} against {@code db}.
     *
     * @return the number of commands successfully replayed
     */
    public static int replay(Path path, CommandRegistry registry, Db db) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) return 0;
        int count = 0;
        try (InputStream raw = Files.newInputStream(path);
             BufferedInputStream in = new BufferedInputStream(raw, 65536)) {
            List<byte[]> args;
            while ((args = readCommand(in)) != null) {
                try {
                    registry.dispatch(db, args);
                } catch (Exception ignored) {
                    // skip corrupt or incompatible commands
                }
                count++;
            }
        }
        return count;
    }

    private static List<byte[]> readCommand(BufferedInputStream in) throws IOException {
        String countLine = readLine(in);
        if (countLine == null) return null; // clean EOF
        if (!countLine.startsWith("*")) throw new IOException("AOF: expected *, got: " + countLine);
        int argc = Integer.parseInt(countLine.substring(1));

        List<byte[]> args = new ArrayList<>(argc);
        for (int i = 0; i < argc; i++) {
            String lenLine = readLine(in);
            if (lenLine == null || !lenLine.startsWith("$"))
                throw new IOException("AOF: expected $, got: " + lenLine);
            int len = Integer.parseInt(lenLine.substring(1));
            byte[] data = in.readNBytes(len);
            in.read(); // \r
            in.read(); // \n
            args.add(data);
        }
        return args;
    }

    /** Read a \r\n-terminated line (without the terminator), or null at clean EOF. */
    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // consume \n
                return sb.toString();
            }
            sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
