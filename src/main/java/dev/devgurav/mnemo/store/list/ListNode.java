package dev.devgurav.mnemo.store.list;

/**
 * A single node in an {@link IntrusiveList}'s doubly-linked chain.
 *
 * <p>Fields are intentionally public — {@code IntrusiveList} and {@link ListNodePool} touch them
 * directly so the list hot path carries no accessor dispatch, mirroring
 * {@link dev.devgurav.mnemo.store.entry.DictEntry}.
 *
 * <p>Pooling invariant: when a node is returned to {@link ListNodePool}, the pool MUST null
 * {@code value}, {@code prev}, and {@code next} before the node enters the free list. A pooled node
 * may survive into old-gen; leaving its reference fields set would form a GC root that retains the
 * value bytes and the neighbouring nodes indefinitely. See architecture-spec.md §4.
 */
public final class ListNode {

    /** The element's raw bytes. Null when this node is sitting in the pool free list. */
    public byte[] value;

    /** Previous node in the list chain, or null at the head. Always null while pooled. */
    public ListNode prev;

    /**
     * Next node in the list chain, or the next free node in the pool's intrusive free list when
     * this node is not in active use. Null at the tail of either list.
     */
    public ListNode next;

    /** Nodes are constructed once by {@link ListNodePool}; callers go through {@code acquire}. */
    public ListNode() {}

    // --- Getters for cross-package access (tests, metrics) ---

    public byte[] value()   { return value; }
    public ListNode prev()  { return prev; }
    public ListNode next()  { return next; }
}
