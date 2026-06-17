package dev.devgurav.mnemo.store;

import java.util.function.Consumer;

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

    /** Visit every key in the store exactly once. The {@code action} must not mutate the store. */
    void forEachKey(Consumer<String> action);

    /**
     * The running logical size of this keyspace in bytes — the sum over every live mapping of
     * {@code key + value + per-entry overhead} ({@link dev.devgurav.mnemo.store.mem.SizeWeigher}).
     * Maintained incrementally on put/remove/clear so the read is O(1); it backs the {@code maxmemory}
     * bound and {@code INFO}'s {@code used_memory}.
     */
    long usedMemory();

    void clear();
}
