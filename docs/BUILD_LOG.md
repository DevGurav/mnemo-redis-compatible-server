# Build Log — Mnemo

A running log of what was built and why. Newest first.

## Architecture decisions — pre-Week-2 hardening (design + docs only, no code yet)

Locked four execution-flaw patches into the [blueprint](act-as-a-senior-kind-waffle.md) and split the
engineering docs into `docs/`:

- **Hash table:** stays **separate chaining** permanently (open-addressing refactor dropped). GC
  reference-churn is handled by a **`DictEntry` object pool** — refs nulled on free, per-shard, bounded —
  not a primitive-array rewrite. Keeps incremental rehashing clean.
- **Thread boundary:** Netty `ByteBuf` alloc/release stays inside EventLoop worker threads; the decoder
  copies frames into plain POJOs, releases the buffer immediately, and only POJOs cross the MPSC queue to
  the shard executor (preserves Netty's per-thread arena fast-path; trivial buffer lifetime).
- **Memory bound:** `maxmemory` is a **deterministic logical capacity** (payload bytes / key count, O(1)
  counter), not a physical object-graph weigher with `Runtime` reconciliation — real heap is observed
  out-of-band via JFR.
- **Docs:** engineering documentation is now version-controlled under `docs/` — `architecture-spec.md`,
  `api-protocol.md`, `benchmarking-methodology.md` (the old `ARCHITECTURE.md` / `BENCHMARKS.md` folded in).

No production code changed — this is design + documentation discipline ahead of the Week-2 build.

## Week 1 — Foundation (server, protocol, dispatch, test harness)

Stood up the skeleton of a Redis-compatible server and proved it end-to-end.

- **Build:** Gradle 9.5.1 (wrapper, checksum-verified). Targets Java 21 bytecode (`options.release = 21`)
  for portability; builds and runs on the installed JDK 25.
- **Networking:** Netty 4.1 pipeline — a hand-written RESP2 decoder/encoder (`net/resp/`) that handles
  both multibulk (`redis-cli`/Jedis) and inline commands, including partial-read buffering.
- **Concurrency:** multi-threaded Netty I/O hands each command to a single command thread, so the
  keyspace is lock-free. This is the data-plane design the whole project rests on (see architecture-spec.md §1).
- **Commands:** `PING ECHO SET GET DEL EXISTS COMMAND` via a Command-pattern registry.
- **Storage seam:** a `KeyValueStore` interface with a temporary `HashMapStore` so the server runs today,
  and a `Dict` stub (the real hash table) left to implement against a red spec.
- **Tests:** `RespCodecTest` (EmbeddedChannel) and `EndToEndTest` (real socket, full PING/SET/GET/
  EXISTS/DEL round-trip) are green. `DictTest` + `DictPropertyTest` (jqwik) are tagged `spec` and
  excluded from the default build — they fail on purpose until `Dict` is implemented (`./gradlew specTest`).
- **CI:** GitHub Actions builds + runs the green tests on Temurin 21.

Verified: `./gradlew test` green; `./gradlew specTest` red (7 failing on the unimplemented `Dict`,
exactly as intended).

Next: implement `Dict` to turn the spec green, then add incremental rehashing with a JMH p99 benchmark.
