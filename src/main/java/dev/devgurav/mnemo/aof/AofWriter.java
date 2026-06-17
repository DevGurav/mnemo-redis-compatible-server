package dev.devgurav.mnemo.aof;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Appends each write command as a RESP multibulk frame to the append-only file (ADR 0013).
 *
 * <p>Runs on the shard thread; no synchronisation needed. Each {@link #append} call assembles the
 * full frame into a single {@link ByteBuffer} and writes it in one shot to minimise syscall count,
 * then calls {@link FileChannel#force(boolean)} to flush to OS buffers without syncing file
 * metadata (a good trade-off between durability and throughput for a single-node store).
 *
 * <p>Must be {@link #close closed} on server shutdown to release the file handle.
 */
public final class AofWriter implements Closeable {

    private final FileChannel channel;

    public AofWriter(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /**
     * Serialises {@code args} as a RESP multibulk array and appends it to the AOF.
     *
     * <p>Frame format: {@code *N\r\n($len\r\ndata\r\n) × N}
     */
    public void append(List<byte[]> args) {
        int n = args.size();
        byte[] arrayHeader = ("*" + n + "\r\n").getBytes(StandardCharsets.US_ASCII);

        // pre-compute bulk headers so we can size the buffer in one pass
        byte[][] bulkHeaders = new byte[n][];
        int total = arrayHeader.length;
        for (int i = 0; i < n; i++) {
            bulkHeaders[i] = ("$" + args.get(i).length + "\r\n").getBytes(StandardCharsets.US_ASCII);
            total += bulkHeaders[i].length + args.get(i).length + 2; // +2 for trailing \r\n
        }

        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(arrayHeader);
        for (int i = 0; i < n; i++) {
            buf.put(bulkHeaders[i]);
            buf.put(args.get(i));
            buf.put((byte) '\r');
            buf.put((byte) '\n');
        }
        buf.flip();

        try {
            while (buf.hasRemaining()) channel.write(buf);
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
