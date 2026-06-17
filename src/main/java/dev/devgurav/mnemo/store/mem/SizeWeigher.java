package dev.devgurav.mnemo.store.mem;

import java.nio.charset.StandardCharsets;

/**
 * Computes the <em>logical</em> byte size of a key/value mapping — the unit the {@code maxmemory}
 * bound and the LRU evictor reason about ([ADR 0006](../../../../../../docs/decisions/0006-logical-maxmemory.md)).
 *
 * <p>This is deliberately not a physical heap measurement: weighing a live object graph on the JVM
 * is non-deterministic and cache-miss-heavy. Instead each mapping costs {@code key.length +
 * value.length + ENTRY_OVERHEAD_BYTES}, a deterministic O(1) figure maintained as a running counter
 * on insert/delete. {@code ENTRY_OVERHEAD_BYTES} approximates the per-entry bookkeeping a
 * {@code DictEntry} carries beyond its payload (object header, the hash/next/lru fields, and an
 * amortised bucket slot) so the counter tracks real growth rather than payload alone.
 *
 * <p><b>Consistency contract.</b> Whatever weight is <em>added</em> when a mapping is stored must be
 * the same weight <em>subtracted</em> when it is removed or evicted. The byte-array overload is the
 * canonical one and is used wherever the raw key bytes are already in hand (the {@code Dict} and the
 * evictor); the {@code String} overload exists only for the placeholder {@code HashMapStore}.
 */
public final class SizeWeigher {

    private SizeWeigher() {}

    /** Per-entry bookkeeping charged on top of the raw key+value payload bytes. */
    public static final long ENTRY_OVERHEAD_BYTES = 48;

    /** Logical size of a mapping whose key bytes are already in hand (the canonical path). */
    public static long weigh(byte[] key, byte[] value) {
        return (long) key.length + value.length + ENTRY_OVERHEAD_BYTES;
    }

    /**
     * Logical size of a mapping keyed by a {@code String}. The key is weighed by its UTF-8 byte
     * length so the figure matches the byte-array overload for the same key. Only the placeholder
     * {@code HashMapStore} uses this; it is not on any allocation-free path.
     */
    public static long weigh(String key, byte[] value) {
        return (long) key.getBytes(StandardCharsets.UTF_8).length + value.length + ENTRY_OVERHEAD_BYTES;
    }
}
