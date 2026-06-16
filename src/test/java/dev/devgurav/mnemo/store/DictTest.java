package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;
import org.junit.jupiter.api.Assumptions;
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

    // ==========================================================================
    // §E — Dual-table routing during incremental rehash
    // ==========================================================================

    /*
     * Test methodology
     * ─────────────────
     * All §E tests share a deterministic mid-rehash fixture built by midRehashDict().
     *
     * With Dict(4): LOAD_FACTOR=0.75, threshold = 0.75 × 4 = 3.0 (resize when size > 3).
     * Single-char keys hash to known capacity-4 buckets via index = hashCode & 3:
     *
     *   "a" (97)  → bucket 1     "b" (98) → bucket 2
     *   "c" (99)  → bucket 3     "d" (100) → bucket 0
     *
     * Step-by-step:
     *   put(a,b,c)    → size=3, no rehash (3 is not > 3)
     *   put(d)        → size=4 > 3 → startRehash(): ht[1]=new[8], rehashidx=0
     *   put(e, h=101) → rehashStep(1): migrate ht[0][0]={d} → ht[1][100&7=4]; rehashidx=1
     *                   insert "e" (new key) → ht[1][101&7=5]; size=5
     *
     * Resulting mid-rehash state  (rehashidx=1, still in progress):
     *   ht[0] = [ null, {a}, {b}, {c} ]
     *   ht[1] = [ …, {d}@idx4, {e}@idx5, … ]
     */

    @Test
    void eSetupLandsInMidRehashState() {
        // Guard: all other §E tests depend on this precondition.
        // If this test fails, re-examine midRehashDict() or the key hash constants above.
        Dict dict = midRehashDict();
        assertThat(dict.isRehashing())
                .as("after 5 deterministic inserts into Dict(4), rehash must still be active " +
                    "(only ht[0][0] was drained; buckets 1..3 remain)")
                .isTrue();
        assertThat(dict.table(1))
                .as("ht[1] must be allocated once a rehash is in progress")
                .isNotNull();
        assertThat(dict.rehashIndex())
                .as("exactly one bucket (ht[0][0]) should have been migrated; cursor must be 1")
                .isEqualTo(1);
    }

    @Test
    void migratedKeyIsInHt1AndNotInHt0() {
        // Validates the migration itself: after rehashStep moves ht[0][0]={d} to ht[1],
        // the node must appear in ht[1] and ht[0][0] must be null.
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(keyIsInTable(dict, 0, "d"))
                .as("\"d\" was in ht[0][0]; after migration it must be gone from ht[0]")
                .isFalse();
        assertThat(keyIsInTable(dict, 1, "d"))
                .as("\"d\" must have been re-linked into ht[1][4] by rehashStep")
                .isTrue();
    }

    @Test
    void getFindsKeyMigratedToHt1() {
        // Core dual-probe regression: if getBytes only probes ht[0], a migrated key returns null.
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(dict.get("d"))
                .as("get(\"d\") must probe ht[1] and return the value even though ht[0][0] is null")
                .isEqualTo(bytes("D"));
    }

    @Test
    void getFindsKeysStillInHt0DuringRehash() {
        // Sanity check: ht[0] must still be consulted while rehash is active.
        // "a","b","c" are in ht[0][1..3] — they have NOT been migrated yet (rehashidx=1).
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(dict.get("a")).as("\"a\" is still in ht[0][1]").isEqualTo(bytes("A"));
        assertThat(dict.get("b")).as("\"b\" is still in ht[0][2]").isEqualTo(bytes("B"));
        assertThat(dict.get("c")).as("\"c\" is still in ht[0][3]").isEqualTo(bytes("C"));
    }

    @Test
    void newPutDuringRehashMustRouteToHt1() {
        // New keys inserted while rehashing must go to ht[1], not ht[0].
        // Routing to ht[0] means the entry is LOST the moment ht[1] replaces ht[0] at completion.
        // "e" was inserted as the 5th put (the one that triggered rehashStep); it is our witness.
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(keyIsInTable(dict, 1, "e"))
                .as("\"e\" was inserted during rehash — it must be in ht[1]")
                .isTrue();
        assertThat(keyIsInTable(dict, 0, "e"))
                .as("\"e\" must NOT be in ht[0]: entries added there during rehash are lost on table promotion")
                .isFalse();
    }

    @Test
    void putOverwriteDuringRehashUpdatesHt1InPlace() {
        // putBytes must search BOTH tables for an existing key before creating a new node.
        // Bug scenario: only ht[0] is checked → "d" is already in ht[1], so the duplicate
        // check misses it and a second node is created — size grows illegally.
        Dict dict = midRehashDict();
        assumeRehashing(dict);
        int sizeBeforeOverwrite = dict.size();

        dict.put("d", bytes("D-NEW"));

        assertThat(dict.get("d"))
                .as("overwriting \"d\" (which is in ht[1]) must update the value in-place")
                .isEqualTo(bytes("D-NEW"));
        assertThat(dict.size())
                .as("size must not grow on an overwrite — a size increase means a duplicate node was created in ht[1]")
                .isEqualTo(sizeBeforeOverwrite);
    }

    @Test
    void putOverwriteKeyInHt0DuringRehashUpdatesInPlace() {
        // Mirror of the above for keys that have NOT yet migrated.
        // "a" is still in ht[0][1] (rehashidx=1; bucket 1 not yet drained).
        Dict dict = midRehashDict();
        assumeRehashing(dict);
        int sizeBeforeOverwrite = dict.size();

        dict.put("a", bytes("A-NEW"));

        assertThat(dict.get("a"))
                .as("overwriting \"a\" (which is still in ht[0]) must update the value in-place")
                .isEqualTo(bytes("A-NEW"));
        assertThat(dict.size())
                .as("size must not grow on an overwrite of a key still in ht[0]")
                .isEqualTo(sizeBeforeOverwrite);
    }

    @Test
    void removeDuringRehashFindsKeyInHt1() {
        // removeBytes must probe ht[1] on a miss in ht[0].
        // Bug scenario: only ht[0] is searched → remove("d") returns false even though "d" exists.
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(dict.remove("d"))
                .as("remove must locate \"d\" in ht[1] and return true")
                .isTrue();
        assertThat(dict.get("d"))
                .as("after removal, \"d\" must not be findable in either table")
                .isNull();
        assertThat(dict.size())
                .as("size must decrease by exactly 1 after a successful remove")
                .isEqualTo(4);
    }

    @Test
    void removeDuringRehashFindsKeyInHt0() {
        // Mirror: remove must also still work for keys in ht[0].
        Dict dict = midRehashDict();
        assumeRehashing(dict);

        assertThat(dict.remove("b"))
                .as("remove must find and delete \"b\" which is still in ht[0][2]")
                .isTrue();
        assertThat(dict.get("b")).isNull();
        assertThat(dict.size()).isEqualTo(4);
    }

    @Test
    void sizeRemainsConsistentAcrossRehash() {
        // size() must equal the number of live entries across BOTH tables at all times.
        // A common bug: counting migrations as new entries (size grows) or not tracking them (size shrinks).
        Dict dict = new Dict(4);
        int expected = 0;
        for (String key : new String[]{"a", "b", "c", "d", "e", "f", "g"}) {
            dict.put(key, bytes(key.toUpperCase()));
            expected++;
            assertThat(dict.size())
                    .as("size must be %d after inserting \"%s\"", expected, key)
                    .isEqualTo(expected);
        }
    }

    @Test
    void allKeysRemainReadableThroughFullRehashCycle() {
        // End-to-end: insert enough entries through multiple rehash cycles.
        // Every key inserted before, during, and after each rehash must remain readable.
        // This catches "lost keys on table promotion" — the hardest bug class to spot.
        Dict dict = new Dict(4);
        int n = 80; // enough to force 4→8→16→32→64 and partial 64→128 rehash cycles
        for (int i = 0; i < n; i++) {
            dict.put("key:" + i, bytes("v:" + i));
        }
        for (int i = 0; i < n; i++) {
            assertThat(dict.get("key:" + i))
                    .as("key:%d must survive all rehash cycles and table promotions", i)
                    .isEqualTo(bytes("v:" + i));
        }
    }

    @Test
    void removedKeyDoesNotReappearAfterRehashCompletes() {
        // A subtle migration bug: if a node is re-linked into ht[1] AFTER being deleted,
        // the key reappears as a phantom entry once ht[1] is promoted to ht[0].
        Dict dict = new Dict(4);
        dict.put("a", bytes("A"));
        dict.put("b", bytes("B"));
        dict.put("c", bytes("C"));
        dict.put("d", bytes("D")); // triggers rehash
        dict.remove("d");          // remove "d" which is still in ht[0][0] pre-migration

        // Drive rehash to completion via additional puts
        for (int i = 0; i < 20; i++) dict.put("extra:" + i, bytes("x"));

        assertThat(dict.get("d"))
                .as("\"d\" was removed before migration — it must not reappear after rehash completes")
                .isNull();
        assertThat(dict.containsKey("d")).isFalse();
    }

    // --- §E helpers ---

    /**
     * Builds a deterministic mid-rehash fixture: Dict(4) with 5 entries split across two tables.
     * <pre>
     *   ht[0] = [ null, {a:A}, {b:B}, {c:C} ]   rehashidx=1
     *   ht[1] = [ …, {d:D}@idx4, {e:E}@idx5, … ]
     * </pre>
     */
    private static Dict midRehashDict() {
        Dict dict = new Dict(4);
        dict.put("a", bytes("A")); // bucket 1; size=1
        dict.put("b", bytes("B")); // bucket 2; size=2
        dict.put("c", bytes("C")); // bucket 3; size=3  ← at threshold, no resize yet
        dict.put("d", bytes("D")); // bucket 0; size=4 > 3.0 → startRehash (rehashidx=0, ht[1]=new[8])
        dict.put("e", bytes("E")); // rehashStep → migrate ht[0][0]={d}→ht[1][4]; rehashidx=1
                                   // insert "e" → ht[1][101&7=5]; size=5
        return dict;
    }

    /**
     * Returns {@code true} if the node for {@code key} is the head (or in the chain) at
     * {@code table[bucketIdx]}. Uses hash equality, safe for the single-char test keys (unique hashes).
     */
    private static boolean keyIsInTable(Dict dict, int tableIdx, String key) {
        DictEntry[] table = dict.table(tableIdx);
        if (table == null) return false;
        int hash = key.hashCode();
        int bucketIdx = (hash & 0x7FFF_FFFF) & (table.length - 1);
        for (DictEntry e = table[bucketIdx]; e != null; e = e.next) {
            if (e.hash == hash) return true;
        }
        return false;
    }

    /**
     * Skips the calling test with a clear explanation if the mid-rehash precondition failed.
     * Uses skip (not fail) so that a broken precondition shows as "aborted" rather than "failed",
     * making it easy to distinguish from a real implementation bug.
     */
    private static void assumeRehashing(Dict dict) {
        Assumptions.assumeTrue(
            dict.isRehashing(),
            "SKIP: mid-rehash precondition failed — all entries may have hashed to one bucket, " +
            "completing rehash in a single step. Check eSetupLandsInMidRehashState() first."
        );
    }

    // --- Helpers ---

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
