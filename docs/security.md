# Security

## Current posture

Mnemo is a **single-node, trusted-network** store, matching Redis's own default stance: no
authentication, no ACLs, no TLS. It is intended to be run on `localhost` or inside a private
network/Docker network, never exposed directly to the internet.

The one untrusted surface today is the **RESP parser** (`net/resp/`), which reads bytes straight off
the socket. Its hardening is correctness-driven: bounded partial-read buffering, explicit framing for
both multibulk and inline commands, and immediate `ByteBuf` release inside the EventLoop so a
malformed or partial frame can't leak buffers. This is covered by `RespCodecTest`.

## Threats deliberately out of scope (for now)

- **AUTH / ACLs** — no password or per-command authorization yet. *Trigger: add when the command
  surface stabilizes or any networked deployment is planned.*
- **TLS** — plaintext only. *Trigger: add if Mnemo is ever exposed beyond a trusted network.*
- **Resource-exhaustion limits** — max request size / max connections aren't capped yet. *Trigger:
  revisit alongside the `maxmemory` eviction work in W3 ([ADR 0006](decisions/0006-logical-maxmemory.md)).*

> This is a stub with explicit triggers, not an empty file: the absence of these controls is a
> deliberate, recorded decision for a single-node learning project — not an oversight.
