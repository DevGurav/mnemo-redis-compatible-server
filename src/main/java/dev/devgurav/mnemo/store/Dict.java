package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Dict — a separate-chaining hash table with incremental rehashing and a pooled node allocator.
 *
 * <h2>Dual-table incremental rehash</h2>
 * <p>Two bucket arrays coexist during a resize:
 * <ul>
 *   <li>{@code ht[0]} — the "old" table; entries drain out one bucket at a time.</li>
 *   <li>{@code ht[1]} — the "new" table (double capacity); entries arrive here.</li>
 * </ul>
 * {@code rehashidx} is the next {@code ht[0]} bucket to migrate ({@code -1} = idle).
 * Every mutating operation (put / remove) piggy-backs one {@code rehashStep} call so that
 * the migration spreads across normal traffic instead of one stop-the-world pause.
 * Reads (get) probe {@code ht[0]} first, then {@code ht[1]} on a miss while a rehash is active.
 *
 * <h2>Pool discipline during migration</h2>
 * <p>Nodes are re-linked directly from {@code ht[0]} chains into {@code ht[1]} chains.
 * Neither {@link DictEntryPool#acquire} nor {@link DictEntryPool#release} is called during
 * migration — the existing {@link DictEntry} objects are re-used in-place, so pool occupancy
 * and GC allocation pressure are unchanged across a resize.
 */
public final class Dict implements KeyValueStore {

    private static final int    INITIAL_CAPACITY = 16;
    private static final double LOAD_FACTOR      = 0.75;

    /**
     * Dual hash-table array.
     * {@code ht[0]} is always the primary (live) table.
     * {@code ht[1]} is non-null only while a rehash is in progress.
     */
    private DictEntry[][] ht = new DictEntry[2][];

    /** Number of live key–value mappings across both tables combined. */
    private int size;

    /**
     * Incremental-rehash cursor.
     * {@code -1}   → no rehash in progress.
     * {@code >= 0} → index of the next {@code ht[0]} bucket to migrate into {@code ht[1]}.
     */
    private int rehashidx = -1;

    /** Node allocator — acquire on insert, release on remove/clear. */
    private final DictEntryPool pool;

    public Dict() {
        this(INITIAL_CAPACITY);
    }

    public Dict(int initialCapacity) {
        if (Integer.bitCount(initialCapacity) != 1) {
            throw new IllegalArgumentException("initialCapacity must be a power of two");
        }
        ht[0] = new DictEntry[initialCapacity];
        ht[1] = null;
        this.pool = new DictEntryPool();
    }

    // -------------------------------------------------------------------------
    // KeyValueStore — public API (String → bytes bridge)
    // -------------------------------------------------------------------------

    @Override
    public void put(String key, byte[] value) {
        putBytes(key.getBytes(StandardCharsets.UTF_8), key.hashCode(), value);
    }

    @Override
    public byte[] get(String key) {
        return getBytes(key.getBytes(StandardCharsets.UTF_8), key.hashCode());
    }

    @Override
    public boolean remove(String key) {
        return removeBytes(key.getBytes(StandardCharsets.UTF_8), key.hashCode());
    }

    @Override
    public boolean containsKey(String key) {
        return get(key) != null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        for (int t = 0; t < 2; t++) {
            if (ht[t] == null) continue;
            for (int i = 0; i < ht[t].length; i++) {
                DictEntry e = ht[t][i];
                while (e != null) {
                    DictEntry next = e.next;
                    pool.release(e);
                    e = next;
                }
                ht[t][i] = null;
            }
        }
        ht[1]     = null;
        rehashidx = -1;
        size      = 0;
    }

    // -------------------------------------------------------------------------
    // Internal byte-level operations
    // -------------------------------------------------------------------------

    /**
     * Insert or overwrite a key.
     *
     * <p>Routing rules during rehash:
     * <ul>
     *   <li>Overwrite: search {@code ht[0]} then {@code ht[1]}; update the node that holds the key.</li>
     *   <li>New key: insert into {@code ht[1]} so the entry is never lost on table promotion.</li>
     * </ul>
     */
    private void putBytes(byte[] keyBytes, int hash, byte[] value) {
        if (isRehashing()) rehashStep(1);

        // idx0 is computed AFTER rehashStep: if rehash just completed, ht[0] is now the promoted
        // table and its length has doubled — recomputing here uses the correct capacity.
        int idx0 = (hash & 0x7FFF_FFFF) & (ht[0].length - 1);

        // Search ht[0] for an existing entry to overwrite.
        for (DictEntry e = ht[0][idx0]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                e.value = value;
                return;
            }
        }

        if (isRehashing()) {
            int idx1 = (hash & 0x7FFF_FFFF) & (ht[1].length - 1);

            // Search ht[1] for an existing entry to overwrite (may have been migrated already).
            for (DictEntry e = ht[1][idx1]; e != null; e = e.next) {
                if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                    e.value = value;
                    return;
                }
            }

            // New key during rehash: always insert into ht[1] so the entry survives promotion.
            ht[1][idx1] = pool.acquire(hash, keyBytes, value, ht[1][idx1]);
        } else {
            ht[0][idx0] = pool.acquire(hash, keyBytes, value, ht[0][idx0]);
        }

        size++;

        // Only trigger a new rehash when no rehash is already in progress.
        if (!isRehashing() && size > LOAD_FACTOR * ht[0].length) {
            startRehash();
        }
    }

    /**
     * Look up a key. Probes {@code ht[0]} first, then {@code ht[1]} on a miss during rehash.
     *
     * <p>Reads do not advance the rehash cursor: they impose no latency cost beyond a second
     * bucket probe, which is bounded O(1) per table.
     */
    private byte[] getBytes(byte[] keyBytes, int hash) {
        int idx0 = (hash & 0x7FFF_FFFF) & (ht[0].length - 1);
        for (DictEntry e = ht[0][idx0]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                return e.value;
            }
        }

        if (isRehashing()) {
            int idx1 = (hash & 0x7FFF_FFFF) & (ht[1].length - 1);
            for (DictEntry e = ht[1][idx1]; e != null; e = e.next) {
                if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                    return e.value;
                }
            }
        }

        return null;
    }

    /**
     * Remove a key from whichever table holds it.
     *
     * <p>Probes {@code ht[0]} first; falls back to {@code ht[1]} on a miss during rehash.
     * The deleted node is returned to the pool so the slot can be reused on the next insert.
     */
    private boolean removeBytes(byte[] keyBytes, int hash) {
        if (isRehashing()) rehashStep(1);

        // idx0 computed after rehashStep for the same reason as in putBytes.
        int idx0 = (hash & 0x7FFF_FFFF) & (ht[0].length - 1);

        DictEntry prev = null;
        for (DictEntry e = ht[0][idx0]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                if (prev == null) ht[0][idx0] = e.next;
                else              prev.next    = e.next;
                pool.release(e);
                size--;
                return true;
            }
            prev = e;
        }

        if (isRehashing()) {
            int idx1 = (hash & 0x7FFF_FFFF) & (ht[1].length - 1);
            prev = null;
            for (DictEntry e = ht[1][idx1]; e != null; e = e.next) {
                if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                    if (prev == null) ht[1][idx1] = e.next;
                    else              prev.next    = e.next;
                    pool.release(e);
                    size--;
                    return true;
                }
                prev = e;
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Incremental rehashing engine
    // -------------------------------------------------------------------------

    /**
     * Migrate up to {@code steps} non-empty buckets from {@code ht[0]} to {@code ht[1]}.
     *
     * <h3>Empty-bucket cap</h3>
     * <p>On a sparse table, most buckets are null. Scanning them all in one call would stall
     * the calling thread. The cap ({@code steps × 10}) limits how many consecutive empty buckets
     * are visited before the method returns early. Empty skips do NOT consume the {@code steps}
     * budget — only migrating a non-empty bucket costs a step.
     *
     * <h3>Node re-linking (no pool operations)</h3>
     * <p>Nodes are spliced directly: each {@link DictEntry} in an {@code ht[0]} chain has its
     * {@code next} pointer updated to prepend it to the correct {@code ht[1]} chain. No new
     * objects are allocated and no nodes are returned to the pool during migration.
     *
     * <h3>Completion</h3>
     * <p>Once {@code rehashidx} reaches {@code ht[0].length}, all buckets have been drained.
     * {@code ht[1]} is promoted to {@code ht[0]}, {@code ht[1]} is nulled, and {@code rehashidx}
     * resets to {@code -1}.
     *
     * @param steps maximum number of non-empty buckets to migrate in this call
     */
    private void rehashStep(int steps) {
        int emptyVisitsCap = steps * 10; // bound on consecutive null-bucket skips
        int emptyVisits    = 0;

        while (steps > 0 && rehashidx < ht[0].length) {
            // Skip empty buckets without consuming the steps budget.
            if (ht[0][rehashidx] == null) {
                rehashidx++;
                if (++emptyVisits > emptyVisitsCap) break;
                continue;
            }

            // Migrate every node in this non-empty bucket into ht[1].
            // Traverse with a local pointer; e.next is overwritten as the new ht[1] chain head.
            DictEntry e = ht[0][rehashidx];
            while (e != null) {
                DictEntry next = e.next;                                    // save before overwrite
                int newIdx = (e.hash & 0x7FFF_FFFF) & (ht[1].length - 1);
                e.next       = ht[1][newIdx];                              // prepend to ht[1] chain
                ht[1][newIdx] = e;
                e = next;
            }
            ht[0][rehashidx] = null; // bucket is fully drained
            rehashidx++;
            steps--;
        }

        // Promote ht[1] → ht[0] once all original buckets have been drained.
        if (rehashidx >= ht[0].length) {
            ht[0]     = ht[1];
            ht[1]     = null;
            rehashidx = -1;
        }
    }

    /**
     * Allocate {@code ht[1]} at double the capacity of {@code ht[0]} and arm the cursor.
     * No-op if a rehash is already active.
     */
    private void startRehash() {
        if (isRehashing()) return;
        ht[1]     = new DictEntry[ht[0].length << 1];
        rehashidx = 0;
    }

    /** {@code true} while an incremental rehash is in progress. */
    boolean isRehashing() {
        return rehashidx != -1;
    }

    // -------------------------------------------------------------------------
    // Iteration
    // -------------------------------------------------------------------------

    /**
     * Visit every live key–value mapping exactly once, passing the raw key and value bytes to
     * {@code action}.
     *
     * <h3>Dual-table correctness</h3>
     * <p>An entry lives in exactly one table at any moment: a rehash <em>moves</em> nodes from
     * {@code ht[0]} to {@code ht[1]} (never copies), and new keys inserted mid-rehash go straight
     * into {@code ht[1]}. So iterating all of {@code ht[0]} and — while a rehash is active — all of
     * {@code ht[1]} yields each entry once and none twice. The {@code ht[0]} buckets below
     * {@code rehashidx} are already drained to {@code null} by {@link #rehashStep}, so they are
     * skipped naturally; this is why a primed-then-mid-rehash table still enumerates completely.
     *
     * <h3>Concurrency</h3>
     * <p>Read-only and single-threaded (the keyspace is owned by one command thread), so there is no
     * concurrent-modification hazard. {@code action} must not mutate this {@code Dict}.
     */
    public void forEach(BiConsumer<byte[], byte[]> action) {
        forEachTable(ht[0], action);
        if (isRehashing()) {
            forEachTable(ht[1], action);
        }
    }

    private static void forEachTable(DictEntry[] table, BiConsumer<byte[], byte[]> action) {
        if (table == null) return;
        for (DictEntry head : table) {
            for (DictEntry e = head; e != null; e = e.next) {
                action.accept(e.key, e.value);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Package-visible accessors (tests / ShardExecutor metrics)
    // -------------------------------------------------------------------------

    DictEntryPool pool()       { return pool; }
    int capacity()             { return ht[0].length; }
    int rehashIndex()          { return rehashidx; }
    DictEntry[] table(int idx) { return ht[idx]; }
}
