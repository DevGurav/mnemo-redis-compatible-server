package dev.devgurav.mnemo.bench;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Stop-the-world control group for {@link dev.devgurav.mnemo.store.Dict}.
 *
 * <p>This is a deliberate, line-for-line clone of the production hash table — same separate
 * chaining, same {@link DictEntryPool} node allocator, same hashing and load factor — with exactly
 * one thing removed: the dual-table incremental rehash. When the load factor is exceeded this class
 * rebuilds the entire table in a single {@link #resize()} call, copying every bucket at once. That
 * one {@code put} therefore pays the full O(n) migration cost and stalls.
 *
 * <p>Keeping every other variable identical (the pool, the chain layout, the hash) is the whole
 * point: any latency difference {@link RehashBenchmark} measures between this and the real
 * {@code Dict} is attributable to the rehash strategy alone.
 *
 * <p><b>Benchmark-only.</b> Lives in {@code src/jmh}, never on the production classpath; it exists
 * solely as the experimental control.
 */
public final class DictSTW {

    private static final int    INITIAL_CAPACITY = 16;
    private static final double LOAD_FACTOR      = 0.75;

    private DictEntry[] table;
    private int size;
    private final DictEntryPool pool;

    public DictSTW() {
        this(INITIAL_CAPACITY);
    }

    public DictSTW(int initialCapacity) {
        if (Integer.bitCount(initialCapacity) != 1) {
            throw new IllegalArgumentException("initialCapacity must be a power of two");
        }
        this.table = new DictEntry[initialCapacity];
        this.pool  = new DictEntryPool();
    }

    public void put(String key, byte[] value) {
        putBytes(key.getBytes(StandardCharsets.UTF_8), key.hashCode(), value);
    }

    public byte[] get(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash = key.hashCode();
        int idx  = (hash & 0x7FFF_FFFF) & (table.length - 1);
        for (DictEntry e = table[idx]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                return e.value;
            }
        }
        return null;
    }

    private void putBytes(byte[] keyBytes, int hash, byte[] value) {
        int idx = (hash & 0x7FFF_FFFF) & (table.length - 1);

        for (DictEntry e = table[idx]; e != null; e = e.next) {
            if (e.hash == hash && Arrays.equals(e.key, keyBytes)) {
                e.value = value;
                return;
            }
        }

        table[idx] = pool.acquire(hash, keyBytes, value, table[idx]);
        size++;

        if (size > LOAD_FACTOR * table.length) {
            resize();
        }
    }

    /**
     * Naive stop-the-world resize: allocate a double-sized table and re-link every existing node
     * into it in one pass. The calling {@code put} bears the entire cost — this is the multi-
     * millisecond stall the incremental engine is designed to avoid.
     */
    private void resize() {
        DictEntry[] old   = table;
        DictEntry[] grown = new DictEntry[old.length << 1];
        for (int i = 0; i < old.length; i++) {
            DictEntry e = old[i];
            while (e != null) {
                DictEntry next = e.next;
                int idx = (e.hash & 0x7FFF_FFFF) & (grown.length - 1);
                e.next     = grown[idx];
                grown[idx] = e;
                e = next;
            }
        }
        table = grown;
    }

    public int size()     { return size; }
    public int capacity() { return table.length; }
}
