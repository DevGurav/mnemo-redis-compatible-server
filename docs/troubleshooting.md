# Troubleshooting

Recurring footguns and their fixes.

- **`redis-cli` not found on Windows** — it isn't shipped natively. Use the Docker one-liner:
  `docker run --rm -it redis redis-cli -h host.docker.internal -p 6379`.

- **`./gradlew specTest` is red on a fresh clone** — that's by design. Spec tests are the contracts
  for the from-scratch structures; they go green as you implement them. `./gradlew test` (plumbing) is
  the always-green CI gate. See [testing.md](testing.md).

- **`sun.misc.Unsafe … will be removed` warning on run** — emitted by Netty on JDK 25, not by Mnemo.
  Harmless; the build targets Java 21 bytecode and runs on the installed JDK.

- **Server uses a `HashMap`, not my `Dict`** — the default backend is the `HashMapStore` placeholder.
  Run with `MNEMO_USE_DICT=true ./gradlew run` to exercise the from-scratch `Dict`.

- **A `.jqwik-database` file keeps reappearing** — it's jqwik's local failure-shrinking cache, written
  when property tests run. It's git-ignored; leave it alone.

*Add new entries here the second time a problem bites — the first fix is a one-off, the second is a
pattern worth recording.*
