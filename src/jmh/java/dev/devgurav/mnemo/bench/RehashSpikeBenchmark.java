package dev.devgurav.mnemo.bench;

import dev.devgurav.mnemo.store.Dict;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Resize-spike isolation — measures the cost of the single {@code put} that crosses the load factor
 * and triggers a table grow, which is exactly the operation where a stop-the-world resize stalls.
 *
 * <p>Where {@link RehashBenchmark} (SampleTime) drowns the rare resize event under JIT/GC/safepoint
 * noise, this benchmark targets it directly: {@link Level#Iteration} setup primes a table to exactly
 * the load-factor edge of a large capacity (≈3.1M entries at capacity 4M), then a single measured
 * {@code put} crosses the threshold. {@link Mode#SingleShotTime} times that one operation.
 *
 * <ul>
 *   <li>{@link #engine} = {@code stw} — the triggering put rebuilds the whole table in one shot,
 *       copying ~3.1M entries. This is the multi-ms stall.</li>
 *   <li>{@link #engine} = {@code incremental} — the triggering put only allocates the new (double-
 *       sized) table and migrates one bucket; the remaining migration is amortised across later
 *       puts. Its cost is the new-table allocation, not the full copy.</li>
 * </ul>
 *
 * <p>The honest contrast this surfaces: incremental rehashing eliminates the O(n) copy from the
 * triggering put, but does <em>not</em> make it free — the doubled backing array still has to be
 * allocated up front. Runs under ZGC (configured in {@code build.gradle.kts}).
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 20) // SingleShot ⇒ one sample per iteration; 20 for a stable interval
@Fork(1)
@State(Scope.Thread)
public class RehashSpikeBenchmark {

    /** Capacity the table sits at when primed; the grow under test is 4M → 8M. */
    private static final int CAP   = 1 << 22;       // 4,194,304
    /** Entries to prime: exactly the load-factor threshold, so the next put triggers the resize. */
    private static final int PRIME = CAP * 3 / 4;   // 3,145,728

    @Param({"incremental", "stw"})
    public String engine;

    private String[] keys;
    private byte[] value;

    private Dict incremental;
    private DictSTW stw;

    @Setup(Level.Trial)
    public void generateKeys() {
        keys  = new String[PRIME + 1]; // PRIME to prime + 1 to trigger
        value = new byte[]{1, 2, 3, 4};
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "key:" + i;
        }
    }

    /** Rebuild a freshly-primed table before every measured shot (excluded from the timing). */
    @Setup(Level.Iteration)
    public void prime() {
        if (engine.equals("incremental")) {
            incremental = new Dict();
            for (int i = 0; i < PRIME; i++) incremental.put(keys[i], value);
        } else {
            stw = new DictSTW();
            for (int i = 0; i < PRIME; i++) stw.put(keys[i], value);
        }
        // Reclaim the ~300MB of priming garbage now, off the clock, so a ZGC cycle is far less
        // likely to land inside the single measured resize put and inflate its latency.
        System.gc();
    }

    @Benchmark
    public void resizeTrigger() {
        if (engine.equals("incremental")) {
            incremental.put(keys[PRIME], value);
        } else {
            stw.put(keys[PRIME], value);
        }
    }
}
