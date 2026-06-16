# 2. Multi-threaded I/O, single-threaded command execution

- Status: Accepted
- Date: 2026-06-16
- Deciders: Devendra Gurav

## Context

The keyspace is shared mutable state. The classic ways to make it safe under concurrent clients are
lock striping (many threads, fine-grained locks) or a single owning thread (no locks, serialized
commands). Redis itself is famously single-threaded on the command path. We need an answer that is
both correct and defensible in an interview, and that doesn't bottleneck network I/O.

## Decision

Split the two concerns:

- **I/O is multi-threaded** — Netty's EventLoop group handles sockets, parsing, and encoding across
  cores.
- **Command execution is single-threaded** — every decoded command is handed to one command thread
  that owns the keyspace, so the data plane needs **no locks**.

The decoder copies each frame into a plain POJO and releases the `ByteBuf` immediately; only POJOs
cross the boundary to the command thread. `ByteBuf` alloc/release stays inside the EventLoop worker
so Netty's per-thread arena fast-path is preserved.

## Consequences

- No lock contention, no deadlocks, no torn reads on the keyspace — correctness by construction.
- Single-command latency is dominated by the command thread; tail latency therefore hinges on keeping
  individual operations short (this is the motivation for [ADR 0004](0004-incremental-rehashing.md)).
- Horizontal scaling across cores comes later via shared-nothing sharding (W4): N command threads,
  each owning a disjoint slice of the keyspace, fed POJOs over an MPSC queue — not by adding locks.

## Alternatives considered

- **Lock-striped concurrent map** — rejected: re-introduces the contention and reasoning burden we
  set out to avoid, and makes tail-latency analysis far harder.
- **Fully single-threaded (I/O included)** — rejected: wastes cores on parsing/encoding that
  parallelize cleanly and don't touch the keyspace.
