package dev.devgurav.mnemo.store.entry;

/**
 * A shard-local, intrusive free-list pool of {@link DictEntry} nodes.
 *
 * <p>Goal: eliminate {@code DictEntry} allocation from the steady-state {@code put} hot path so
 * that ZGC's young-gen collector sees near-zero allocation pressure from the data plane.
 * Verify the effect with async-profiler alloc flame graphs (benchmarking-methodology.md §2).
 *
 * <p>Owned exclusively by one {@link dev.devgurav.mnemo.store.Dict} shard thread. No
 * synchronization is used or needed.
 *
 * <h2>Free-list discipline</h2>
 * <ul>
 *   <li>{@link #acquire} — pops a node from the free list, sets all fields, and returns it.
 *       Falls back to {@code new DictEntry()} on a miss.</li>
 *   <li>{@link #release} — nulls {@code key}, {@code value}, and {@code next} unconditionally
 *       first, then pushes the shell onto the free list. The unconditional null-then-branch order
 *       ensures no old chain reference survives into a pooled node regardless of pool capacity
 *       (architecture-spec.md §4).</li>
 * </ul>
 *
 * <p>The free list is intrusive: freed nodes link through their own {@code next} field, so no
 * wrapper allocation is ever required for pool bookkeeping.
 */
public final class DictEntryPool {

    private static final int DEFAULT_MAX_POOLED = 128;

    private final int maxPooled;

    /** Head of the intrusive free list; null when the pool is empty. */
    private DictEntry freeHead;

    /** Current number of nodes sitting in the free list. */
    private int poolSize;

    public DictEntryPool() {
        this(DEFAULT_MAX_POOLED);
    }

    public DictEntryPool(int maxPooled) {
        if (maxPooled <= 0) throw new IllegalArgumentException("maxPooled must be > 0");
        this.maxPooled = maxPooled;
    }

    /**
     * Returns a fully initialised {@link DictEntry} from the free list, or freshly allocated on
     * a miss. All four fields are set before returning — the caller does not touch them.
     *
     * @param hash  pre-computed hash of {@code key}
     * @param key   the entry's key bytes
     * @param value the entry's value bytes
     * @param next  the next node in the bucket chain (the current bucket head), or {@code null}
     * @return a ready-to-use entry; never {@code null}
     */
    public DictEntry acquire(int hash, byte[] key, byte[] value, DictEntry next) {
        DictEntry entry;
        if (freeHead != null) {
            // Pop from free list — sever the free-list link before returning.
            entry    = freeHead;
            freeHead = entry.next;
            entry.next = null;
            poolSize--;
        } else {
            entry = new DictEntry();
        }
        entry.hash  = hash;
        entry.key   = key;
        entry.value = value;
        entry.next  = next;
        return entry;
    }

    /**
     * Returns {@code entry} to the pool for future reuse.
     *
     * <p>All reference fields are nulled <em>before</em> the capacity check. This guarantees the
     * null-on-free invariant (architecture-spec.md §4) regardless of whether the entry is pooled
     * or discarded: a node dropped to GC while still holding a live key/value reference extends
     * the GC lifetime of those byte arrays unnecessarily.
     *
     * @param entry the node to return; must not be {@code null}
     */
    public void release(DictEntry entry) {
        // Null all reference fields first — unconditionally, before any branching.
        // This breaks the old bucket-chain reference and satisfies the null-on-free invariant
        // whether the entry ends up in the pool or is discarded to GC.
        entry.key   = null;
        entry.value = null;
        entry.next  = null;
        entry.hash  = 0;

        if (poolSize >= maxPooled) return; // discard safely: fields are already nulled

        entry.next = freeHead;
        freeHead   = entry;
        poolSize++;
    }

    /** The number of nodes currently sitting in the free list. */
    public int poolSize() {
        return poolSize;
    }
}
