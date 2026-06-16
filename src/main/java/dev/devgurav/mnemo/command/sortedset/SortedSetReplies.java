package dev.devgurav.mnemo.command.sortedset;

/**
 * Shared constants and score formatting for the sorted-set commands.
 */
final class SortedSetReplies {

    private SortedSetReplies() {}

    static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    /**
     * Parse a ZADD score. Accepts the same special tokens Redis does ({@code inf}, {@code +inf},
     * {@code -inf}) plus any value {@link Double#parseDouble} understands. {@code NaN} is rejected.
     *
     * @throws NumberFormatException if the token is not a valid score
     */
    static double parseScore(String token) {
        String t = token.trim().toLowerCase(java.util.Locale.ROOT);
        double value = switch (t) {
            case "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY;
            case "-inf", "-infinity"                     -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(token);
        };
        if (Double.isNaN(value)) {
            throw new NumberFormatException("nan");
        }
        return value;
    }

    /**
     * Format a score the way Redis does on the wire: integral values print without a decimal point
     * ({@code 1.0 → "1"}), infinities print as {@code inf} / {@code -inf}.
     */
    static String formatScore(double value) {
        if (value == Double.POSITIVE_INFINITY) return "inf";
        if (value == Double.NEGATIVE_INFINITY) return "-inf";
        if (value == Math.rint(value)) return Long.toString((long) value);
        return Double.toString(value);
    }
}
