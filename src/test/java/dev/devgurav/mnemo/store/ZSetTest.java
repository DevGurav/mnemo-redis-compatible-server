package dev.devgurav.mnemo.store;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC (red-turned-green) for {@link ZSet} and the underlying {@link SkipList}.
 *
 * <p>Tagged {@code "spec"}; run via {@code ./gradlew specTest}.
 *
 * <p>Test groups:
 * <ul>
 *   <li>§A — ZSet semantics: add, score, rank, remove, size.</li>
 *   <li>§B — Ordering: score-ascending, tie-breaking by member bytes.</li>
 *   <li>§C — Span correctness: rank and getByRank are O(log n) via span accumulation.</li>
 *   <li>§D — SkipList internals: insert/delete span invariants, level pruning.</li>
 *   <li>§E — Edge cases: empty set, single element, score update, idempotent add.</li>
 * </ul>
 */
@Tag("spec")
class ZSetTest {

    // ==========================================================================
    // §A — ZSet semantics
    // ==========================================================================

    @Test
    void addReturnsTrue_firstInsert() {
        ZSet z = new ZSet();
        assertThat(z.add("alice", 1.0)).isTrue();
        assertThat(z.size()).isEqualTo(1);
    }

    @Test
    void addReturnsFalse_scoreUpdate() {
        ZSet z = new ZSet();
        z.add("alice", 1.0);
        assertThat(z.add("alice", 2.0)).isFalse(); // update, not new insert
        assertThat(z.size()).isEqualTo(1);          // size unchanged
    }

    @Test
    void addReturnsFalse_idempotentSameScore() {
        ZSet z = new ZSet();
        z.add("alice", 3.0);
        assertThat(z.add("alice", 3.0)).isFalse(); // exact same score: no-op
        assertThat(z.size()).isEqualTo(1);
    }

    @Test
    void scoreReturnsNaN_missingMember() {
        ZSet z = new ZSet();
        assertThat(z.score("ghost")).isNaN();
    }

    @Test
    void scoreReturnsCorrectValue_afterAdd() {
        ZSet z = new ZSet();
        z.add("alice", 42.5);
        assertThat(z.score("alice")).isEqualTo(42.5);
    }

    @Test
    void scoreReflectsUpdate() {
        ZSet z = new ZSet();
        z.add("alice", 1.0);
        z.add("alice", 99.0);
        assertThat(z.score("alice")).isEqualTo(99.0);
    }

    @Test
    void rankReturnsNegOne_missingMember() {
        ZSet z = new ZSet();
        assertThat(z.rank("ghost")).isEqualTo(-1);
    }

    @Test
    void rankReturnsOne_singleElement() {
        ZSet z = new ZSet();
        z.add("only", 5.0);
        assertThat(z.rank("only")).isEqualTo(1);
    }

    @Test
    void removeReturnsFalse_missingMember() {
        ZSet z = new ZSet();
        assertThat(z.remove("ghost")).isFalse();
    }

    @Test
    void removeReturnsTrueAndDeletesMember() {
        ZSet z = new ZSet();
        z.add("alice", 1.0);
        assertThat(z.remove("alice")).isTrue();
        assertThat(z.size()).isZero();
        assertThat(z.score("alice")).isNaN();
        assertThat(z.rank("alice")).isEqualTo(-1);
    }

    @Test
    void sizeTracksAddAndRemove() {
        ZSet z = new ZSet();
        z.add("a", 1.0);
        z.add("b", 2.0);
        z.add("c", 3.0);
        assertThat(z.size()).isEqualTo(3);
        z.remove("b");
        assertThat(z.size()).isEqualTo(2);
    }

    // ==========================================================================
    // §B — Ordering: ascending score, tie-breaking
    // ==========================================================================

    @Test
    void rankReflectsScoreOrder_threeElements() {
        ZSet z = new ZSet();
        z.add("charlie", 3.0);
        z.add("alice",   1.0);
        z.add("bob",     2.0);

        assertThat(z.rank("alice")).isEqualTo(1);   // lowest score
        assertThat(z.rank("bob")).isEqualTo(2);
        assertThat(z.rank("charlie")).isEqualTo(3); // highest score
    }

