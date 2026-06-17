package dev.devgurav.mnemo.shard;

/**
 * CRC-16/XMODEM (polynomial 0x1021, init 0xFFFF) — the same algorithm Redis uses to map keys to
 * hash slots. A precomputed 256-entry lookup table collapses each byte to one XOR + table read,
 * making the per-key cost proportional to key length with no branch per bit.
 *
 * <p>Only the bytes inside {@code {}} are hashed when a hashtag is present; otherwise the whole
 * key is hashed. This matches Redis Cluster hash-slot assignment and lets callers co-locate
 * logically related keys on the same shard by writing {@code {user:42}:profile}.
 */
public final class Crc16 {

    private Crc16() {}

    private static final int[] TABLE = buildTable();

    private static int[] buildTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            // Keep crc within 16 bits at every step; unlike C uint16_t, Java int does not
            // overflow naturally, so bits above 15 would accumulate and corrupt the MSB check.
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) & 0xFFFF) ^ 0x1021
                                             : (crc << 1) & 0xFFFF;
            }
            t[i] = crc;
        }
        return t;
    }

    /**
     * Compute the CRC-16/XMODEM checksum of the effective routing key inside {@code keyBytes}.
     *
     * <p>If a {@code {hashtag}} is present (first {@code {} closed by a later {@code }}), only
     * the bytes between the braces are hashed — matching Redis Cluster semantics. If no hashtag is
     * found the whole array is hashed.
     *
     * @param keyBytes raw UTF-8 key bytes from the RESP argument
     * @return CRC-16 value in [0, 65535]
     */
    public static int compute(byte[] keyBytes) {
        // Locate {hashtag} — scan for the first '{' followed by a matching '}'.
        int start = -1;
        int end   = -1;
        for (int i = 0; i < keyBytes.length; i++) {
            if (keyBytes[i] == '{' && start == -1) { start = i; }
            else if (keyBytes[i] == '}' && start != -1) { end = i; break; }
        }

        // Use the hashtag contents if the braces are non-empty; otherwise hash the full key.
        int from, len;
        if (start != -1 && end != -1 && end > start + 1) {
            from = start + 1;
            len  = end - start - 1;
        } else {
            from = 0;
            len  = keyBytes.length;
        }

        int crc = 0;
        for (int i = from; i < from + len; i++) {
            crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ (keyBytes[i] & 0xFF)) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }
}
