package dev.devgurav.mnemo.store;

import java.util.HashMap;
import java.util.Map;

/**
 * The single logical database (keyspace). A key holds exactly one type of value.
 *
 * <p>Three value types are modelled today:
 * <ul>
 *   <li><b>Strings</b> — {@code key → byte[]}, delegated to a {@link KeyValueStore} so the backing
 *       structure ({@link HashMapStore} now, {@link Dict} later) can be swapped without touching
 *       command code.</li>
 *   <li><b>Sorted sets</b> — {@code key → ZSet}, held in a separate namespace map.</li>
 *   <li><b>Hashes</b> — {@code key → Dict}, where the per-hash {@link Dict} maps field → value.
 *       A hash reuses the hand-built hash table wholesale.</li>
 * </ul>
 *
 * <p>The one-type-per-key invariant is enforced at the command layer via {@link #isString} /
 * {@link #isZSet} / {@link #isHash} (a mismatched command returns {@code WRONGTYPE}); {@link #set}
 * overwrites any prior type, and {@link #delete} / {@link #exists} span all namespaces. Folding the
 * namespaces into a single typed {@code Dict} is still deferred — see
 * {@code docs/decisions/0008-hash-type.md}.
 *
 * <p>Owned by the single command thread, so it needs no synchronization (§0.3).
 */
public final class Db {

    private final KeyValueStore store;
    private final Map<String, ZSet> zsets = new HashMap<>();
    private final Map<String, Dict> hashes = new HashMap<>();

    public Db(KeyValueStore store) {
        this.store = store;
    }

    // --- String keyspace ---

    /** Stores a string value, overwriting any prior value of any type under {@code key}. */
    public void set(String key, byte[] value) {
        zsets.remove(key);
        hashes.remove(key);
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

    // --- Hash keyspace ---

    /** The hash (field → value {@link Dict}) at {@code key}, or {@code null} if absent. */
    public Dict hash(String key) { return hashes.get(key); }

    /** The hash at {@code key}, creating an empty one if absent. */
    public Dict hashForWrite(String key) {
        return hashes.computeIfAbsent(key, k -> new Dict());
    }

    // --- Type inspection (one type per key) ---

    public boolean isString(String key) { return store.containsKey(key); }

    public boolean isZSet(String key) { return zsets.containsKey(key); }

    public boolean isHash(String key) { return hashes.containsKey(key); }

    // --- Cross-type key operations ---

    public boolean delete(String key) {
        boolean removedZset   = zsets.remove(key) != null;
        boolean removedHash   = hashes.remove(key) != null;
        boolean removedString = store.remove(key);
        return removedString || removedZset || removedHash;
    }

    public boolean exists(String key) {
        return store.containsKey(key) || zsets.containsKey(key) || hashes.containsKey(key);
    }

    public int size() { return store.size() + zsets.size() + hashes.size(); }

    public void flush() {
        store.clear();
        zsets.clear();
        hashes.clear();
    }
}
