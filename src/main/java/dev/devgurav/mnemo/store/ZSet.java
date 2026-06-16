package dev.devgurav.mnemo.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sorted Set — the data structure behind Redis's {@code ZADD} / {@code ZRANK} / {@code ZRANGE}.
 *
 * <p>Backed by two complementary structures that share the same logical dataset:
 * <ul>
 *   <li>{@link Dict} — maps member → score bytes; O(1) score lookup and membership test.</li>
 *   <li>{@link SkipList} — maintains {@code (score, member)} pairs in ascending order;
 *       O(log n) rank and range queries via span accumulation.</li>
 * </ul>
 *
 * <p>Every write mutates both structures atomically (within a single thread — no concurrency
 * guarantee). Reads are routed to whichever structure answers the query most efficiently.
 *
 * <h2>Score encoding</h2>
 * <p>Scores are stored as 8 big-endian bytes ({@link #doubleToBytes}) inside the Dict so that
 * the Dict's byte[] value type is reused without an extra object wrapper. The encoding round-trips
 * through {@link Double#doubleToRawLongBits} so that negative zero and NaN payloads are preserved.
 *
 * <h2>Rank semantics</h2>
 * <p>Ranks are 1-based: the member with the lowest score has rank 1. Ties in score are broken
 * by ascending unsigned lexicographic order on the member's UTF-8 bytes.
 */
public final class ZSet {

    private final Dict     scoreIndex; // member → 8-byte big-endian score
    private final SkipList rankIndex;  // (score, member) → rank-ordered nodes

    public ZSet() {
        this.scoreIndex = new Dict();
        this.rankIndex  = new SkipList();
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Add {@code member} with the given {@code score}, or update its score if it already exists.
     *
     * <p>When the score changes, the old skip-list entry is removed and a new one is inserted at
     * the correct position. The Dict entry is updated in-place (no pool churn for the Dict layer).
     *
     * @return {@code true} if a new member was added; {@code false} if an existing one was updated
     */
    public boolean add(String member, double score) {
        byte[] memberBytes    = memberBytes(member);
        byte[] existingScoreBytes = scoreIndex.get(member);

        if (existingScoreBytes != null) {
            double oldScore = bytesToDouble(existingScoreBytes);
            if (oldScore == score) return false; // idempotent: nothing to do
            rankIndex.delete(oldScore, memberBytes);
        }

        scoreIndex.put(member, doubleToBytes(score));
        rankIndex.insert(score, memberBytes);
        return existingScoreBytes == null; // true = new member, false = score updated
    }

    /**
     * Remove {@code member} from both the Dict and the SkipList.
     *
     * @return {@code true} if the member existed and was removed
     */
    public boolean remove(String member) {
        byte[] scoreBytes = scoreIndex.get(member);
        if (scoreBytes == null) return false;

        scoreIndex.remove(member);
        rankIndex.delete(bytesToDouble(scoreBytes), memberBytes(member));
        return true;
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Return the score for {@code member}, or {@link Double#NaN} if not present.
     * O(1) via the Dict index.
     */
    public double score(String member) {
        byte[] scoreBytes = scoreIndex.get(member);
        return (scoreBytes == null) ? Double.NaN : bytesToDouble(scoreBytes);
    }

    /**
     * Return the 1-based rank of {@code member} (rank 1 = lowest score), or {@code -1} if absent.
     * O(log n) via span accumulation in the SkipList.
     */
    public int rank(String member) {
        byte[] scoreBytes = scoreIndex.get(member);
        if (scoreBytes == null) return -1;
        return rankIndex.getRank(bytesToDouble(scoreBytes), memberBytes(member));
    }

    /**
     * Return member names at ranks {@code [startRank, endRank]} (1-based, inclusive), in ascending
     * score order. Equivalent to Redis's {@code ZRANGE key startRank endRank BYSCORE} using rank
     * indices. Returns an empty list if the range is invalid or empty.
     */
    public List<String> rangeByRank(int startRank, int endRank) {
        List<SkipList.SkipListNode> nodes = rankIndex.range(startRank, endRank);
        List<String> result = new ArrayList<>(nodes.size());
        for (SkipList.SkipListNode node : nodes) {
            result.add(new String(node.member, StandardCharsets.UTF_8));
        }
        return result;
    }

    /**
     * Return (member, score) pairs at ranks {@code [startRank, endRank]} (1-based, inclusive).
     * Each element of the returned list is a two-element array: {@code [memberString, scoreString]}.
     */
    public List<String[]> rangeByRankWithScores(int startRank, int endRank) {
        List<SkipList.SkipListNode> nodes = rankIndex.range(startRank, endRank);
        List<String[]> result = new ArrayList<>(nodes.size());
        for (SkipList.SkipListNode node : nodes) {
            result.add(new String[]{
                new String(node.member, StandardCharsets.UTF_8),
                Double.toString(node.score)
            });
        }
        return result;
    }

    /**
     * Return the number of members in the sorted set.
     */
    public int size() {
        return rankIndex.size();
    }

    // -------------------------------------------------------------------------
    // Score encoding helpers
    // -------------------------------------------------------------------------

    static byte[] doubleToBytes(double v) {
        long bits = Double.doubleToRawLongBits(v);
        return new byte[]{
            (byte)(bits >>> 56), (byte)(bits >>> 48),
            (byte)(bits >>> 40), (byte)(bits >>> 32),
            (byte)(bits >>> 24), (byte)(bits >>> 16),
            (byte)(bits >>>  8), (byte) bits
        };
    }

    static double bytesToDouble(byte[] b) {
        long bits =
            ((long)(b[0] & 0xFF) << 56) | ((long)(b[1] & 0xFF) << 48) |
            ((long)(b[2] & 0xFF) << 40) | ((long)(b[3] & 0xFF) << 32) |
            ((long)(b[4] & 0xFF) << 24) | ((long)(b[5] & 0xFF) << 16) |
            ((long)(b[6] & 0xFF) <<  8) |  (long)(b[7] & 0xFF);
        return Double.longBitsToDouble(bits);
    }

    static byte[] memberBytes(String member) {
        return member.getBytes(StandardCharsets.UTF_8);
    }
}
