# Observability

## Today

- **`INFO`** — the primary introspection surface: server and keyspace stats over the wire. The exact
  fields are documented with the rest of the command surface in
  [api-protocol.md](api-protocol.md).
- **Liveness** — `PING` → `PONG`.

## Performance signals (out of band)

Latency and allocation behaviour are measured deliberately, not scraped from the running server:

- **JMH** microbenchmarks (`src/jmh/`, `DictBenchmark`) for throughput / sampled latency.
- **JFR** for real heap behaviour and GC pauses — kept out of the request path on purpose, because the
  eviction trigger is a *logical* counter, not a heap weigh ([ADR 0006](decisions/0006-logical-maxmemory.md)).
- **async-profiler** for the allocation comparison that justifies the `DictEntry` pool (W3).

See [benchmarking-methodology.md](benchmarking-methodology.md) for the full setup.

## Planned

- Separating **GC-pause p99** from **command p99** in the W4 performance report — the two-front
  tail-latency story ([ADR 0004](decisions/0004-incremental-rehashing.md)). *Trigger: wire richer
  runtime counters into `INFO` if the report needs live numbers rather than benchmark numbers.*