    @Test
    void rankUpdatesCorrectly_afterScoreChange() {
        ZSet z = new ZSet();
        z.add("alice", 1.0);
        z.add("bob",   2.0);
        z.add("carol", 3.0);

        // Move alice to the back
        z.add("alice", 99.0);

        assertThat(z.rank("bob")).isEqualTo(1);
        assertThat(z.rank("carol")).isEqualTo(2);
        assertThat(z.rank("alice")).isEqualTo(3);
    }

    @Test
    void scoresTieBreakByMemberBytesAscending() {
        // Two members with identical scores: ordering must be lexicographic by member bytes.
        ZSet z = new ZSet();
        z.add("bravo", 1.0);
        z.add("alpha", 1.0); // same score, but "alpha" < "bravo" lexicographically

        assertThat(z.rank("alpha")).isEqualTo(1);
        assertThat(z.rank("bravo")).isEqualTo(2);
    }

    @Test
    void scoresTieBreakByMemberBytes_multipleMembers() {
        ZSet z = new ZSet();
        z.add("c", 5.0);
        z.add("a", 5.0);
        z.add("b", 5.0);

        assertThat(z.rank("a")).isEqualTo(1);
        assertThat(z.rank("b")).isEqualTo(2);
        assertThat(z.rank("c")).isEqualTo(3);
    }

    // ==========================================================================
    // §C — Span correctness: ZRANGE and ZRANK on larger sets
    // ==========================================================================

    @Test
    void rangeByRank_allElements_inOrder() {
        ZSet z = new ZSet();
        z.add("c", 3.0);
        z.add("a", 1.0);
        z.add("b", 2.0);

        List<String> range = z.rangeByRank(1, 3);
        assertThat(range).containsExactly("a", "b", "c");
    }

    @Test
    void rangeByRank_subRange() {
        ZSet z = new ZSet();
        for (int i = 1; i <= 10; i++) z.add("m" + i, i);

        // Ranks 3..7 → members m3..m7
        List<String> range = z.rangeByRank(3, 7);
        assertThat(range).containsExactly("m3", "m4", "m5", "m6", "m7");
    }

    @Test
    void rangeByRank_singleElement() {
        ZSet z = new ZSet();
        z.add("only", 42.0);
        assertThat(z.rangeByRank(1, 1)).containsExactly("only");
    }

    @Test
    void rangeByRank_invalidRange_returnsEmpty() {
        ZSet z = new ZSet();
        z.add("a", 1.0);
        assertThat(z.rangeByRank(2, 5)).isEmpty();  // startRank > size
        assertThat(z.rangeByRank(0, 1)).isEmpty();  // startRank < 1
        assertThat(z.rangeByRank(3, 2)).isEmpty();  // inverted range
    }

    @Test
    void rankAndRangeConsistent_largeSet() {
        // Insert 100 members with known scores; verify every rank matches position in rangeByRank.
        ZSet z = new ZSet();
        int n = 100;
        for (int i = n; i >= 1; i--) z.add("m" + i, i); // insert in reverse score order

        List<String> all = z.rangeByRank(1, n);
        assertThat(all).hasSize(n);
        for (int i = 1; i <= n; i++) {
            String member = "m" + i;
            assertThat(z.rank(member))
                    .as("rank of %s must equal its 1-based position in score order", member)
                    .isEqualTo(i);
            assertThat(all.get(i - 1))
                    .as("rangeByRank position %d must be %s", i, member)
                    .isEqualTo(member);
        }
    }

    @Test
    void rankWithScores_verifyScoreInRange() {
        ZSet z = new ZSet();
        z.add("low",  1.5);
        z.add("mid",  5.0);
        z.add("high", 9.9);

        List<String[]> pairs = z.rangeByRankWithScores(1, 3);
        assertThat(pairs).hasSize(3);
        assertThat(pairs.get(0)[0]).isEqualTo("low");
        assertThat(Double.parseDouble(pairs.get(0)[1])).isEqualTo(1.5);
        assertThat(pairs.get(2)[0]).isEqualTo("high");
        assertThat(Double.parseDouble(pairs.get(2)[1])).isEqualTo(9.9);
    }

