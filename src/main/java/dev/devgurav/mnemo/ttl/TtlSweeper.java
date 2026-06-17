package dev.devgurav.mnemo.ttl;

import dev.devgurav.mnemo.store.Db;

/**
 * Active TTL expiry — called by the shard thread before each command dispatch so it runs on the
 * same thread that owns the {@link Db}, removing the need for any synchronization.
 *
 * <p>Redis's active expiry checks 20 random keys every 100 ms and repeats the cycle if more than
 * 25% of them were expired. We use the same cadence: {@link #sweepIfDue()} is a no-op when fewer
 * than {@value SWEEP_INTERVAL_MS} ms have elapsed since the last sweep, so even under very high
 * command rates the sweep runs at most 10 times per second.
 *
 * <p>Lazy expiry ({@link Db#expireIfNeeded}) handles the common case: a key is deleted at the
 * first access after its deadline. Active expiry cleans up keys that are never re-accessed —
 * avoiding unbounded growth of the {@code expiries} map in a write-heavy workload.
 */
public final class TtlSweeper {

    private static final long SWEEP_INTERVAL_MS = 100;
    private static final int  SWEEP_SAMPLE      = 20;

    private final Db db;
    private long lastSweepMs;

    public TtlSweeper(Db db) {
        this.db = db;
    }

    /** Run a sweep if the interval has elapsed; otherwise return immediately. */
    public void sweepIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs >= SWEEP_INTERVAL_MS) {
            lastSweepMs = now;
            db.sweepExpiredKeys(SWEEP_SAMPLE);
        }
    }
}
