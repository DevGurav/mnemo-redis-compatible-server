package dev.devgurav.mnemo.bench;

import dev.devgurav.mnemo.store.entry.DictEntry;
import dev.devgurav.mnemo.store.entry.DictEntryPool;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Compares the allocation cost of the {@link DictEntryPool} against a raw {@code new DictEntry()}
 * on every acquire — quantifying the allocation-reduction claim in benchmarking-methodology.md §2
 * (Headline 2).
 *
 * <p>Two benchmarks share identical logic (acquire → set fields → release) but differ in whether
 * the pool's free-list is used (pool ON) or bypassed (pool OFF, always allocates).
 *
 * <h2>How to read the results</h2>
 * <ul>
 *   <li><b>Throughput</b> — ops/µs. Pool ON should match or exceed pool OFF by recycling nodes
 *       rather than waiting for the allocator.</li>
 *   <li><b>SampleTime</b> — p99 / p99.9. Allocation pressure under pool OFF causes GC safepoints
 *       that inflate tail latency; pool ON stays below the GC noise floor.</li>
 *   <li><b>JFR / async-profiler</b> — the definitive signal. Under pool ON,
 *       {@code DictEntry.<init>} must be absent or negligible in the allocation flame graph.
 *       Under pool OFF, it is the dominant allocator on the hot path.</li>
 * </ul>
 *
 * <p>Run: {@code ./gradlew jmh -Pjmh.includes=DictEntryPoolBenchmark}
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class DictEntryPoolBenchmark {

    private static final byte[] KEY   = "benchmark-key".getBytes();
    private static final byte[] VALUE = "benchmark-value".getBytes();

    private DictEntryPool pool;

    @Setup(Level.Trial)
    public void setup() {
        // Pre-warm the pool so the first measurement iteration isn't measuring cold-start
        // allocation: acquire/release 128 entries to fill the free list up to DEFAULT_MAX_POOLED.
        pool = new DictEntryPool();
        DictEntry[] warmup = new DictEntry[128];
        for (int i = 0; i < 128; i++) warmup[i] = pool.acquire(0, KEY, VALUE, null);
        for (DictEntry e : warmup) pool.release(e);
    }

    /**
     * Pool ON — acquires a node from the free list (O(1), no allocation after warmup) and
     * immediately releases it back. Represents steady-state insert + delete of a single key.
     */
    @Benchmark
    public DictEntry poolOn() {
        DictEntry e = pool.acquire(0, KEY, VALUE, null);
        pool.release(e);
        return e;
    }

    /**
     * Pool OFF — always allocates a fresh node with {@code new DictEntry()}, simulating a Dict
     * implementation without a pool. The {@code release} call is skipped so no node is recycled —
     * each iteration the node becomes garbage and must be collected.
     *
     * <p>Expected: GC safepoints visible in JFR {@code GcPause} events and p99 latency higher
     * than pool ON, especially at high allocation rates.
     */
    @Benchmark
    public DictEntry poolOff() {
        DictEntry e = new DictEntry();
        e.hash  = 0;
        e.key   = KEY;
        e.value = VALUE;
        return e; // intentionally leaks — the node is never pooled; becomes short-lived garbage
    }
}
