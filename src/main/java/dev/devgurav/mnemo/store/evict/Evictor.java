package dev.devgurav.mnemo.store.evict;

import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.Dict;
import dev.devgurav.mnemo.store.entry.DictEntry;

/**
 * Redis-style <em>approximate</em> LRU eviction over the string keyspace.
 *
 * <p>True LRU needs a global recency-ordered structure touched on every access; Redis (and Mnemo)
 * instead approximate it: when memory is over budget, draw a small random sample of keys and evict
 * the least-recently-used one in the sample. With a handful of samples this lands very close to true
 * LRU at a fraction of the cost and none of the bookkeeping.
 *
 * <h2>Allocation discipline</h2>
 * <p>The sampler runs on the command path and must produce <b>zero garbage</b>. It holds the running
 * best candidate in a single {@link DictEntry} reference plus a {@code long} for its recency stamp —
 * no arrays, no iterators, no boxing. Randomness comes from an inlined xorshift over a {@code long}
 * field, so even the RNG allocates nothing. The sampled entries are read straight out of the
 * {@link Dict} bucket arrays via {@link Dict#randomEntry(int)}.
 *
 * <h2>Scope</h2>
 * <p>Only the string keyspace is evictable (its {@code Dict} is what {@link Db#usedMemory()} weighs
 * and what this samples). The bound is therefore defined over exactly what can be reclaimed, so the
 * loop always makes progress and terminates.
 *
 * <p>Owned by the single command thread; no synchronization.
 */
public final class Evictor {

    /** Redis' default sample size for {@code maxmemory-policy allkeys-lru}. */
    public static final int DEFAULT_SAMPLE_SIZE = 5;

    private final Dict dict;
    private final Db db;
    private final long maxmemory;
    private final int sampleSize;

    /** xorshift state; must be non-zero. Seeded off identity + nanoTime so shards differ. */
    private long rngState;

    public Evictor(Dict dict, Db db, long maxmemory) {
        this(dict, db, maxmemory, DEFAULT_SAMPLE_SIZE);
    }

    public Evictor(Dict dict, Db db, long maxmemory, int sampleSize) {
        if (maxmemory <= 0) throw new IllegalArgumentException("maxmemory must be > 0 for eviction");
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be > 0");
        this.dict       = dict;
        this.db         = db;
        this.maxmemory  = maxmemory;
        this.sampleSize = sampleSize;
        this.rngState   = (System.nanoTime() ^ System.identityHashCode(this)) | 1L;
    }

    /**
     * Evict least-recently-used string keys until {@link Db#usedMemory()} is back within
     * {@code maxmemory} (or there is nothing left to evict). Called before each command executes.
     */
    public void evictIfNeeded() {
        while (db.usedMemory() > maxmemory) {
            if (!evictOne()) return; // nothing sampled / nothing removed — avoid spinning
        }
    }

    /**
     * Sample, pick the LRU victim, and delete it.
     *
     * @return {@code true} if a key was evicted; {@code false} if the keyspace had nothing to sample
     *         or the sampled victim could not be removed (so the caller stops).
     */
    private boolean evictOne() {
        DictEntry victim = sampleVictim();
        if (victim == null) return false;

        // Remove by raw bytes so no String is rebuilt; the Dict updates usedMemory and size itself.
        if (!dict.removeByteKey(victim.key, victim.hash)) return false;
        db.recordEviction();
        return true;
    }

    /**
     * Draw {@code sampleSize} random entries and return the one with the smallest {@code lruTime}
     * (the least recently used). Allocation-free: only primitives and one entry reference are used.
     *
     * @return the LRU entry among the sample, or {@code null} if every draw missed (empty dict)
     */
    private DictEntry sampleVictim() {
        DictEntry best = null;
        long bestLru = Long.MAX_VALUE;
        for (int i = 0; i < sampleSize; i++) {
            DictEntry candidate = dict.randomEntry(nextRandom());
            if (candidate == null) continue;
            if (best == null || candidate.lruTime < bestLru) {
                best    = candidate;
                bestLru = candidate.lruTime;
            }
        }
        return best;
    }

    /** Inlined xorshift64; returns a fresh pseudo-random int with no allocation. */
    private int nextRandom() {
        long x = rngState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        rngState = x;
        return (int) x;
    }
}
