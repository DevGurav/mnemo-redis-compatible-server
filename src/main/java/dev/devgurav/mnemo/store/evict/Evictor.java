package dev.devgurav.mnemo.store.evict;

import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.Dict;
import dev.devgurav.mnemo.store.entry.DictEntry;

/**
 * Redis-style <em>approximate</em> LRU/LFU eviction over the string keyspace (ADR 0010, ADR 0012).
 *
 * <p>When memory is over budget, draw a small random sample of keys and evict the
 * least-recently-used (LRU) or least-frequently-used (LFU) one from the sample, depending on the
 * configured {@link EvictionPolicy}. With a handful of samples this approximates the optimal
 * eviction order at a fraction of the cost and none of the bookkeeping.
 *
 * <h2>Allocation discipline</h2>
 * <p>The sampler runs on the command path and must produce <b>zero garbage</b>. It holds the
 * running best candidate in a single {@link DictEntry} reference plus a primitive score —
 * no arrays, no iterators, no boxing. Randomness comes from an inlined xorshift over a {@code long}
 * field, so even the RNG allocates nothing.
 *
 * <p>Owned by the single command thread; no synchronization.
 */
public final class Evictor {

    /** Redis' default sample size for {@code maxmemory-policy allkeys-lru/lfu}. */
    public static final int DEFAULT_SAMPLE_SIZE = 5;

    private final Dict dict;
    private final Db db;
    private final long maxmemory;
    private final int sampleSize;
    private final EvictionPolicy policy;

    /** xorshift state; must be non-zero. Seeded off identity + nanoTime so shards differ. */
    private long rngState;

    public Evictor(Dict dict, Db db, long maxmemory) {
        this(dict, db, maxmemory, DEFAULT_SAMPLE_SIZE, EvictionPolicy.ALLKEYS_LRU);
    }

    public Evictor(Dict dict, Db db, long maxmemory, int sampleSize) {
        this(dict, db, maxmemory, sampleSize, EvictionPolicy.ALLKEYS_LRU);
    }

    public Evictor(Dict dict, Db db, long maxmemory, int sampleSize, EvictionPolicy policy) {
        if (maxmemory <= 0) throw new IllegalArgumentException("maxmemory must be > 0 for eviction");
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be > 0");
        this.dict       = dict;
        this.db         = db;
        this.maxmemory  = maxmemory;
        this.sampleSize = sampleSize;
        this.policy     = policy;
        this.rngState   = (System.nanoTime() ^ (long) sampleSize ^ maxmemory) | 1L;
    }

    /**
     * Evict keys until {@link Db#usedMemory()} is back within {@code maxmemory}
     * (or there is nothing left to evict). Called before each command executes.
     */
    public void evictIfNeeded() {
        while (db.usedMemory() > maxmemory) {
            if (!evictOne()) return;
        }
    }

    private boolean evictOne() {
        DictEntry victim = sampleVictim();
        if (victim == null) return false;
        if (!dict.removeByteKey(victim.key, victim.hash)) return false;
        db.recordEviction();
        return true;
    }

    /**
     * Draw {@code sampleSize} random entries and return the victim according to {@link #policy}:
     * smallest {@code lruTime} for LRU, smallest {@code lfu} for LFU.
     * Allocation-free.
     */
    private DictEntry sampleVictim() {
        DictEntry best   = null;
        long bestScore   = Long.MAX_VALUE; // lower = worse → evict first
        for (int i = 0; i < sampleSize; i++) {
            DictEntry candidate = dict.randomEntry(nextRandom());
            if (candidate == null) continue;
            long score = (policy == EvictionPolicy.ALLKEYS_LFU)
                    ? candidate.lfu
                    : candidate.lruTime;
            if (best == null || score < bestScore) {
                best      = candidate;
                bestScore = score;
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
