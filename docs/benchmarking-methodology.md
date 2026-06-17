# Mnemo — Benchmarking Methodology

How every published number is produced so results are reproducible and defensible in any
technical discussion. Cross-reference: GC tuning rationale is in
[architecture-spec.md](architecture-spec.md) §5; the command surface being measured is in
[api-protocol.md](api-protocol.md).

**Cardinal rule: GC max-pause and command p99 are always reported as two separate numbers.**
Conflating them hides whether a latency spike originates in the data plane or in a collector
pause. A benchmark that merges them is uninterpretable.

---

## Ground rules

- **Disclose the hardware** (CPU model, core count, RAM, NUMA topology) and the exact JVM build
  (JDK 25, full ZGC flag set) with every result. A number without a machine is noise.
- **Pin the workload:** key count, value size distribution, pipeline depth, command mix ratio,
  run duration. Results at one workload point do not extrapolate to another.
- **Two curves, never one:** publish GC max-pause from `gc.log` or JFR alongside command p99
  from JMH `SampleTime`. If the benchmark tool merges them, it is the wrong tool for this claim.
- **No bare "X ops/sec"** — always with methodology, hardware, and the full flag set.
- **Warm before measuring:** JIT compilation, ZGC concurrent thread startup, and Netty pool
  preallocation all front-load latency. Warmup must complete before the measurement window opens.

---

## 1. JMH microbenchmarks

Wired via the `me.champeau.jmh` Gradle plugin. Benchmarks live in
`src/jmh/java/dev/devgurav/mnemo/bench/`.

```kotlin
// build.gradle.kts
plugins { id("me.champeau.jmh") version "0.7.2" }
// run:  ./gradlew jmh
```

### Conventions

- Use **both** `@BenchmarkMode(Mode.Throughput)` and `Mode.SampleTime` in the same class.
  `Throughput` gives ops/sec; `SampleTime` gives the full latency distribution including p99 and
  p99.9. The headline rehash story requires the distribution, not the mean.
- `@Warmup(iterations = 5)`, `@Measurement(iterations = 10)`, `@Fork(2)` — forking isolates JIT
  and GC state between runs; never report single-fork results as final.
- `@State(Scope.Thread)` for per-thread setup; `@Param` over `tableSize`
  (`10_000 / 100_000 / 1_000_000`) to show scaling curves, not point estimates.

### Headline benchmarks (in priority order)

1. **Incremental rehashing p99** — `SET`/`put` latency distribution comparing a full-resize
   `Dict` (baseline) against the incremental-rehashing `Dict`. The p99 curve must remain flat
   through the resize threshold; the full-resize baseline must spike. This is the primary
   correctness-of-design proof.
2. **Object pool allocation reduction** — `Dict` put/get throughput with pool `ON` vs `OFF`,
   measured alongside the async-profiler allocation flame graph (§2). The pool must visibly
   eliminate `DictEntry` allocation from the hot path.
3. **SkipList scaling** — `ZADD` / `ZRANK` latency vs N (10³ → 10⁶). Expect O(log N); any
   deviation flags an implementation bug.

---

## 2. async-profiler — allocation and CPU flame graphs

Used to **prove** the `DictEntry` object pool actually reduces allocation pressure; do not assume
the pool is effective — measure it (architecture-spec.md §4 explicitly requires this).

```bash
# Via JMH profiler integration (Linux/WSL2 only — see platform caveat below)
./gradlew jmh \
  -Pjmh.profilers="async:output=flamegraph;event=alloc;libPath=/path/to/libasyncProfiler.so"
```

### Interpretation

Compare two runs: pool `OFF` vs pool `ON`. In the allocation flame graph, `DictEntry.<init>` and
its `byte[]` allocation callees must disappear or reduce to negligible width in the `ON` run.
If they remain prominent, the pool is not being hit on the hot path — investigate the acquire/
release discipline in `Dict`.

For CPU profiles (`event=cpu`), the `RespDecoder` → `MpscArrayQueue.offer` → shard-executor
dispatch path should dominate; any unexpected lock contention or GC safepoint overhead in that
chain is a regression signal.

### Platform caveat

async-profiler targets **Linux and macOS** only. It does not run natively on Windows.

- **Option A (preferred):** run JMH benchmarks inside **WSL2** with a Linux JDK. Flame graphs
  are fully supported.
- **Option B:** run in **CI** (GitHub Actions `ubuntu-latest`). Attach async-profiler to the JMH
  process via the profiler integration above.
- **Option C (Windows fallback):** use **JFR** (§3) for allocation and GC data. JFR runs
  natively on Windows and captures the same GC pause and allocation-rate signals, without the
  per-frame call-stack resolution of async-profiler.

---

## 3. JFR — GC pauses and allocation pressure (Windows-compatible)

Java Flight Recorder runs on all platforms, including Windows, without additional binaries.

```text
# Add to applicationDefaultJvmArgs or to the JMH fork JVM args:
-XX:StartFlightRecording=filename=mnemo.jfr,settings=profile,dumponexit=true
-Xlog:gc*:file=gc.log:time,uptime,tags
```

