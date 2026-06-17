package dev.devgurav.mnemo.store;

import dev.devgurav.mnemo.store.entry.DictEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link Dict#forEach} — the dual-table iterator that {@code HGETALL} relies on.
 *
 * <p>Lives in the {@code store} package so it can read the package-private rehash state
 * ({@link Dict#isRehashing()}, {@link Dict#table(int)}) and assert the iterator is correct
 * <em>while a rehash is genuinely in flight</em> with entries split across both tables. Plumbing
 * (not {@code @Tag("spec")}), so it runs under {@code ./gradlew test}.
 */
class DictIteratorTest {

    private static Map<String, String> collect(Dict dict) {
        Map<String, String> out = new HashMap<>();
        dict.forEach((k, v) -> out.put(
                new String(k, StandardCharsets.UTF_8),
                new String(v, StandardCharsets.UTF_8)));
        return out;
    }

    private static byte[] val(int i) { return ("v" + i).getBytes(StandardCharsets.UTF_8); }

    private static boolean hasEntries(DictEntry[] table) {
        if (table == null) return false;
        for (DictEntry e : table) if (e != null) return true;
        return false;
    }

    @Test
    void emptyDictYieldsNothing() {
        assertThat(collect(new Dict())).isEmpty();
    }

    @Test
    void yieldsEveryEntryWhenNotRehashing() {
        Dict dict = new Dict();
        for (int i = 0; i < 5; i++) dict.put("k" + i, val(i));
        assertThat(dict.isRehashing()).isFalse(); // 5 < 0.75 × 16, no resize armed

        Map<String, String> all = collect(dict);
        assertThat(all).hasSize(5);
        for (int i = 0; i < 5; i++) assertThat(all).containsEntry("k" + i, "v" + i);
    }

    @Test
    void yieldsEveryEntryDuringActiveMigration() {
        Dict dict = new Dict();
        // Grow until a rehash is genuinely mid-flight AND both tables hold entries — the exact case
        // the iterator must merge (ht[0] = not-yet-migrated, ht[1] = migrated + new keys).
        int n = 0;
        while (n < 1000 && !(dict.isRehashing()
                && hasEntries(dict.table(0)) && hasEntries(dict.table(1)))) {
            dict.put("k" + n, val(n));
            n++;
        }
        assertThat(dict.isRehashing()).isTrue();
        assertThat(hasEntries(dict.table(0))).isTrue();
        assertThat(hasEntries(dict.table(1))).isTrue();

        Map<String, String> all = collect(dict);
        assertThat(all).hasSize(n);
        for (int i = 0; i < n; i++) assertThat(all).containsEntry("k" + i, "v" + i);
    }

    @Test
    void yieldsEveryEntryAfterRehashCompletes() {
        Dict dict = new Dict();
        for (int i = 0; i < 13; i++) dict.put("k" + i, val(i)); // arm the rehash
        int filler = 0;
        while (dict.isRehashing()) dict.put("filler" + (filler++), val(0)); // drive it to completion
        assertThat(dict.isRehashing()).isFalse();

        Map<String, String> all = collect(dict);
        for (int i = 0; i < 13; i++) assertThat(all).containsEntry("k" + i, "v" + i);
    }
}
