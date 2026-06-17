package dev.devgurav.mnemo.command.keyspace;

/**
 * Redis-compatible glob matching for {@code KEYS pattern}.
 *
 * <p>Supported metacharacters:
 * <ul>
 *   <li>{@code *}  — zero or more characters</li>
 *   <li>{@code ?}  — exactly one character</li>
 *   <li>{@code [abc]}, {@code [a-z]} — character class (ranges supported)</li>
 *   <li>{@code [^abc]}, {@code [!abc]} — negated character class</li>
 *   <li>{@code \x} — escape: treats {@code x} literally</li>
 * </ul>
 * Matching is case-sensitive (Redis default).
 */
final class GlobPattern {

    private GlobPattern() {}

    static boolean matches(String text, String pattern) {
        return match(text, 0, pattern, 0);
    }

    private static boolean match(String t, int ti, String p, int pi) {
        while (pi < p.length()) {
            char pc = p.charAt(pi);
            switch (pc) {
                case '*' -> {
                    // consume consecutive '*'s
                    while (pi < p.length() && p.charAt(pi) == '*') pi++;
                    if (pi == p.length()) return true; // trailing '*' matches anything
                    for (int i = ti; i <= t.length(); i++) {
                        if (match(t, i, p, pi)) return true;
                    }
                    return false;
                }
                case '?' -> {
                    if (ti >= t.length()) return false;
                    ti++; pi++;
                }
                case '[' -> {
                    if (ti >= t.length()) return false;
                    pi++; // skip '['
                    boolean negate = pi < p.length()
                            && (p.charAt(pi) == '^' || p.charAt(pi) == '!');
                    if (negate) pi++;
                    boolean found = false;
                    boolean first = true;
                    while (pi < p.length() && (first || p.charAt(pi) != ']')) {
                        first = false;
                        // range [a-z] — only when '-' is not the last char before ']'
                        if (pi + 2 < p.length()
                                && p.charAt(pi + 1) == '-'
                                && p.charAt(pi + 2) != ']') {
                            char lo = p.charAt(pi), hi = p.charAt(pi + 2);
                            if (t.charAt(ti) >= lo && t.charAt(ti) <= hi) found = true;
                            pi += 3;
                        } else {
                            if (t.charAt(ti) == p.charAt(pi)) found = true;
                            pi++;
                        }
                    }
                    if (pi < p.length() && p.charAt(pi) == ']') pi++; // consume ']'
                    if (found == negate) return false; // negate XOR found
                    ti++;
                }
                case '\\' -> {
                    pi++; // skip backslash
                    if (pi >= p.length()) return false; // trailing backslash
                    if (ti >= t.length() || t.charAt(ti) != p.charAt(pi)) return false;
                    ti++; pi++;
                }
                default -> {
                    if (ti >= t.length() || t.charAt(ti) != pc) return false;
                    ti++; pi++;
                }
            }
        }
        return ti == t.length();
    }
}
