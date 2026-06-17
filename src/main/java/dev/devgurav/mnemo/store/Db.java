package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.list.IntrusiveList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 *   <li><b>Lists</b> — {@code key → IntrusiveList}, a hand-built doubly-linked list with a recycling
 *       node pool, held in a fourth namespace map.</li>
 * </ul>
 *
 * <p>The one-type-per-key invariant is enforced at the command layer via {@link #isString} /
 * {@link #isZSet} / {@link #isHash} / {@link #isList} (a mismatched command returns
 * {@code WRONGTYPE}); {@link #set} overwrites any prior type, and {@link #delete} / {@link #exists}
 * span all namespaces. As {@code docs/decisions/0008-hash-type.md} foresaw at the fourth type, the
 * cross-type guard is now repeated in every command; folding the namespaces into a single typed
 * {@code Dict} is deliberately deferred to the Week-4 keyspace/sharding rework rather than bolted on
 * here — see {@code docs/decisions/0009-list-type-and-info.md}.
 *
 * <p>Owned by the single command thread, so it needs no synchronization (§0.3).
 */
public final class Db {

    private final KeyValueStore store;
    private final Map<String, ZSet> zsets = new HashMap<>();
    private final Map<String, Dict> hashes = new HashMap<>();
    private final Map<String, IntrusiveList> lists = new HashMap<>();

    /** Cumulative count of keys removed by the evictor; surfaced via {@code INFO}. Single-thread owned. */
    private long evictedKeys;

    public Db(KeyValueStore store) {
        this.store = store;
    }

    // --- String keyspace ---

    /** Stores a string value, overwriting any prior value of any type under {@code key}. */
    public void set(String key, byte[] value) {
        zsets.remove(key);
        hashes.remove(key);
        lists.remove(key);
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

    // --- List keyspace ---

    /** The list at {@code key}, or {@code null} if no list is stored there. */
    public IntrusiveList list(String key) { return lists.get(key); }

    /** The list at {@code key}, creating an empty one if absent. */
    public IntrusiveList listForWrite(String key) {
        return lists.computeIfAbsent(key, k -> new IntrusiveList());
    }

    // --- Type inspection (one type per key) ---

    public boolean isString(String key) { return store.containsKey(key); }

    public boolean isZSet(String key) { return zsets.containsKey(key); }

    public boolean isHash(String key) { return hashes.containsKey(key); }

    public boolean isList(String key) { return lists.containsKey(key); }

    // --- Cross-type key operations ---

    public boolean delete(String key) {
        boolean removedZset   = zsets.remove(key) != null;
        boolean removedHash   = hashes.remove(key) != null;
        boolean removedList   = lists.remove(key) != null;
        boolean removedString = store.remove(key);
        return removedString || removedZset || removedHash || removedList;
    }

    public boolean exists(String key) {
        return store.containsKey(key)
                || zsets.containsKey(key)
                || hashes.containsKey(key)
                || lists.containsKey(key);
    }

    public int size() { return store.size() + zsets.size() + hashes.size() + lists.size(); }

    public void flush() {
        store.clear();
        zsets.clear();
        hashes.clear();
        lists.clear();
    }

    // --- Per-type key counts (for INFO Keyspace) ---

    public int stringCount() { return store.size(); }

    public int zsetCount() { return zsets.size(); }

    public int hashCount() { return hashes.size(); }

    public int listCount() { return lists.size(); }

    // --- Keyspace scan ---

    /**
     * Returns all keys in all four namespaces that satisfy {@code filter}.
     * Used by the {@code KEYS} command: the filter is a glob-pattern predicate built in the command
     * layer so that {@code Db} stays decoupled from pattern-matching logic.
     */
    public List<String> keys(Predicate<String> filter) {
        List<String> result = new ArrayList<>();
        Consumer<String> collect = key -> { if (filter.test(key)) result.add(key); };
        store.forEachKey(collect);
        zsets.keySet().forEach(collect);
        hashes.keySet().forEach(collect);
        lists.keySet().forEach(collect);
        return result;
    }

    // --- Memory accounting (for maxmemory / INFO) ---

    /**
     * The running logical size in bytes of the evictable (string) keyspace — maintained by the
     * backing {@link KeyValueStore} on every put/remove, so the read is O(1). This is the figure the
     * {@code maxmemory} bound and {@code INFO}'s {@code used_memory} are measured against. Sorted
     * sets, hashes, and lists are not weighed here: the random-sampling LRU evictor frees only string
     * keys, so the bound it enforces is defined over exactly what it can reclaim.
     */
    public long usedMemory() { return store.usedMemory(); }

    /** Cumulative number of keys removed by the evictor since startup (an {@code INFO} stat). */
    public long evictedKeys() { return evictedKeys; }

    /** Called by the evictor after it deletes a sampled victim, to advance the {@code INFO} stat. */
    public void recordEviction() { evictedKeys++; }
}
