package dev.devgurav.mnemo.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Probabilistic skip list — the range-query spine of {@link ZSet}.
 *
 * <p>Nodes are ordered by {@code score} ascending. Ties in score are broken by unsigned
 * lexicographic order on {@code member} bytes, mirroring Redis's {@code ZADD} semantics.
 *
 * <h2>Span accounting</h2>
 * <p>Every forward pointer carries a {@code span}: the number of level-0 edges between this
 * node and its successor at that level. Formally, {@code node.span[i] = position(node.forward[i])
 * - position(node)}, where the header is at position 0 and the first real node is at position 1.
 * This means:
 * <ul>
 *   <li>At level 0, every span is exactly 1 (adjacent nodes).</li>
 *   <li>At higher levels, spans grow as nodes are skipped.</li>
 *   <li>Summing spans along the search path to a target gives its exact rank in O(log n).</li>
 * </ul>
 *
 * <h2>Insert span update</h2>
 * <p>When a new node {@code x} is inserted at position {@code rank[0]+1}:
 * <pre>
 *   x.span[i]         = update[i].span[i] - (rank[0] - rank[i])
 *   update[i].span[i] = (rank[0] - rank[i]) + 1
 *   // For levels >= x's level: update[i].span[i]++   (one extra node in the span)
 * </pre>
 *
 * <h2>Delete span update</h2>
 * <p>When target {@code t} at level {@code L} is removed:
 * <pre>
 *   for i in 0..L-1: update[i].span[i] += t.span[i] - 1; update[i].forward[i] = t.forward[i]
 *   for i in L..this.level-1: update[i].span[i]--
 * </pre>
 */
public final class SkipList {

    static final int    MAX_LEVEL = 32;
    static final double P         = 0.25; // geometric level-promotion probability (Redis default)

    // -------------------------------------------------------------------------
    // Node
    // -------------------------------------------------------------------------

    /**
     * A single node in the skip list.
     *
     * <p>{@code forward[i]} is the next node reachable at level {@code i}.
     * {@code span[i]} is the level-0 distance to {@code forward[i]}; when {@code forward[i]}
     * is {@code null} the value is a bookkeeping accumulator used by the insert algorithm and
     * is never consulted in traversal.
     */
    static final class SkipListNode {
        final double score;
        final byte[] member;

        final SkipListNode[] forward;
        final int[]          span;

