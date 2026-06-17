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

    /**
     * Per-key absolute expiry in epoch milliseconds. Covers all four namespaces.
     * A key absent from this map has no TTL (lives forever until explicitly deleted).
     */
    private final Map<String, Long> expiries = new HashMap<>();

    /** Cumulative keys removed by the evictor; surfaced via {@code INFO}. */
    private long evictedKeys;

    /** Cumulative keys removed by lazy or active TTL expiry; surfaced via {@code INFO}. */
    private long expiredKeys;

    public Db(KeyValueStore store) {
        this.store = store;
    }

    // -------------------------------------------------------------------------
    // Lazy expiry — called before every read and exists/type check
    // -------------------------------------------------------------------------

    /**
     * If {@code key} has a TTL and it has passed, remove the key from every namespace and the expiry
     * map, and increment {@link #expiredKeys}. Returns {@code true} when the key was just expired.
     * Idempotent: a second call on the same key (already deleted) returns {@code false}.
     */
    private boolean expireIfNeeded(String key) {
        Long exp = expiries.get(key);
        if (exp == null) return false;
        if (System.currentTimeMillis() < exp) return false;
        store.remove(key);
        zsets.remove(key);
        hashes.remove(key);
        lists.remove(key);
        expiries.remove(key);
        expiredKeys++;
        return true;
    }

    // -------------------------------------------------------------------------
    // String keyspace
    // -------------------------------------------------------------------------

    /**
     * Stores a string value, overwriting any prior value of any type under {@code key}.
     * {@code SET} always clears any existing TTL, matching Redis semantics.
     */
    public void set(String key, byte[] value) {
        expiries.remove(key); // SET removes any existing TTL
        zsets.remove(key);
        hashes.remove(key);
        lists.remove(key);
        store.put(key, value);
    }

    public byte[] get(String key) {
        expireIfNeeded(key);
        return store.get(key);
    }

    // -------------------------------------------------------------------------
    // Sorted-set keyspace
    // -------------------------------------------------------------------------

    /** The sorted set at {@code key}, or {@code null} if no sorted set is stored there. */
    public ZSet zset(String key) {
        expireIfNeeded(key);
        return zsets.get(key);
    }

    /** The sorted set at {@code key}, creating an empty one if absent. */
    public ZSet zsetForWrite(String key) {
        expireIfNeeded(key);
        return zsets.computeIfAbsent(key, k -> new ZSet());
    }

    // -------------------------------------------------------------------------
    // Hash keyspace
    // -------------------------------------------------------------------------

    /** The hash (field → value {@link Dict}) at {@code key}, or {@code null} if absent. */
    public Dict hash(String key) {
        expireIfNeeded(key);
        return hashes.get(key);
    }

    /** The hash at {@code key}, creating an empty one if absent. */
    public Dict hashForWrite(String key) {
        expireIfNeeded(key);
        return hashes.computeIfAbsent(key, k -> new Dict());
    }

    // -------------------------------------------------------------------------
    // List keyspace
    // -------------------------------------------------------------------------

    /** The list at {@code key}, or {@code null} if no list is stored there. */
    public IntrusiveList list(String key) {
        expireIfNeeded(key);
        return lists.get(key);
    }

    /** The list at {@code key}, creating an empty one if absent. */
    public IntrusiveList listForWrite(String key) {
        expireIfNeeded(key);
        return lists.computeIfAbsent(key, k -> new IntrusiveList());
    }

    // -------------------------------------------------------------------------
    // Type inspection (one type per key)
    // -------------------------------------------------------------------------

    public boolean isString(String key) {
        expireIfNeeded(key);
        return store.containsKey(key);
    }

    public boolean isZSet(String key) {
        expireIfNeeded(key);
        return zsets.containsKey(key);
    }

    public boolean isHash(String key) {
        expireIfNeeded(key);
        return hashes.containsKey(key);
    }

    public boolean isList(String key) {
        expireIfNeeded(key);
        return lists.containsKey(key);
    }

    // -------------------------------------------------------------------------
    // Cross-type key operations
    // -------------------------------------------------------------------------

    public boolean delete(String key) {
        expiries.remove(key);
        boolean removedZset   = zsets.remove(key) != null;
        boolean removedHash   = hashes.remove(key) != null;
        boolean removedList   = lists.remove(key) != null;
        boolean removedString = store.remove(key);
        return removedString || removedZset || removedHash || removedList;
    }

    public boolean exists(String key) {
        expireIfNeeded(key);
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
        expiries.clear();
    }

    // -------------------------------------------------------------------------
    // Per-type key counts (for INFO Keyspace)
    // -------------------------------------------------------------------------

    public int stringCount() { return store.size(); }
    public int zsetCount()   { return zsets.size(); }
    public int hashCount()   { return hashes.size(); }
    public int listCount()   { return lists.size(); }

    // -------------------------------------------------------------------------
    // TTL operations
    // -------------------------------------------------------------------------

    /**
     * Sets the absolute expiry (epoch ms) for {@code key}. Replaces any prior TTL. No-op if the key
     * does not exist. Called by EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT after verifying the key exists.
     */
    public void setExpiry(String key, long absoluteMs) {
        expiries.put(key, absoluteMs);
    }

    /**
     * Removes the TTL for {@code key} (PERSIST). Returns {@code true} if a TTL was present and
     * removed; {@code false} if the key has no TTL or does not exist.
     */
    public boolean removeExpiry(String key) {
        return expiries.remove(key) != null;
    }

    /**
     * Returns the remaining TTL in milliseconds for {@code key}, or:
     * <ul>
     *   <li>{@code -1} — the key exists but has no TTL (persistent)</li>
     *   <li>{@code -2} — the key does not exist (or has just been lazily expired)</li>
     * </ul>
     * Performs lazy expiry before the check.
     */
    public long remainingTtlMs(String key) {
        if (expireIfNeeded(key)) return -2;
        if (!exists(key))        return -2;
        Long exp = expiries.get(key);
        if (exp == null)         return -1;
        long rem = exp - System.currentTimeMillis();
        return rem <= 0 ? -2 : rem;
    }

    /**
     * Active expiry: scans up to {@code maxSample} entries in the expiry map and deletes any that
     * have already passed. Called by {@link dev.devgurav.mnemo.ttl.TtlSweeper} on the shard thread
     * before each command. Does not allocate if no keys are expired (early exit).
     */
    public void sweepExpiredKeys(int maxSample) {
        if (expiries.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> toDelete = null;
        int sampled = 0;
        for (Map.Entry<String, Long> entry : expiries.entrySet()) {
            if (sampled++ >= maxSample) break;
            if (now >= entry.getValue()) {
                if (toDelete == null) toDelete = new ArrayList<>();
                toDelete.add(entry.getKey());
            }
        }
        if (toDelete == null) return;
        for (String key : toDelete) {
            store.remove(key);
            zsets.remove(key);
            hashes.remove(key);
            lists.remove(key);
            expiries.remove(key);
            expiredKeys++;
        }
    }

    // -------------------------------------------------------------------------
    // Keyspace scan
    // -------------------------------------------------------------------------

    /**
     * Returns all live (non-expired) keys in all four namespaces that satisfy {@code filter}.
     * Used by the {@code KEYS} command.
     */
    public List<String> keys(Predicate<String> filter) {
        long now = System.currentTimeMillis();
        List<String> result = new ArrayList<>();
        Consumer<String> collect = key -> {
            Long exp = expiries.get(key);
            if (exp != null && now >= exp) return; // skip expired (lazy clean-up deferred)
            if (filter.test(key)) result.add(key);
        };
        store.forEachKey(collect);
        zsets.keySet().forEach(collect);
        hashes.keySet().forEach(collect);
        lists.keySet().forEach(collect);
        return result;
    }

    // -------------------------------------------------------------------------
    // Memory accounting (for maxmemory / INFO)
    // -------------------------------------------------------------------------

    /**
     * The running logical size in bytes of the evictable (string) keyspace — maintained by the
     * backing {@link KeyValueStore} on every put/remove, so the read is O(1). This is the figure the
     * {@code maxmemory} bound and {@code INFO}'s {@code used_memory} are measured against.
     */
    public long usedMemory()      { return store.usedMemory(); }

    /** Cumulative keys removed by the evictor since startup. */
    public long evictedKeys()     { return evictedKeys; }

    /** Cumulative keys removed by TTL expiry (lazy or active) since startup. */
    public long expiredKeys()     { return expiredKeys; }

    /** Called by the evictor after it deletes a sampled victim. */
    public void recordEviction()  { evictedKeys++; }
}
