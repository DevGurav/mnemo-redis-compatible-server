package dev.devgurav.mnemo.store.list;

/**
 * A list-local, intrusive free-list pool of {@link ListNode}s — the list-type analogue of
 * {@link dev.devgurav.mnemo.store.entry.DictEntryPool}.
 *
 * <p>Goal: keep the {@code LPUSH}/{@code RPUSH} → {@code LPOP}/{@code RPOP} churn off the allocator.
 * A queue that is pushed and popped at a steady depth would otherwise allocate one {@code ListNode}
 * per push and hand it straight to GC on the matching pop. Recycling popped shells holds the data
 * plane to near-zero allocation pressure (the zero-allocation hot-path thesis; verify with
 * async-profiler alloc flame graphs — benchmarking-methodology.md §2).
 *
 * <p>Owned exclusively by one {@link IntrusiveList}, which is itself owned by the single shard
 * thread. No synchronization is used or needed.
 *
 * <h2>Free-list discipline</h2>
 * <ul>
 *   <li>{@link #acquire} — pops a node from the free list, sets all fields, and returns it; falls
 *       back to {@code new ListNode()} on a miss.</li>
 *   <li>{@link #release} — nulls {@code value}, {@code prev}, and {@code next} unconditionally
 *       <em>before</em> the capacity check, so a node dropped to GC never retains its old value or
 *       neighbours, exactly as {@link dev.devgurav.mnemo.store.entry.DictEntryPool} does.</li>
 * </ul>
 *
 * <p>The free list is intrusive: freed nodes link through their own {@code next} field, so pool
 * bookkeeping never allocates a wrapper.
 */
public final class ListNodePool {

    private static final int DEFAULT_MAX_POOLED = 128;

    private final int maxPooled;

    /** Head of the intrusive free list; null when the pool is empty. */
    private ListNode freeHead;

    /** Current number of nodes sitting in the free list. */
    private int poolSize;

    public ListNodePool() {
        this(DEFAULT_MAX_POOLED);
    }

    public ListNodePool(int maxPooled) {
        if (maxPooled <= 0) throw new IllegalArgumentException("maxPooled must be > 0");
        this.maxPooled = maxPooled;
    }

    /**
     * Returns a fully initialised {@link ListNode} from the free list, or freshly allocated on a
     * miss. All three fields are set before returning — the caller does not touch them.
     *
     * @param value the element bytes
     * @param prev  the node that will precede this one in the list, or {@code null} at the head
     * @param next  the node that will follow this one in the list, or {@code null} at the tail
     * @return a ready-to-use node; never {@code null}
     */
    public ListNode acquire(byte[] value, ListNode prev, ListNode next) {
        ListNode node;
        if (freeHead != null) {
            // Pop from free list — sever the free-list link before returning.
            node     = freeHead;
            freeHead = node.next;
            node.next = null;
            poolSize--;
        } else {
            node = new ListNode();
        }
        node.value = value;
        node.prev  = prev;
        node.next  = next;
        return node;
    }

    /**
     * Returns {@code node} to the pool for future reuse.
     *
     * <p>All reference fields are nulled <em>before</em> the capacity check, guaranteeing the
     * null-on-free invariant whether the node is pooled or discarded to GC: a node dropped while
     * still holding a live value/neighbour reference would extend the GC lifetime of those objects.
     *
     * @param node the node to return; must not be {@code null}
     */
    public void release(ListNode node) {
        // Null all reference fields first — unconditionally, before any branching.
        node.value = null;
        node.prev  = null;
        node.next  = null;

        if (poolSize >= maxPooled) return; // discard safely: fields are already nulled

        node.next = freeHead;
        freeHead  = node;
        poolSize++;
    }

    /** The number of nodes currently sitting in the free list. */
    public int poolSize() {
        return poolSize;
    }
}
