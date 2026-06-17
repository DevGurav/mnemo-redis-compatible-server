package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.mem.SizeWeigher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEMPORARY placeholder keyspace backed by {@link java.util.HashMap}, so the server runs
 * end-to-end today.
 *
 * <p>Replace it with your own {@link Dict} by setting {@code MNEMO_USE_DICT=true} once
 * {@code DictTest} / {@code DictPropertyTest} are green. This class is intentionally trivial — it is
 * NOT part of the work you implement; it only exists to keep the server runnable while you build the
 * real hash table.
 *
 * <p>It tracks {@link #usedMemory()} the same way {@link Dict} does (so {@code INFO} works under
 * either store), but it is <em>not</em> evictable — the random-sampling LRU evictor reads
 * {@code Dict} bucket arrays directly and is only wired up when the store is a {@code Dict}.
 */
public final class HashMapStore implements KeyValueStore {

    private final Map<String, byte[]> map = new HashMap<>();
    private final AtomicLong usedMemory = new AtomicLong();

    @Override public void put(String key, byte[] value) {
        byte[] old = map.put(key, value);
        long delta = SizeWeigher.weigh(key, value);
        if (old != null) delta -= SizeWeigher.weigh(key, old); // overwrite: charge only the difference
        usedMemory.addAndGet(delta);
    }

    @Override public byte[] get(String key) { return map.get(key); }

    @Override public boolean remove(String key) {
        byte[] old = map.remove(key);
        if (old == null) return false;
        usedMemory.addAndGet(-SizeWeigher.weigh(key, old));
        return true;
    }

    @Override public boolean containsKey(String key) { return map.containsKey(key); }

    @Override public int size() { return map.size(); }

    @Override public long usedMemory() { return usedMemory.get(); }

    @Override public void clear() {
        map.clear();
        usedMemory.set(0);
    }
}