    // ==========================================================================
    // §D — SkipList internals: span invariants and level management
    // ==========================================================================

    @Test
    void skipListGetByRank_matchesInsertionOrder() {
        SkipList sl = new SkipList();
        sl.insert(1.0, bytes("a"));
        sl.insert(2.0, bytes("b"));
        sl.insert(3.0, bytes("c"));

        assertThat(sl.getByRank(1).score).isEqualTo(1.0);
        assertThat(sl.getByRank(2).score).isEqualTo(2.0);
        assertThat(sl.getByRank(3).score).isEqualTo(3.0);
        assertThat(sl.getByRank(4)).isNull();
        assertThat(sl.getByRank(0)).isNull();
    }

    @Test
    void skipListGetRank_returnsCorrect1BasedRank() {
        SkipList sl = new SkipList();
        sl.insert(10.0, bytes("x"));
        sl.insert(20.0, bytes("y"));
        sl.insert(30.0, bytes("z"));

        assertThat(sl.getRank(10.0, bytes("x"))).isEqualTo(1);
        assertThat(sl.getRank(20.0, bytes("y"))).isEqualTo(2);
        assertThat(sl.getRank(30.0, bytes("z"))).isEqualTo(3);
        assertThat(sl.getRank(99.0, bytes("w"))).isEqualTo(-1);
    }

    @Test
    void skipListDelete_removesCorrectNode() {
        SkipList sl = new SkipList();
        sl.insert(1.0, bytes("a"));
        sl.insert(2.0, bytes("b"));
        sl.insert(3.0, bytes("c"));

        assertThat(sl.delete(2.0, bytes("b"))).isTrue();
        assertThat(sl.size()).isEqualTo(2);
        assertThat(sl.getRank(2.0, bytes("b"))).isEqualTo(-1);
        // Rank of remaining elements must be re-indexed
        assertThat(sl.getRank(1.0, bytes("a"))).isEqualTo(1);
        assertThat(sl.getRank(3.0, bytes("c"))).isEqualTo(2);
    }

    @Test
    void skipListDelete_returnsFalse_notFound() {
        SkipList sl = new SkipList();
        sl.insert(1.0, bytes("a"));
        assertThat(sl.delete(1.0, bytes("b"))).isFalse(); // member mismatch
        assertThat(sl.delete(2.0, bytes("a"))).isFalse(); // score mismatch
        assertThat(sl.size()).isEqualTo(1);
    }

    @Test
    void skipListSpanInvariant_afterInserts() {
        // Build a 5-element list and verify that getByRank is consistent with getRank for every element.
        SkipList sl = new SkipList();
        String[] members = {"e", "c", "a", "d", "b"};
        double[] scores  = {5.0, 3.0, 1.0, 4.0, 2.0};
        for (int i = 0; i < members.length; i++) sl.insert(scores[i], bytes(members[i]));

        for (int rank = 1; rank <= 5; rank++) {
            SkipList.SkipListNode node = sl.getByRank(rank);
            assertThat(node).as("getByRank(%d) must not be null", rank).isNotNull();
            int actual = sl.getRank(node.score, node.member);
            assertThat(actual)
                    .as("getRank of node returned by getByRank(%d) must be %d (span round-trip)", rank, rank)
                    .isEqualTo(rank);
        }
    }

    @Test
    void skipListSpanInvariant_afterDeletes() {
        // Insert 6, delete the middle two, verify span-based ranks are consistent.
        SkipList sl = new SkipList();
        for (int i = 1; i <= 6; i++) sl.insert(i, bytes("m" + i));

        sl.delete(3.0, bytes("m3"));
        sl.delete(4.0, bytes("m4"));

        // Remaining: m1(rank1), m2(rank2), m5(rank3), m6(rank4)
        assertThat(sl.size()).isEqualTo(4);
        assertThat(sl.getRank(1.0, bytes("m1"))).isEqualTo(1);
        assertThat(sl.getRank(2.0, bytes("m2"))).isEqualTo(2);
        assertThat(sl.getRank(5.0, bytes("m5"))).isEqualTo(3);
        assertThat(sl.getRank(6.0, bytes("m6"))).isEqualTo(4);

        // getByRank must also agree
        assertThat(sl.getByRank(3).score).isEqualTo(5.0);
        assertThat(sl.getByRank(4).score).isEqualTo(6.0);
    }

