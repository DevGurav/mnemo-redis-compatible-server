package dev.devgurav.mnemo.bench;

import dev.devgurav.mnemo.store.Dict;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Insert-with-growth latency benchmark — the experiment that validates Mnemo's headline thesis:
 * incremental rehashing flattens the tail-latency spike a stop-the-world resize inflicts.
 *
 * <p>Two engines, identical except for their resize strategy (see {@link DictSTW}), are each grown
 * from empty by inserting distinct keys until the table has doubled ~13 times:
 * <ul>
 *   <li>{@link #incremental} — the production {@link Dict}: every {@code put} migrates a bounded
 *       slice of the old table, so the resize cost is amortised into O(1) per operation.</li>
 *   <li>{@link #stw} — the {@link DictSTW} control: the load-factor-crossing {@code put} rebuilds
 *       the whole table in one shot and stalls for the full O(n).</li>
 * </ul>
 *
 * <p>{@link Mode#SampleTime} records the per-{@code put} latency distribution. Because only ~1
 * insert in tens of thousands triggers a resize, the STW penalty lands in the <em>deep tail</em>
 * (p99.9 / p99.99 / p100), not at p99 — which is precisely the point: STW turns growth into rare,
 * catastrophic stalls, exactly what a max/p100 captures and a throughput average hides.
 *
 * <p>Run under ZGC (the production GC, configured in {@code build.gradle.kts}) so a young-gen pause
 * cannot be mistaken for the algorithmic spike being measured.
 *
 * <p>Each growth cycle inserts {@link #KEYS} distinct keys; the table is then rebuilt empty and the
 * cycle repeats for the rest of the iteration, so the measured window is dominated by growth.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class RehashBenchmark {

    /** Distinct keys per growth cycle — forces ~13 doublings (capacity 16 → 131 072). */
    private static final int KEYS = 1 << 16; // 65 536

    private String[] keys;
    private byte[] value;

    private Dict incremental;
    private int incrIdx;

    private DictSTW stw;
    private int stwIdx;

    @Setup(Level.Trial)
    public void generateKeys() {
        keys  = new String[KEYS];
        value = new byte[]{1, 2, 3, 4}; // fixed sentinel — value churn isn't what we measure
        for (int i = 0; i < KEYS; i++) {
            keys[i] = "key:" + i;
        }
    }

    @Setup(Level.Iteration)
    public void freshTables() {
        incremental = new Dict();
        incrIdx     = 0;
        stw         = new DictSTW();
        stwIdx      = 0;
    }

    @Benchmark
    public void incremental() {
        if (incrIdx == KEYS) {        // cycle complete — rebuild empty and grow again
            incremental = new Dict();
            incrIdx = 0;
        }
        incremental.put(keys[incrIdx++], value);
    }

    @Benchmark
    public void stw() {
        if (stwIdx == KEYS) {
            stw = new DictSTW();
            stwIdx = 0;
        }
        stw.put(keys[stwIdx++], value);
    }
}
