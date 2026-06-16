package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC (red) for the hash table YOU implement in {@link Dict} and the pool YOU implement in
 * {@link DictEntryPool}.
 *
 * <p>Tagged {@code "spec"} so it is excluded from {@code ./gradlew test} (keeping CI green)
 * and run via {@code ./gradlew specTest}. Make every test here pass without delegating to
 * {@link java.util.HashMap}.
 *
 * <p>Test groups:
 * <ul>
 *   <li>§A — Basic correctness: put, get, overwrite, remove, clear.</li>
 *   <li>§B — Collision resolution: multiple entries per bucket, ordering after inserts/removes.</li>
 *   <li>§C — Resize correctness: entries survive a full rehash.</li>
 *   <li>§D — Pool discipline: node reuse, null-on-free invariant.</li>
 * </ul>
 */
@Tag("spec")
class DictTest {

    // ==========================================================================
    // §A — Basic correctness
    // ==========================================================================

    @Test
    void getReturnsNullForMissingKey() {
        Dict dict = new Dict();
        assertThat(dict.get("absent")).isNull();
        assertThat(dict.containsKey("absent")).isFalse();
        assertThat(dict.size()).isZero();
    }

    @Test
    void putThenGet() {
        Dict dict = new Dict();
        dict.put("k", bytes("v"));
        assertThat(dict.get("k")).isEqualTo(bytes("v"));
        assertThat(dict.containsKey("k")).isTrue();
        assertThat(dict.size()).isEqualTo(1);
    }

    @Test
    void putOverwritesValueAndKeepsSizeStable() {
        Dict dict = new Dict();
        dict.put("k", bytes("v1"));
        dict.put("k", bytes("v2"));
        assertThat(dict.get("k")).isEqualTo(bytes("v2"));
        assertThat(dict.size()).isEqualTo(1);
    }

    @Test
    void removeReportsWhetherKeyWasPresent() {
        Dict dict = new Dict();
        dict.put("k", bytes("v"));
        assertThat(dict.remove("k")).isTrue();
        assertThat(dict.remove("k")).isFalse();
        assertThat(dict.get("k")).isNull();
        assertThat(dict.size()).isZero();
    }

    @Test
    void clearEmptiesTheTable() {
        Dict dict = new Dict();
        dict.put("a", bytes("1"));
        dict.put("b", bytes("2"));
        dict.clear();
        assertThat(dict.size()).isZero();
        assertThat(dict.get("a")).isNull();
        assertThat(dict.get("b")).isNull();
    }

    // ==========================================================================
    // §B — Collision resolution
    // ==========================================================================

    @Test
    void testCollisionResolution() {
        // A capacity-4 table with 20 inserts guarantees that multiple keys share the same bucket
        // long before the first resize. If chain traversal is broken — e.g. new entries silently
        // overwrite the head pointer instead of being prepended, or get() stops at the first node
        // — some entries become unreachable and this test fails.
        Dict d = new Dict(4);
        int n = 20;
        for (int i = 0; i < n; i++) {
            d.put("collision-key-" + i, bytes("val-" + i));
        }
        assertThat(d.size()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertThat(d.get("collision-key-" + i))
                    .as("entry %d must be reachable after chain collisions", i)
                    .isEqualTo(bytes("val-" + i));
        }
    }

    @Test
    void removeFromMiddleOfChainLeavesRemainingEntriesAccessible() {
        // Insert three keys that the test forces into the same bucket by using a tiny capacity.
        // Removing the middle node must not corrupt the chain: the head and tail must survive.
        Dict d = new Dict(2); // 2 buckets → guaranteed collisions
        d.put("a", bytes("1"));
        d.put("b", bytes("2"));
        d.put("c", bytes("3"));

        assertThat(d.remove("b")).isTrue();

        assertThat(d.get("a")).isEqualTo(bytes("1"));
        assertThat(d.get("b")).isNull();
        assertThat(d.get("c")).isEqualTo(bytes("3"));
        assertThat(d.size()).isEqualTo(2);
    }

    // ==========================================================================
    // §C — Resize correctness
    // ==========================================================================

    @Test
    void handlesManyKeysAcrossResizes() {
        Dict dict = new Dict();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            dict.put("key:" + i, bytes("val:" + i));
        }
        assertThat(dict.size()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertThat(dict.get("key:" + i)).isEqualTo(bytes("val:" + i));
        }
    }

    @Test
    void allEntriesAccessibleImmediatelyAfterResize() {
        // Fill to just above the resize threshold, then verify every entry is still reachable.
        // Resize bugs typically manifest as entries mapped to stale bucket indices.
        Dict d = new Dict(4); // initial capacity 4; resize at size > 3
        d.put("alpha",   bytes("1"));
        d.put("beta",    bytes("2"));
        d.put("gamma",   bytes("3"));
        d.put("delta",   bytes("4")); // triggers resize to capacity 8
        d.put("epsilon", bytes("5"));

        assertThat(d.size()).isEqualTo(5);
        assertThat(d.get("alpha")).isEqualTo(bytes("1"));
        assertThat(d.get("beta")).isEqualTo(bytes("2"));
        assertThat(d.get("gamma")).isEqualTo(bytes("3"));
        assertThat(d.get("delta")).isEqualTo(bytes("4"));
        assertThat(d.get("epsilon")).isEqualTo(bytes("5"));
    }

    // ==========================================================================
    // §D — DictEntryPool discipline
    // ==========================================================================

    @Test
    void testPoolReuse() {
        // After a node is released, the next acquire must return the SAME object — not a fresh
        // allocation. If the pool's free-list logic is wrong (e.g. release doesn't push the node
        // or acquire doesn't pop it), this fails with isSameAs.
        DictEntryPool pool = new DictEntryPool();
        byte[] k = bytes("k");
        byte[] v = bytes("v");

        DictEntry first = pool.acquire(42, k, v, null);
        assertThat(first).isNotNull();

        pool.release(first);

        DictEntry second = pool.acquire(42, k, v, null);
        assertThat(second)
                .as("pool must return the previously released node, not a fresh allocation")
                .isSameAs(first);
    }

    @Test
    void testNullOnFreeInvariant() {
        // A released node's reference fields (key, value, next) MUST be nulled before the node
        // enters the free list. A pooled node is not collected by GC; if its fields are non-null,
        // it keeps a strong path to the key and value byte arrays, preventing their collection
        // and inflating old-gen occupancy. See architecture-spec.md §4.
        DictEntryPool pool = new DictEntryPool();
        DictEntry entry = pool.acquire(0, bytes("key"), bytes("value"), null);

        pool.release(entry);

        assertThat(entry.key())
                .as("key must be nulled on release (null-on-free invariant)")
                .isNull();
        assertThat(entry.value())
                .as("value must be nulled on release (null-on-free invariant)")
                .isNull();
        assertThat(entry.next())
                .as("next must be nulled on release — it will be overwritten by the free-list pointer, "
                        + "but the invariant check happens before that")
                .isNull();
    }

    @Test
    void poolDoesNotExceedMaxPooled() {
        // A bounded pool must discard nodes once it reaches capacity rather than growing
        // indefinitely. Insert many entries, remove them all, and verify the pool size
        // stays within its declared bound.
        int maxPooled = 8;
        DictEntryPool pool = new DictEntryPool(maxPooled);
        for (int i = 0; i < 32; i++) {
            pool.release(pool.acquire(i, bytes("k" + i), bytes("v" + i), null));
        }
        assertThat(pool.poolSize())
                .as("pool size must not exceed maxPooled")
                .isLessThanOrEqualTo(maxPooled);
    }

    // --- Helpers ---

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
