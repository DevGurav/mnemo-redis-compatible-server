package dev.devgurav.mnemo.server;

/**
 * Server configuration: TCP port, which keyspace store to use, and the {@code maxmemory} bound.
 *
 * <p>{@code useDict=false} runs the temporary {@link dev.devgurav.mnemo.store.HashMapStore}
 * so the server works end-to-end today. Set {@code MNEMO_USE_DICT=true} (or {@code --use-dict true})
 * to switch to your own {@link dev.devgurav.mnemo.store.Dict} once its spec tests are green.
 *
 * <p>{@code maxmemory} is the logical-byte budget for the evictable (string) keyspace; {@code 0}
 * means unlimited (no eviction). Approximate-LRU eviction is only armed when {@code maxmemory > 0}
 * <em>and</em> the store is a {@code Dict} (the sampler reads {@code Dict} bucket arrays).
 */
public record Config(int port, boolean useDict, long maxmemory) {

    /** Back-compat constructor for callers that predate {@code maxmemory} (defaults to unlimited). */
    public Config(int port, boolean useDict) {
        this(port, useDict, 0);
    }

    public static Config defaults() {
        return new Config(6379, false, 0);
    }

    public static Config fromArgs(String[] args) {
        int port = envInt("MNEMO_PORT", 6379);
        boolean useDict = Boolean.parseBoolean(
                System.getenv().getOrDefault("MNEMO_USE_DICT", "false"));
        long maxmemory = envLong("MNEMO_MAXMEMORY", 0);
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--use-dict" -> useDict = Boolean.parseBoolean(args[i + 1]);
                case "--maxmemory" -> maxmemory = Long.parseLong(args[i + 1]);
                default -> { /* ignore */ }
            }
        }
        return new Config(port, useDict, maxmemory);
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static long envLong(String key, long def) {
        String v = System.getenv(key);
        return v == null ? def : Long.parseLong(v);
    }
}
