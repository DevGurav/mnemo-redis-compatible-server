package dev.devgurav.mnemo.store.evict;

/**
 * Determines how the {@link Evictor} picks the victim when {@code usedMemory > maxmemory}.
 *
 * <ul>
 *   <li>{@link #NOEVICTION} — no eviction; writes fail with an error once the limit is hit (the
 *       default when {@code maxmemory == 0}).</li>
 *   <li>{@link #ALLKEYS_LRU} — evict the least-recently-used key sampled from the string
 *       keyspace (ADR 0010).</li>
 *   <li>{@link #ALLKEYS_LFU} — evict the least-frequently-used key sampled from the string
 *       keyspace (ADR 0012).</li>
 * </ul>
 */
public enum EvictionPolicy {
    NOEVICTION,
    ALLKEYS_LRU,
    ALLKEYS_LFU;

    /** Parse from the Redis-style config string (e.g. {@code "allkeys-lru"}). */
    public static EvictionPolicy fromString(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "allkeys-lru" -> ALLKEYS_LRU;
            case "allkeys-lfu" -> ALLKEYS_LFU;
            default            -> NOEVICTION;
        };
    }

    public String toConfigString() {
        return switch (this) {
            case ALLKEYS_LRU -> "allkeys-lru";
            case ALLKEYS_LFU -> "allkeys-lfu";
            default          -> "noeviction";
        };
    }
}
