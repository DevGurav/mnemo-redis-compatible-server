package dev.devgurav.mnemo.server;

import dev.devgurav.mnemo.store.evict.EvictionPolicy;

/**
 * Server configuration: TCP port, which keyspace store to use, the {@code maxmemory} bound, and
 * the eviction policy applied when that bound is exceeded.
 *
 * <p>{@code useDict=false} runs the temporary {@link dev.devgurav.mnemo.store.HashMapStore}
 * so the server works end-to-end today. Set {@code MNEMO_USE_DICT=true} (or {@code --use-dict true})
 * to switch to your own {@link dev.devgurav.mnemo.store.Dict} once its spec tests are green.
 *
 * <p>{@code maxmemory} is the logical-byte budget for the evictable (string) keyspace; {@code 0}
 * means unlimited (no eviction). Eviction is only armed when {@code maxmemory > 0} <em>and</em>
 * the store is a {@code Dict} (the sampler reads {@code Dict} bucket arrays).
 */
public record Config(int port, boolean useDict, long maxmemory, EvictionPolicy evictionPolicy) {

    /** Back-compat constructor for callers that predate {@code evictionPolicy}. */
    public Config(int port, boolean useDict, long maxmemory) {
        this(port, useDict, maxmemory, EvictionPolicy.ALLKEYS_LRU);
    }

    /** Back-compat constructor for callers that predate {@code maxmemory} (defaults to unlimited). */
    public Config(int port, boolean useDict) {
        this(port, useDict, 0, EvictionPolicy.NOEVICTION);
    }

    public static Config defaults() {
        return new Config(6379, false, 0, EvictionPolicy.NOEVICTION);
    }

    public static Config fromArgs(String[] args) {
        int port = envInt("MNEMO_PORT", 6379);
        boolean useDict = Boolean.parseBoolean(
                System.getenv().getOrDefault("MNEMO_USE_DICT", "false"));
        long maxmemory = envLong("MNEMO_MAXMEMORY", 0);
        EvictionPolicy evictionPolicy = EvictionPolicy.fromString(
                System.getenv().getOrDefault("MNEMO_EVICTION_POLICY", "allkeys-lru"));
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--port"             -> port = Integer.parseInt(args[i + 1]);
                case "--use-dict"         -> useDict = Boolean.parseBoolean(args[i + 1]);
                case "--maxmemory"        -> maxmemory = Long.parseLong(args[i + 1]);
                case "--eviction-policy"  -> evictionPolicy = EvictionPolicy.fromString(args[i + 1]);
                default -> { /* ignore */ }
            }
        }
        return new Config(port, useDict, maxmemory, evictionPolicy);
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
