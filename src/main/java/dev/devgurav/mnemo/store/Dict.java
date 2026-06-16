package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dict — a separate-chaining hash table with an object-pooled node allocator.
 *
 * <p>The public API accepts {@code String} keys; internally, keys are stored as UTF-8
 * {@code byte[]} inside {@link DictEntry} so the pool node layout stays allocation-friendly.
 * Hash code comes from {@link String#hashCode()} (pre-computed by the JDK); key equality uses
 * {@link Arrays#equals} on the stored bytes.
 *
 * <h2>Week 1 — correctness (see DictTest)</h2>
 * <ul>
 *   <li>Bucket index: {@code (hash & 0x7FFF_FFFF) & (buckets.length - 1)}. The sign-bit mask
 *       keeps the index positive for negative hash codes; the length mask is O(1) because
 *       capacity is always a power of two.</li>
 *   <li>{@code put}: overwrite on existing key (size unchanged); prepend new node on miss.</li>
 *   <li>Resize at {@code size > LOAD_FACTOR * buckets.length}: double capacity, re-link
 *       existing nodes (no pool churn — nodes are re-used in-place).</li>
 * </ul>
 *
 * <h2>Week 2 — incremental rehashing</h2>
 * Replace the stop-the-world resize with dual tables ({@code ht[0]}/{@code ht[1]}),
 * migrating a bounded number of buckets per operation to flatten p99 (see architecture-spec.md §2).
 */
public final class Dict implements KeyValueStore {

    private static final int    INITIAL_CAPACITY = 16;
    private static final double LOAD_FACTOR      = 0.75;

    /** Bucket array; each slot is the head of a linked chain of {@link DictEntry} nodes. */
    private DictEntry[] buckets;

    /** Number of live key–value mappings in the table. */
    private int size;

    /** Node allocator — acquire before inserting, release on remove/clear. */
    private final DictEntryPool pool;

    public Dict() {
        this(INITIAL_CAPACITY);
    }

    public Dict(int initialCapacity) {
        if (Integer.bitCount(initialCapacity) != 1) {
            throw new IllegalArgumentException("initialCapacity must be a power of two");
        }
        this.buckets = new DictEntry[initialCapacity];
        this.pool    = new DictEntryPool();
    }

    @Override
    public void put(String key, byte[] value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash  = key.hashCode();
        int index = (hash & 0x7FFF_FFFF) & (buckets.length - 1);

        // Overwrite path: walk chain, update value in-place if key already present.
        DictEntry current = buckets[index];
        while (current != null) {
            if (current.hash == hash && Arrays.equals(current.key, keyBytes)) {
                current.value = value;
                return;
            }
            current = current.next;
        }

        // Insert path: acquire a node from the pool and prepend to bucket chain head (O(1)).
        DictEntry newEntry = pool.acquire(hash, keyBytes, value, buckets[index]);
        buckets[index] = newEntry;
        size++;

        if (size > LOAD_FACTOR * buckets.length) {
            resize();
        }
    }

    @Override
    public byte[] get(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash  = key.hashCode();
        int index = (hash & 0x7FFF_FFFF) & (buckets.length - 1);

        DictEntry current = buckets[index];
        while (current != null) {
            if (current.hash == hash && Arrays.equals(current.key, keyBytes)) {
                return current.value;
            }
            current = current.next;
        }
        return null;
    }

    @Override
    public boolean remove(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash  = key.hashCode();
        int index = (hash & 0x7FFF_FFFF) & (buckets.length - 1);

        DictEntry current = buckets[index];
        DictEntry prev    = null;

        while (current != null) {
            if (current.hash == hash && Arrays.equals(current.key, keyBytes)) {
                if (prev == null) {
                    buckets[index] = current.next; // was the chain head
                } else {
                    prev.next = current.next;       // splice out from middle or tail
                }
                pool.release(current);
                size--;
                return true;
            }
            prev    = current;
            current = current.next;
        }
        return false;
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
        for (int i = 0; i < buckets.length; i++) {
            DictEntry e = buckets[i];
            while (e != null) {
                DictEntry next = e.next;
                pool.release(e);
                e = next;
            }
            buckets[i] = null;
        }
        size = 0;
    }

    // --- Package-visible accessors for testing and future ShardExecutor metrics ---

    DictEntryPool pool() { return pool; }

    int capacity() { return buckets.length; }

    // --- Private helpers ---

    /**
     * Stop-the-world resize: double the bucket array and re-link all existing nodes into
     * the new table. Nodes are re-used in-place (no pool acquire/release) so the pool
     * occupancy is unchanged after resize.
     */
    private void resize() {
        int newCapacity = buckets.length << 1;
        DictEntry[] newBuckets = new DictEntry[newCapacity];

        for (DictEntry head : buckets) {
            DictEntry e = head;
            while (e != null) {
                DictEntry next = e.next;
                int newIndex = (e.hash & 0x7FFF_FFFF) & (newCapacity - 1);
                // Prepend e to the chain at newBuckets[newIndex].
                e.next = newBuckets[newIndex];
                newBuckets[newIndex] = e;
                e = next;
            }
        }
        buckets = newBuckets;
    }
}
