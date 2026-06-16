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

**Environment:** _CPU model / core count / RAM_ · JDK 25 ·
`-XX:+UseZGC -Xms_g -Xmx_g -XX:+AlwaysPreTouch -XX:SoftMaxHeapSize=_g`

### Headline 1 — incremental rehashing eliminates p99 spikes

| Dict variant | p50 | p99 | p99.9 | max |
| --- | --- | --- | --- | --- |
| Full resize (stop-the-world) | — | — | — | — |
| Incremental rehashing | — | — | — | — |

### Headline 2 — GC pause vs command latency (separate curves)

| Metric | Value |
| --- | --- |
| ZGC max pause (`gc.log` / JFR) | — |
| Command p99 (JMH `SampleTime`) | — |
| Allocation rate, pool OFF → ON (async-profiler / JFR) | — → — MB/s |

### Headline 3 — end-to-end throughput (`redis-benchmark`)

| Command | ops/sec | p50 | p99 |
| --- | --- | --- | --- |
| SET | — | — | — |
| GET | — | — | — |

### Headline 4 — sorted-set scaling (skip list, expected O(log N))

| N | ZADD p99 | ZRANK p99 | ZRANGE(k=100) p99 |
| --- | --- | --- | --- |
| 10³ | — | — | — |
| 10⁴ | — | — | — |
| 10⁵ | — | — | — |
| 10⁶ | — | — | — |
