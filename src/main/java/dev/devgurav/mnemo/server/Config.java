package dev.devgurav.mnemo.server;

import dev.devgurav.mnemo.store.evict.EvictionPolicy;

/**
 * Server configuration: TCP port, which keyspace store to use, the {@code maxmemory} bound,
 * the eviction policy, and an optional AOF persistence path.
 *
 * <p>{@code useDict=false} runs the temporary {@link dev.devgurav.mnemo.store.HashMapStore}.
 * Set {@code MNEMO_USE_DICT=true} or {@code --use-dict true} to use {@link dev.devgurav.mnemo.store.Dict}.
 *
 * <p>{@code maxmemory=0} disables eviction. {@code aofPath=null} disables AOF persistence.
 */
public record Config(int port, boolean useDict, long maxmemory, EvictionPolicy evictionPolicy,
                     String aofPath) {

    /** Back-compat: no AOF. */
    public Config(int port, boolean useDict, long maxmemory, EvictionPolicy evictionPolicy) {
        this(port, useDict, maxmemory, evictionPolicy, null);
    }

    /** Back-compat: no AOF, default LRU policy. */
    public Config(int port, boolean useDict, long maxmemory) {
        this(port, useDict, maxmemory, EvictionPolicy.ALLKEYS_LRU, null);
    }

    /** Back-compat: unlimited memory, no eviction, no AOF. */
    public Config(int port, boolean useDict) {
        this(port, useDict, 0, EvictionPolicy.NOEVICTION, null);
    }

    public static Config defaults() {
        return new Config(6379, false, 0, EvictionPolicy.NOEVICTION, null);
    }

    public static Config fromArgs(String[] args) {
        int port = envInt("MNEMO_PORT", 6379);
        boolean useDict = Boolean.parseBoolean(
                System.getenv().getOrDefault("MNEMO_USE_DICT", "false"));
        long maxmemory = envLong("MNEMO_MAXMEMORY", 0);
        EvictionPolicy evictionPolicy = EvictionPolicy.fromString(
                System.getenv().getOrDefault("MNEMO_EVICTION_POLICY", "allkeys-lru"));
        String aofPath = System.getenv("MNEMO_AOF_PATH");
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--port"             -> port = Integer.parseInt(args[i + 1]);
                case "--use-dict"         -> useDict = Boolean.parseBoolean(args[i + 1]);
                case "--maxmemory"        -> maxmemory = Long.parseLong(args[i + 1]);
                case "--eviction-policy"  -> evictionPolicy = EvictionPolicy.fromString(args[i + 1]);
                case "--aof-path"         -> aofPath = args[i + 1];
                default -> { /* ignore */ }
            }
        }
        return new Config(port, useDict, maxmemory, evictionPolicy, aofPath);
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
