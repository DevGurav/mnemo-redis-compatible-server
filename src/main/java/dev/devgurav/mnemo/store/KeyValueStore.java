package dev.devgurav.mnemo.store;

/**
 * The keyspace contract: a string-keyed, {@code byte[]}-valued map.
 *
 * <p>Two implementations exist: {@link HashMapStore} (a temporary placeholder so the server runs
 * today) and {@link Dict} (YOUR from-scratch hash table). The server picks one via
 * {@link dev.devgurav.mnemo.server.Config}.
 */
public interface KeyValueStore {

    void put(String key, byte[] value);

    byte[] get(String key);

    /** @return {@code true} if a mapping for {@code key} existed and was removed. */
    boolean remove(String key);

    boolean containsKey(String key);

    int size();

    void clear();
}
