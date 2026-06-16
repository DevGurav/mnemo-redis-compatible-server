package dev.devgurav.mnemo.store;

/**
 * The single logical database (keyspace). Week 1: string keys → {@code byte[]} string values.
 *
 * <p>Owned by the single command thread, so it needs no synchronization (§0.3). It delegates
 * storage to a {@link KeyValueStore} so the backing structure ({@link HashMapStore} now,
 * {@link Dict} later) can be swapped without touching command code.
 */
public final class Db {

    private final KeyValueStore store;

    public Db(KeyValueStore store) {
        this.store = store;
    }

    public void set(String key, byte[] value) { store.put(key, value); }

    public byte[] get(String key) { return store.get(key); }

    public boolean delete(String key) { return store.remove(key); }

    public boolean exists(String key) { return store.containsKey(key); }

    public int size() { return store.size(); }

    public void flush() { store.clear(); }
}
