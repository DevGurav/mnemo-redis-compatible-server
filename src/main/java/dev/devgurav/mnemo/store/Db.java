package dev.devgurav.mnemo.store;

import java.util.HashMap;
import java.util.Map;

/**
 * The single logical database (keyspace). A key holds exactly one type of value.
 *
 * <p>Two value types are modelled today:
 * <ul>
 *   <li><b>Strings</b> — {@code key → byte[]}, delegated to a {@link KeyValueStore} so the backing
 *       structure ({@link HashMapStore} now, {@link Dict} later) can be swapped without touching
 *       command code.</li>
 *   <li><b>Sorted sets</b> — {@code key → ZSet}, held in a separate namespace map.</li>
 * </ul>
 *
 * <p>The one-type-per-key invariant is enforced at the command layer via {@link #isString} /
 * {@link #isZSet} (a mismatched command returns {@code WRONGTYPE}); {@link #set} overwrites any
 * prior type, and {@link #delete} / {@link #exists} span both namespaces. Folding both namespaces
 * into a single typed {@code Dict} is deferred — see {@code docs/decisions/0007-typed-keyspace.md}.
 *
 * <p>Owned by the single command thread, so it needs no synchronization (§0.3).
 */
public final class Db {

    private final KeyValueStore store;
    private final Map<String, ZSet> zsets = new HashMap<>();

    public Db(KeyValueStore store) {
        this.store = store;
    }

    // --- String keyspace ---

    /** Stores a string value, overwriting any prior value of any type under {@code key}. */
    public void set(String key, byte[] value) {
        zsets.remove(key);
        store.put(key, value);
    }

    public byte[] get(String key) { return store.get(key); }

    // --- Sorted-set keyspace ---

    /** The sorted set at {@code key}, or {@code null} if no sorted set is stored there. */
    public ZSet zset(String key) { return zsets.get(key); }

    /** The sorted set at {@code key}, creating an empty one if absent. */
    public ZSet zsetForWrite(String key) {
        return zsets.computeIfAbsent(key, k -> new ZSet());
    }

    // --- Type inspection (one type per key) ---

    public boolean isString(String key) { return store.containsKey(key); }

    public boolean isZSet(String key) { return zsets.containsKey(key); }

    // --- Cross-type key operations ---

    public boolean delete(String key) {
        boolean removedZset  = zsets.remove(key) != null;
        boolean removedString = store.remove(key);
        return removedString || removedZset;
    }

    public boolean exists(String key) { return store.containsKey(key) || zsets.containsKey(key); }

    public int size() { return store.size() + zsets.size(); }

    public void flush() {
        store.clear();
        zsets.clear();
    }
}
