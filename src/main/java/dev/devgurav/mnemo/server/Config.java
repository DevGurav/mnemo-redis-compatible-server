package dev.devgurav.mnemo.server;

/**
 * Server configuration. Week 1: TCP port + which keyspace store to use.
 *
 * <p>{@code useDict=false} runs the temporary {@link dev.devgurav.mnemo.store.HashMapStore}
 * so the server works end-to-end today. Set {@code MNEMO_USE_DICT=true} (or {@code --use-dict true})
 * to switch to your own {@link dev.devgurav.mnemo.store.Dict} once its spec tests are green.
 */
public record Config(int port, boolean useDict) {

    public static Config defaults() {
        return new Config(6379, false);
    }

    public static Config fromArgs(String[] args) {
        int port = envInt("MNEMO_PORT", 6379);
        boolean useDict = Boolean.parseBoolean(
                System.getenv().getOrDefault("MNEMO_USE_DICT", "false"));
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--use-dict" -> useDict = Boolean.parseBoolean(args[i + 1]);
                default -> { /* ignore */ }
            }
        }
        return new Config(port, useDict);
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        return v == null ? def : Integer.parseInt(v);
    }
}