Open `mnemo.jfr` in JDK Mission Control:

- **GC pause distribution** — the "max pause < X ms" claim is verified here, not from JMH.
  Extract the max and p99.9 pause from the GC event stream.
- **Allocation pressure** — the `jdk.ObjectAllocationInNewTLAB` and
  `jdk.ObjectAllocationOutsideTLAB` events show allocation rate per class. Compare pool ON/OFF
  to validate the pool reduces `DictEntry` churn.

`gc.log` provides the same GC pause times in text form, suitable for charting in any tool.

---

## 4. Epsilon GC — allocation ceiling and leak check

```text
-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx<large>g
```

Epsilon never reclaims memory. A bounded workload run that completes without OOM proves the data
plane has no unbounded allocation leak under that workload. The peak allocation rate observed
under Epsilon is the true ceiling that the real collector (ZGC) must stay ahead of.

Run this check after any change to `Dict`, `SkipList`, or eviction logic to guard against
introducing a slow leak.

---

## 5. End-to-end throughput — `redis-benchmark` via Docker

Mnemo is RESP2-compatible, so Redis's own benchmark driver exercises it directly.
`redis-cli` and `redis-benchmark` are not natively available on Windows; run them from Docker.

```bash
# Baseline SET/GET throughput, pipelined depth 16
docker run --rm redis redis-benchmark \
  -h host.docker.internal -p 6379 \
  -t set,get -n 1_000_000 -P 16 -q

# Full command mix with latency percentiles
docker run --rm redis redis-benchmark \
  -h host.docker.internal -p 6379 \
  -t set,get,incr,lpush,rpush,lpop,rpop,sadd,hset,spop,mset \
  -n 1_000_000 -P 16 --latency-history

# memtier_benchmark for more realistic key-space distribution
docker run --rm redislabs/memtier_benchmark \
  -s host.docker.internal -p 6379 --protocol=redis \
  --ratio=1:10 --key-pattern=R:R --key-maximum=1000000
```

Record ops/sec and p50/p99 per command from `redis-benchmark`, alongside the ZGC max-pause from
the concurrent JFR recording. These two numbers must be reported together.

---

## Results (fill from Week 2 onward)

**Environment:** Intel Core i5-11300H (4C/8T @ 3.1 GHz) · 16 GB RAM · Windows 11 ·
OpenJDK 25.0.2 · `-XX:+UseZGC -Xmx3g` · JMH `me.champeau.jmh` 0.7.2, fork = 1.

### Headline 1 — incremental rehashing removes the stop-the-world resize stall

The two engines are identical except for resize strategy:
[`DictSTW`](../src/jmh/java/dev/devgurav/mnemo/bench/DictSTW.java) is a clone of `Dict` reusing the
same chaining and `DictEntryPool`, with the dual-table incremental rehash replaced by a one-shot
`resize()`. Any difference is therefore attributable to the rehash strategy alone.

**Finding A — bulk inserts are indistinguishable.**
[`RehashBenchmark`](../src/jmh/java/dev/devgurav/mnemo/bench/RehashBenchmark.java) (SampleTime,
growth from empty, capacity 16 → 131 072):

| engine | p50 | p90 | p99 | p99.9 | p100 |
| --- | --- | --- | --- | --- | --- |
| incremental | 0.20 µs | 0.40 µs | 1.20 µs | 26.1 µs | 4.1 ms\* |
| stop-the-world | 0.10 µs | 0.20 µs | 0.80 µs | 24.1 µs | 3.7 ms\* |

\* The p100 here is JIT/GC/safepoint noise common to **both** engines, not the resize: at this scale
a single resize (~49 k entries) is below that noise floor, and `SampleTime` sub-samples a ~1-in-5 000
event. The bulk of the distribution (p50–p99) is sub-microsecond and equal — which is precisely *why*
a p99 number alone cannot tell this story. The cost is concentrated in a handful of resize-triggering
puts, so it must be isolated, not sampled.

**Finding B — the resize-triggering put is where they diverge.**
[`RehashSpikeBenchmark`](../src/jmh/java/dev/devgurav/mnemo/bench/RehashSpikeBenchmark.java)
(SingleShot, primed to the load-factor edge at capacity 4 194 304 ≈ 3.1 M entries, heap
GC-stabilised so the working set is in old-gen, then the single threshold-crossing put is timed;
n = 20):

| engine | resize-triggering put (mean ± 99.9% CI) |
| --- | --- |
| incremental | **3.33 ms ± 0.72** |
| stop-the-world | **112.6 ms ± 17.3** |

→ **≈ 34× lower worst-case put latency.** The stop-the-world engine copies all ~3.1 M entries on the
one put that crosses the load factor. (On a promoted/old-gen keyspace each of those 3.1 M reference
rewrites also pays ZGC's store barrier, which lifts the STW copy above the naïve O(n) estimate; a
fresh young-gen copy is cheaper but still tens of ms.) The incremental engine allocates the doubled
backing array and migrates a single bucket on that put, spreading the rest across the next ~3.1 M
operations.

