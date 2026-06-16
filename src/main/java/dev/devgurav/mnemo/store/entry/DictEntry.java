package dev.devgurav.mnemo.store.entry;

/**
 * A single node in a {@link dev.devgurav.mnemo.store.Dict} bucket chain.
 *
 * <p>Fields are intentionally public: {@code Dict} and {@code DictEntryPool} access them
 * directly for performance — there are no lock acquisitions or virtual dispatch on the hot path.
 *
 * <p>Pooling invariant: when a node is returned to {@link DictEntryPool}, the pool MUST null
 * {@code key}, {@code value}, and {@code next} before the node enters the free list. A pooled
 * node survives into old-gen; if its reference fields are not nulled they form a GC root that
 * retains the key and value objects indefinitely, inflating old-gen occupancy and defeating ZGC's
 * generational hypothesis. See architecture-spec.md §4.
 */
public final class DictEntry {

    /** Pre-computed hash of {@code key}; avoids rehashing on every lookup. */
    public int hash;

    /** The raw key bytes. Null when this node is sitting in the pool free list. */
    public byte[] key;

    /** The raw value bytes. Null when this node is sitting in the pool free list. */
    public byte[] value;

    /**
     * Next node in the bucket chain, or the next node in the pool's intrusive free list when
     * this node is not in active use. Null when this node is the tail of either list.
     */
    public DictEntry next;

    /** Pool-allocated nodes are constructed once by {@link DictEntryPool}; callers use acquire(). */
    public DictEntry() {}

    // --- Getters for cross-package access (tests, metrics) ---

    public byte[] key()     { return key; }
    public byte[] value()   { return value; }
    public DictEntry next() { return next; }
    public int hash()       { return hash; }
}