        SkipListNode(double score, byte[] member, int level) {
            this.score   = score;
            this.member  = member;
            this.forward = new SkipListNode[level];
            this.span    = new int[level];
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final SkipListNode header;
    private int level; // highest level currently in use (1-based)
    private int size;

    public SkipList() {
        this.header = new SkipListNode(Double.NEGATIVE_INFINITY, new byte[0], MAX_LEVEL);
        this.level  = 1;
        this.size   = 0;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Insert a {@code (score, member)} pair into the sorted set.
     *
     * <p>The caller is responsible for removing any prior entry for the same member before
     * calling this method (see {@link ZSet#add}). Inserting a duplicate {@code (score, member)}
     * will create two separate nodes, which corrupts rank calculations.
     */
    public void insert(double score, byte[] member) {
        SkipListNode[] update = new SkipListNode[MAX_LEVEL];
        int[]          rank   = new int[MAX_LEVEL];

        // Top-down search: find the predecessor at each level and accumulate span distances.
        SkipListNode current = header;
        for (int i = this.level - 1; i >= 0; i--) {
            // Carry the rank accumulated from the level above; avoids re-traversal.
            rank[i] = (i == this.level - 1) ? 0 : rank[i + 1];
            while (current.forward[i] != null && lessThan(current.forward[i], score, member)) {
                rank[i] += current.span[i];
                current  = current.forward[i];
            }
            update[i] = current;
        }
        // rank[0] = position of update[0]; new node goes at rank[0] + 1.

        int newLevel = randomLevel();
        if (newLevel > this.level) {
            // Initialise new levels: header is update, rank=0, span=size (accumulated counter).
            for (int i = this.level; i < newLevel; i++) {
                rank[i]         = 0;
                update[i]       = header;
                header.span[i]  = size; // will be overwritten by the formula below
            }
            this.level = newLevel;
        }

        SkipListNode x = new SkipListNode(score, member, newLevel);

        // Splice x in at every level it occupies and recalculate spans.
        for (int i = 0; i < newLevel; i++) {
            x.forward[i]       = update[i].forward[i];
            update[i].forward[i] = x;

            // update[i] now points to x; x points to what update[i] used to point to.
            x.span[i]           = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i]   = (rank[0] - rank[i]) + 1;
        }

        // Levels above x's height still span across x: increment their counters.
        for (int i = newLevel; i < this.level; i++) {
            update[i].span[i]++;
        }

        size++;
    }

    /**
     * Remove the node identified by {@code (score, member)}.
     *
     * @return {@code true} if the node was found and removed
     */
    public boolean delete(double score, byte[] member) {
        SkipListNode[] update = new SkipListNode[MAX_LEVEL];

        SkipListNode current = header;
        for (int i = this.level - 1; i >= 0; i--) {
            while (current.forward[i] != null && lessThan(current.forward[i], score, member)) {
                current = current.forward[i];
            }
            update[i] = current;
        }

        SkipListNode target = update[0].forward[0];
        if (target == null || target.score != score || !Arrays.equals(target.member, member)) {
            return false; // node not in the list
        }

        for (int i = 0; i < this.level; i++) {
            if (update[i].forward[i] == target) {
                // This level contains target: merge target's span into predecessor, then splice out.
                update[i].span[i]   += target.span[i] - 1;
                update[i].forward[i] = target.forward[i];
            } else {
                // This level spans across target's position without containing it: one fewer node.
                update[i].span[i]--;
            }
        }

        // Prune levels that became empty after the removal.
        while (this.level > 1 && header.forward[this.level - 1] == null) {
            this.level--;
        }

        size--;
        return true;
    }

    /**
     * Return the 1-based rank of the node with the given {@code (score, member)} pair.
     * Rank 1 is the element with the lowest score (ties broken by member bytes ascending).
     *
     * @return 1-based rank, or {@code -1} if the element is not present
     */
    public int getRank(double score, byte[] member) {
        int rank         = 0;
        SkipListNode cur = header;

        for (int i = this.level - 1; i >= 0; i--) {
            // Advance while the successor is BEFORE OR AT (score, member).
            while (cur.forward[i] != null && lessOrEqual(cur.forward[i], score, member)) {
                rank += cur.span[i];
                cur   = cur.forward[i];
            }
        }

        // cur is now the target node if it exists.
        if (cur != header && cur.score == score && Arrays.equals(cur.member, member)) {
            return rank;
        }
        return -1;
    }

    /**
     * Return the node at the given 1-based rank, or {@code null} if out of bounds.
     * Rank 1 is the element with the lowest score.
     */
    public SkipListNode getByRank(int rank) {
        if (rank < 1 || rank > size) return null;

        int traversed    = 0;
        SkipListNode cur = header;

        for (int i = this.level - 1; i >= 0; i--) {
            while (cur.forward[i] != null && traversed + cur.span[i] <= rank) {
                traversed += cur.span[i];
                cur        = cur.forward[i];
            }
        }

        // For a valid rank, traversed always equals rank after the level-0 pass.
        return (traversed == rank) ? cur : null;
    }

    /**
     * Return nodes at ranks {@code [startRank, endRank]} (1-based, inclusive) in score order.
     * Returns an empty list if the range is invalid.
     */
    public List<SkipListNode> range(int startRank, int endRank) {
        List<SkipListNode> result = new ArrayList<>();
        if (startRank < 1 || startRank > size || startRank > endRank) return result;
        endRank = Math.min(endRank, size);

        SkipListNode node = getByRank(startRank);
        for (int r = startRank; node != null && r <= endRank; r++) {
            result.add(node);
            node = node.forward[0]; // level-0 chain enumerates all nodes in order
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Ordering predicates
    // -------------------------------------------------------------------------

    /** True if {@code node} is strictly before {@code (score, member)} in the sorted order. */
    private static boolean lessThan(SkipListNode node, double score, byte[] member) {
        if (node.score != score) return node.score < score;
        return cmpBytes(node.member, member) < 0;
    }

    /** True if {@code node} is before or at {@code (score, member)} in the sorted order. */
    private static boolean lessOrEqual(SkipListNode node, double score, byte[] member) {
        if (node.score != score) return node.score < score;
        return cmpBytes(node.member, member) <= 0;
    }

    /** Unsigned lexicographic comparison of two byte arrays. */
    static int cmpBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < P) lvl++;
        return lvl;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int size()  { return size; }
    public int level() { return level; }
}