**Scope of the claim (honest).** Incremental rehashing removes the **O(n) copy** from the hot path;
it does *not* make a resize free — both engines still pay the unavoidable doubled-array allocation
(the ~3 ms is the cost of allocating and zeroing a 64 MB `DictEntry[]`). The win is elimination of
the stall, not a zero-cost grow. Dev-profile numbers (laptop, single fork, n = 20):
directional and reproducible (`jmh { includes.set(listOf("RehashSpikeBenchmark")) }`), not yet a
publish-grade multi-fork run.

### Headline 2 — `DictEntry` object pool eliminates allocation from the hot path

**Benchmark:** [`DictEntryPoolBenchmark`](../src/jmh/java/dev/devgurav/mnemo/bench/DictEntryPoolBenchmark.java)  
Run: `./gradlew jmh -Pjmh.includes=DictEntryPoolBenchmark`

Two variants share the same acquire-set-release loop; they differ only in whether the free list is used:

- `poolOn` — acquire from the pre-warmed free list (no `new DictEntry()`), release back after use.
- `poolOff` — always calls `new DictEntry()`, simulating a Dict without a pool; the node leaks as
  short-lived garbage to let GC see its true allocation pressure.

The allocation signal is in JFR (`jdk.ObjectAllocationInNewTLAB`) or async-profiler:

- Pool ON: `DictEntry.<init>` must be absent or ≤ noise level.
- Pool OFF: `DictEntry.<init>` is the dominant allocator on the benchmark hot path.

Add to JVM args to record while running JMH:

```text
-XX:StartFlightRecording=filename=pool-benchmark.jfr,settings=profile,dumponexit=true
```

| Metric | Pool ON | Pool OFF |
| --- | --- | --- |
| Throughput (ops/µs) | **258.0** | 69.4 |
| p99 latency (µs) | 0.10 | 0.10 |
| p99.9 latency (µs) | **0.20** | 0.60 |
| p100 latency (µs) | **71.2** | 114.3 |

Pool ON is **3.7× higher throughput** and **3× lower p99.9**. The p99 floor at 0.10 µs is the
JMH sampling resolution limit; the throughput and tail-latency numbers carry the real signal. JFR
`jdk.ObjectAllocationInNewTLAB` for the pool-ON run shows zero `DictEntry` allocations after the
`@Setup` warmup phase (128 pre-heated entries); the pool-OFF run shows one allocation per
iteration throughout the measurement window.

### Headline 2b — ZGC pause vs command latency (separate curves)

Dict.get p99 from `DictBenchmark` (SampleTime, n = 1 M entries, `-XX:+UseZGC -Xmx3g`):

| key-space size | p50 | p99 | p99.9 |
| --- | --- | --- | --- |
| 1 024 | ≈ 0 µs | 0.10 µs | 8.6 µs |
| 65 536 | ≈ 0 µs | 0.10 µs | 7.2 µs |
| 1 048 576 | ≈ 0 µs | 0.40 µs | 5.6 µs |

ZGC max-pause during the above run: **< 1 ms** (from `-Xlog:gc*` output; ZGC concurrent cycles
run in the background and do not appear in the SampleTime distribution). Command p99 (0.10–0.40 µs)
is at least **4 orders of magnitude** below the GC pause budget — the two curves do not conflict.

### Headline 3 — end-to-end throughput (`redis-benchmark`)

Run `redis-benchmark` against a running Mnemo instance (requires Docker Desktop):

```bash
docker run --rm redis redis-benchmark \
  -h host.docker.internal -p 6379 \
  -t set,get -n 1000000 -P 16 -q
```

Single-shard Dict store, pipeline depth 16, 1 M operations, Docker → `host.docker.internal`:

| Command | ops/sec | p50 |
| --- | --- | --- |
| SET | **67 879** | 9.2 ms |
| GET | **65 893** | 9.6 ms |

The p50 latency here is dominated by the Docker-on-Windows network path (host.docker.internal
adds ~9 ms per round-trip). The JMH in-process numbers (Dict.get p99 ≤ 0.4 µs at 1 M keys)
are the true data-plane measure; redis-benchmark through Docker proves RESP2 wire compatibility,
not raw throughput. Run on Linux with `--network host` to eliminate the bridge overhead.

### Headline 4 — Dict key-space scaling (O(1) expected)

From `DictBenchmark` (SampleTime, put and get across 3 key-space sizes):

| N | Dict.put p99 | Dict.get p99 | Dict.put p99.9 | Dict.get p99.9 |
| --- | --- | --- | --- | --- |
| 1 024 | 0.20 µs | 0.10 µs | 4.6 µs | 8.6 µs |
| 65 536 | 0.90 µs | 0.10 µs | 11.3 µs | 7.2 µs |
| 1 048 576 | 0.90 µs | 0.40 µs | 15.3 µs | 5.6 µs |

p99 remains ≤ 1 µs even at 1 M entries — consistent with O(1) average-case chain lookup. p99.9
growth (4.6 → 15.3 µs) is attributable to cache misses on the cold entry chain at large N, not
to algorithmic complexity.
