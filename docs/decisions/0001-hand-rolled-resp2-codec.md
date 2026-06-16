# 1. Hand-roll the RESP2 codec on Netty

- Status: Accepted
- Date: 2026-06-16
- Deciders: Devendra Gurav

## Context

Mnemo has to speak the real Redis wire protocol (RESP2) so that `redis-cli`, `Jedis`, and
`redis-benchmark` connect to it unmodified — that compatibility is the whole point of the project.
RESP2 framing has two shapes that matter: multibulk arrays (what real clients send) and inline
commands (what a human types into a raw socket). Both can arrive split across TCP reads.

The options were to pull in an existing RESP library, or to write the decoder/encoder by hand against
Netty's `ByteBuf`.

## Decision

Hand-write the RESP2 decoder and encoder in `net/resp/`, driven directly off Netty's `ByteBuf`,
handling multibulk + inline framing and partial-read buffering.

## Consequences

- The protocol layer is fully owned and understood — there is no black box between the socket and the
  command dispatch, which is exactly the systems competence the project is meant to demonstrate.
- Partial-read buffering is our responsibility and is covered by `RespCodecTest` (EmbeddedChannel)
  plus a real-socket `EndToEndTest`.
- `ByteBuf` lifetime stays under our control, which is what lets us enforce the EventLoop boundary
  in [ADR 0002](0002-single-threaded-command-execution.md).

## Alternatives considered

- **A RESP parsing library** — rejected: it would hide the part of the system most worth building by
  hand, and add a dependency for code that is a few hundred lines we want to own.