    @Test
    void skipListLevelPruned_afterDeletingHighNode() {
        // If only the header existed at the max level, deleting all high-level nodes
        // must trim this.level back down.
        SkipList sl = new SkipList();
        for (int i = 1; i <= 50; i++) sl.insert(i, bytes("m" + i));
        int levelBefore = sl.level();

        for (int i = 50; i >= 1; i--) sl.delete(i, bytes("m" + i));

        assertThat(sl.size()).isZero();
        assertThat(sl.level())
                .as("level must shrink back toward 1 once all nodes are removed")
                .isLessThan(levelBefore);
    }

    @Test
    void skipListRange_fullList() {
        SkipList sl = new SkipList();
        for (int i = 1; i <= 5; i++) sl.insert(i, bytes("m" + i));

        List<SkipList.SkipListNode> nodes = sl.range(1, 5);
        assertThat(nodes).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(nodes.get(i).score).isEqualTo(i + 1.0);
        }
    }

    // ==========================================================================
    // §E — Edge cases
    // ==========================================================================

    @Test
    void emptySet_rankAndScoreReturnSentinels() {
        ZSet z = new ZSet();
        assertThat(z.rank("x")).isEqualTo(-1);
        assertThat(z.score("x")).isNaN();
        assertThat(z.size()).isZero();
        assertThat(z.rangeByRank(1, 10)).isEmpty();
    }

    @Test
    void scoreEncoding_roundTrip() {
        // Verify that doubleToBytes / bytesToDouble are lossless for various double values.
        double[] values = {0.0, -0.0, 1.0, -1.0, Double.MAX_VALUE, Double.MIN_VALUE,
                           Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.PI};
        for (double v : values) {
            assertThat(ZSet.bytesToDouble(ZSet.doubleToBytes(v)))
                    .as("double %s must round-trip through byte encoding", v)
                    .isEqualTo(v);
        }
    }

    @Test
    void removeAndReAdd_rankCorrect() {
        ZSet z = new ZSet();
        z.add("alice", 1.0);
        z.add("bob",   2.0);
        z.remove("alice");
        z.add("alice", 99.0); // re-add at a very high score

        assertThat(z.rank("bob")).isEqualTo(1);
        assertThat(z.rank("alice")).isEqualTo(2);
        assertThat(z.size()).isEqualTo(2);
    }

    @Test
    void largeSet_rankConsistentWithScoreOrder() {
        // 500 members inserted in randomised order; every rank must match score ordering.
        ZSet z = new ZSet();
        int n = 500;
        // Insert in a shuffled order using a simple permutation (i*7 mod n gives pseudo-random order)
        for (int i = 0; i < n; i++) {
            int score = (i * 7) % n;
            z.add("m" + score, score);
        }
        assertThat(z.size()).isEqualTo(n);

        for (int i = 0; i < n; i++) {
            assertThat(z.rank("m" + i))
                    .as("rank of m%d (score=%d) must be %d (1-based)", i, i, i + 1)
                    .isEqualTo(i + 1);
        }
    }

    @Test
    void negativeScores_orderedCorrectly() {
        ZSet z = new ZSet();
        z.add("neg",  -10.0);
        z.add("zero",   0.0);
        z.add("pos",   10.0);

        assertThat(z.rank("neg")).isEqualTo(1);
        assertThat(z.rank("zero")).isEqualTo(2);
        assertThat(z.rank("pos")).isEqualTo(3);
        assertThat(z.rangeByRank(1, 3)).containsExactly("neg", "zero", "pos");
    }

    @Test
    void fractionalScores_orderedCorrectly() {
        ZSet z = new ZSet();
        z.add("pi",    Math.PI);
        z.add("e",     Math.E);
        z.add("half",  0.5);

        // 0.5 < e (≈2.718) < π (≈3.14159)
        assertThat(z.rangeByRank(1, 3)).containsExactly("half", "e", "pi");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
