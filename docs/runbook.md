# Runbook

How to run, operate, and (eventually) recover Mnemo.

## Run locally

```bash
./gradlew run                      # start on port 6379, HashMapStore backend
MNEMO_USE_DICT=true ./gradlew run  # run on the from-scratch Dict instead
```

Connect with any real Redis client. On Windows `redis-cli` isn't native — use Docker:

```bash
docker run --rm -it redis redis-cli -h host.docker.internal -p 6379
```

## Container

A `docker/` directory and `.dockerignore` exist; a published image + Compose are part of W4. *Trigger:
fill the build/run/compose commands here when the W4 Docker image lands.*

## Operate

- **Health check:** a successful `PING` → `PONG` is liveness.
- **Metrics:** the `INFO` command surfaces server/keyspace stats — see
  [api-protocol.md](api-protocol.md) and [observability.md](observability.md).

## Recovery

Mnemo is currently **in-memory only** — a restart is a cold start with an empty keyspace. Durable
recovery (AOF replay, survive `kill -9`) is W3 work. *Trigger: document the AOF replay + crash-recovery
procedure here when persistence lands ([roadmap](roadmap.md) W3).*
