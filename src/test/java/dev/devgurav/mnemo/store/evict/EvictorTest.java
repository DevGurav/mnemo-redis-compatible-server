package dev.devgurav.mnemo.store.evict;

import dev.devgurav.mnemo.store.Db;
import dev.devgurav.mnemo.store.Dict;
import dev.devgurav.mnemo.store.mem.SizeWeigher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the random-sampling LRU evictor bounds the (string) keyspace at {@code maxmemory}: it
 * evicts when over budget, leaves the keyspace alone when under, keeps memory bounded under
 * sustained inserts (so the server cannot OOM), and preferentially spares recently-used keys.
 *
 * <p>Backed by a real {@link Dict} (the only evictable store), exercised directly rather than over
 * the wire so the assertions are deterministic. Plumbing test — runs in {@code ./gradlew test}.
 */
class EvictorTest {

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** A fixed-width key so every entry weighs the same, making the bound arithmetic predictable. */
    private static String key(int i) { return String.format("key%07d", i); }

    private static final byte[] VALUE = bytes("0123456789"); // 10-byte value

    /** Weight of one entry under this test's fixed key/value shape. */
    private static long entryBytes() {
        return SizeWeigher.weigh(bytes(key(0)), VALUE);
    }

    @Test
    void evictsDownToWithinMaxmemory() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 100 * entryBytes();          // room for ~100 entries
        Evictor evictor = new Evictor(dict, db, maxmemory);

        for (int i = 0; i < 500; i++) db.set(key(i), VALUE); // ~5x over budget
        assertThat(db.usedMemory()).isGreaterThan(maxmemory);

        evictor.evictIfNeeded();

        assertThat(db.usedMemory()).isLessThanOrEqualTo(maxmemory);
        assertThat(db.stringCount()).isLessThanOrEqualTo(100);
        assertThat(db.evictedKeys()).isGreaterThan(0);
        // The counter is exact: every key inserted is either still present or was evicted.
        assertThat(db.evictedKeys()).isEqualTo(500 - db.stringCount());
    }

    @Test
    void leavesTheKeyspaceAloneWhenUnderBudget() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        Evictor evictor = new Evictor(dict, db, 1_000 * entryBytes());

        for (int i = 0; i < 100; i++) db.set(key(i), VALUE); // well under the bound
        evictor.evictIfNeeded();

        assertThat(db.stringCount()).isEqualTo(100);
        assertThat(db.evictedKeys()).isZero();
    }

    @Test
    void keepsMemoryBoundedUnderSustainedInsertsSoTheServerCannotOom() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 200 * entryBytes();
        Evictor evictor = new Evictor(dict, db, maxmemory);

        // Mirror the shard hook: evict before every write. Insert far more keys than fit.
        for (int i = 0; i < 50_000; i++) {
            evictor.evictIfNeeded();
            db.set(key(i), VALUE);
        }
        evictor.evictIfNeeded();

        assertThat(db.usedMemory()).isLessThanOrEqualTo(maxmemory);
        // Despite 50k inserts the resident set stays tiny — memory is bounded, not leaking.
        assertThat(db.stringCount()).isLessThanOrEqualTo(200);
        assertThat(db.evictedKeys()).isGreaterThan(49_000);
    }

    @Test
    void sparesARepeatedlyAccessedKey() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 100 * entryBytes();
        Evictor evictor = new Evictor(dict, db, maxmemory);

        db.set("hot", VALUE);
        for (int i = 0; i < 400; i++) {
            db.set(key(i), VALUE);
            db.get("hot");            // keep "hot" the most-recently-used key
            evictor.evictIfNeeded();
        }

        // "hot" has the newest lruTime, so it is never the minimum of any sample while >5 keys remain.
        assertThat(db.isString("hot")).isTrue();
        assertThat(new String(db.get("hot"), StandardCharsets.UTF_8))
                .isEqualTo(new String(VALUE, StandardCharsets.UTF_8));
    }

    // ---- LFU policy ----

    @Test
    void lfuNewKeyStartsAtLfuInit() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        db.set("k", VALUE);
        // After one insert the entry's lfu counter should be LFU_INIT.
        dev.devgurav.mnemo.store.entry.DictEntry entry = dict.randomEntry(0);
        assertThat(entry).isNotNull();
        assertThat(entry.lfu).isEqualTo(dev.devgurav.mnemo.store.entry.DictEntry.LFU_INIT);
    }

    @Test
    void lfuFrequentKeyOutlivesRareKeys() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 100 * entryBytes();
        Evictor evictor = new Evictor(dict, db, maxmemory, Evictor.DEFAULT_SAMPLE_SIZE,
                EvictionPolicy.ALLKEYS_LFU);

        db.set("hot", VALUE);
        // Access "hot" many times so its lfu counter saturates above the cold keys' LFU_INIT.
        for (int i = 0; i < 300; i++) db.get("hot");

        // Insert enough cold keys to push far over budget.
        for (int i = 0; i < 400; i++) {
            db.set(key(i), VALUE);
            evictor.evictIfNeeded();
        }

        // "hot" was accessed hundreds of times; cold keys each touched once. Under LFU, cold keys
        // are evicted first — "hot" must survive.
        assertThat(db.isString("hot")).isTrue();
    }

    @Test
    void lfuKeepsMemoryBoundedUnderSustainedInserts() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 200 * entryBytes();
        Evictor evictor = new Evictor(dict, db, maxmemory, Evictor.DEFAULT_SAMPLE_SIZE,
                EvictionPolicy.ALLKEYS_LFU);

        for (int i = 0; i < 50_000; i++) {
            evictor.evictIfNeeded();
            db.set(key(i), VALUE);
        }
        evictor.evictIfNeeded();

        assertThat(db.usedMemory()).isLessThanOrEqualTo(maxmemory);
        assertThat(db.stringCount()).isLessThanOrEqualTo(200);
        assertThat(db.evictedKeys()).isGreaterThan(49_000);
    }

    @Test
    void evictionUpdatesUsedMemoryConsistentlyWithReinserts() {
        Dict dict = new Dict();
        Db db = new Db(dict);
        long maxmemory = 50 * entryBytes();
        Evictor evictor = new Evictor(dict, db, maxmemory);

        for (int i = 0; i < 300; i++) {
            evictor.evictIfNeeded();
            db.set(key(i), VALUE);
        }
        evictor.evictIfNeeded();

        // usedMemory must equal exactly the surviving keys' weight — no drift across evict/insert churn.
        assertThat(db.usedMemory()).isEqualTo(db.stringCount() * entryBytes());
        assertThat(db.usedMemory()).isLessThanOrEqualTo(maxmemory);
    }
}
