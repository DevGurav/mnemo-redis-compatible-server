package dev.devgurav.mnemo.store.list;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-built doubly-linked list backing the Redis list type — the fourth keyspace value type
 * after strings, sorted sets, and hashes.
 *
 * <p>Push and pop are O(1) at both ends; the head/tail pointers make {@code LPUSH}/{@code RPUSH}/
 * {@code LPOP}/{@code RPOP} pointer swaps with no scan. {@link #lrange} walks from the nearer end.
 *
 * <p><b>Zero-allocation hot path.</b> Nodes are never {@code new}-ed on a push and never dropped to
 * GC on a pop: every node comes from and returns to a per-list {@link ListNodePool}, exactly as
 * {@link dev.devgurav.mnemo.store.Dict} recycles {@link dev.devgurav.mnemo.store.entry.DictEntry}
 * through its {@link dev.devgurav.mnemo.store.entry.DictEntryPool}. A queue pushed and popped at a
 * steady depth therefore allocates nothing in steady state.
 *
 * <p>Owned by the single shard thread (§0.3); no synchronization.
 */
public final class IntrusiveList {

    private final ListNodePool pool;

    private ListNode head;
    private ListNode tail;
    private int size;

    public IntrusiveList() {
        this(new ListNodePool());
    }

    /** Test/benchmark seam: inject a pool to observe recycling. */
    public IntrusiveList(ListNodePool pool) {
        this.pool = pool;
    }

    /** Prepends {@code value} to the head. O(1). */
    public void lpush(byte[] value) {
        ListNode node = pool.acquire(value, null, head);
        if (head != null) {
            head.prev = node;
        } else {
            tail = node; // first element: head and tail are the same node
        }
        head = node;
        size++;
    }

    /** Appends {@code value} to the tail. O(1). */
    public void rpush(byte[] value) {
        ListNode node = pool.acquire(value, tail, null);
        if (tail != null) {
            tail.next = node;
        } else {
            head = node; // first element: head and tail are the same node
        }
        tail = node;
        size++;
    }

    /**
     * Removes and returns the head element, recycling its node to the pool.
     *
     * @return the popped bytes, or {@code null} if the list is empty
     */
    public byte[] lpop() {
        if (head == null) return null;
        ListNode node = head;
        byte[] value = node.value;        // capture before release nulls the field
        head = node.next;
        if (head != null) {
            head.prev = null;
        } else {
            tail = null;                  // popped the last element
        }
        pool.release(node);
        size--;
        return value;
    }

    /**
     * Removes and returns the tail element, recycling its node to the pool.
     *
     * @return the popped bytes, or {@code null} if the list is empty
     */
    public byte[] rpop() {
        if (tail == null) return null;
        ListNode node = tail;
        byte[] value = node.value;        // capture before release nulls the field
        tail = node.prev;
        if (tail != null) {
            tail.next = null;
        } else {
            head = null;                  // popped the last element
        }
        pool.release(node);
        size--;
        return value;
    }

    /** The number of elements in the list. */
    public int llen() {
        return size;
    }

    /**
     * Returns the elements between {@code start} and {@code stop}, both inclusive, using Redis
     * index semantics: negative indices count back from the end ({@code -1} is the last element),
     * out-of-range bounds are clamped, and an empty range yields an empty list.
     */
    public List<byte[]> lrange(int start, int stop) {
        if (size == 0) return List.of();

        // Normalise negatives to absolute indices, then clamp to [0, size-1].
        int from = start < 0 ? start + size : start;
        int to   = stop  < 0 ? stop  + size : stop;
        if (from < 0) from = 0;
        if (to >= size) to = size - 1;
        if (from > to) return List.of();

        List<byte[]> out = new ArrayList<>(to - from + 1);
        ListNode node = nodeAt(from);
        for (int i = from; i <= to; i++) {
            out.add(node.value);
            node = node.next;
        }
        return out;
    }

    /** Walks to the node at absolute {@code index}, starting from whichever end is closer. */
    private ListNode nodeAt(int index) {
        ListNode node;
        if (index <= size / 2) {
            node = head;
            for (int i = 0; i < index; i++) node = node.next;
        } else {
            node = tail;
            for (int i = size - 1; i > index; i--) node = node.prev;
        }
        return node;
    }

    /** Free-list depth of the backing pool; for tests and {@code INFO}. */
    public int poolSize() {
        return pool.poolSize();
    }
}
