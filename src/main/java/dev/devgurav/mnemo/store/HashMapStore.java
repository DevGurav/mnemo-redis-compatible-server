package dev.devgurav.mnemo.store;

import java.util.HashMap;
import java.util.Map;

/**
 * TEMPORARY placeholder keyspace backed by {@link java.util.HashMap}, so the server runs
 * end-to-end today.
 *
 * <p>Replace it with your own {@link Dict} by setting {@code MNEMO_USE_DICT=true} once
 * {@code DictTest} / {@code DictPropertyTest} are green. This class is intentionally trivial — it is
 * NOT part of the work you implement; it only exists to keep the server runnable while you build the
 * real hash table.
 */
public final class HashMapStore implements KeyValueStore {

    private final Map<String, byte[]> map = new HashMap<>();

    @Override public void put(String key, byte[] value) { map.put(key, value); }

    @Override public byte[] get(String key) { return map.get(key); }

    @Override public boolean remove(String key) { return map.remove(key) != null; }

    @Override public boolean containsKey(String key) { return map.containsKey(key); }

    @Override public int size() { return map.size(); }

    @Override public void clear() { map.clear(); }
}
