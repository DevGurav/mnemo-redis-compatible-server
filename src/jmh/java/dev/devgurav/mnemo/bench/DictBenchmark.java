package dev.devgurav.mnemo.bench;

import dev.devgurav.mnemo.store.Dict;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH throughput + latency benchmarks for {@link Dict}.
 *
 * <p>Run via: {@code ./gradlew jmh}
 *
 * <p>The benchmark focuses on <em>steady-state</em> behaviour: the Dict is pre-loaded with
 * {@code tableSize} entries during {@link #setup()} so that we measure hot-path lookup and
 * update performance, not first-insertion growth.
 *
 * <h2>Benchmark matrix</h2>
 * <ul>
 *   <li>{@link #put} — overwrite path (key already present; no size growth, no rehash).</li>
 *   <li>{@link #get} — lookup across a fully populated table (no rehash).</li>
 * </ul>
 *
 * <h2>Measurement modes</h2>
 * <ul>
 *   <li>{@link Mode#Throughput} — operations per microsecond (multiply by 1e6 for ops/sec).</li>
 *   <li>{@link Mode#SampleTime} — per-operation latency distribution, including p99/p999.
 *       This is the mode that surfaces tail latency; {@code Throughput} alone cannot.</li>
 * </ul>
 *
 * <h2>Scope &amp; caveat</h2>
 * <p>Both methods operate on a table that is already at its final size, so neither triggers an
 * incremental rehash. This suite therefore measures <em>steady-state</em> hot-path cost, not the
 * p99-flattening effect of incremental rehashing during growth — that requires a separate
 * insert-with-growth benchmark (see Week-3 follow-up).
 *
 * <h2>Interpreting results</h2>
 * <p>Compare {@code tableSize=1024} vs {@code tableSize=1048576} to observe the cache-miss cliff:
 * a sharp throughput drop (and p99 rise) signals the working set has overflowed L1/L2 into L3/RAM.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DictBenchmark {

    /**
     * Shared state, initialised once per benchmark fork.
     *
     * <p>Using {@link Scope#Benchmark} means all JMH worker threads share a single {@link Dict}
     * instance, which is the realistic production model (one Dict per Mnemo shard, many threads
     * feeding work via ShardExecutor's MpscArrayQueue).
     */
    @State(Scope.Benchmark)
    public static class BenchState {

        /**
         * Number of entries pre-loaded into the Dict before measurement starts.
         *
         * <p>JMH injects each value in sequence so you get one result row per size:
         * <ul>
         *   <li>1 024 — fits entirely in L1/L2; baseline for "best possible" throughput.</li>
         *   <li>65 536 — L3 resident on most modern CPUs.</li>
         *   <li>1 048 576 — overflows L3; reveals main-memory latency impact.</li>
         * </ul>
         */
        @Param({"1024", "65536", "1048576"})
        public int tableSize;

        public Dict dict;

        /** Keys are pre-computed strings to avoid encoding cost on the hot path. */
        public String[] keys;

        /** Counter used to cycle through keys in round-robin fashion during measurement. */
        public int cursor;

        @Setup(Level.Trial)
        public void setup() {
            dict   = new Dict();
            keys   = new String[tableSize];
            byte[] value = new byte[]{1, 2, 3, 4}; // fixed 4-byte sentinel value

            for (int i = 0; i < tableSize; i++) {
                keys[i] = "key:" + i;
                dict.put(keys[i], value);
            }
            cursor = 0;
        }

        /** Returns the next key in round-robin order, avoiding branch-heavy modulo. */
        public String nextKey() {
            int i = cursor++;
            if (cursor == tableSize) cursor = 0;
            return keys[i];
        }
    }

    /**
     * Measures overwrite (update) throughput.
     *
     * <p>The key is always present, so this exercises the "walk chain, overwrite value" fast path
     * in {@link Dict#put} without triggering any resize or pool allocation.
     */
    @Benchmark
    public void put(BenchState state) {
        state.dict.put(state.nextKey(), new byte[]{9});
    }

    /**
     * Measures lookup throughput.
     *
     * <p>All keys are present, so this is a pure chain-walk hit-path benchmark.
     * The returned {@code byte[]} reference is consumed by JMH's {@link org.openjdk.jmh.infra.Blackhole}
     * injection to prevent dead-code elimination.
     */
    @Benchmark
    public byte[] get(BenchState state) {
        return state.dict.get(state.nextKey());
    }
}
