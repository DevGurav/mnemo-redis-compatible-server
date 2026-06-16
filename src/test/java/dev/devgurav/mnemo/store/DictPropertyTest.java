package dev.devgurav.mnemo.store;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based SPEC (red) for {@link Dict}: under any random sequence of put/get/remove with a
 * small key space (forcing collisions and overwrites), your Dict must behave exactly like a
 * reference {@link HashMap}. Tagged {@code "spec"}; run via {@code ./gradlew specTest}.
 */
@Tag("spec")
class DictPropertyTest {

    enum Kind { PUT, REMOVE, GET }

    record Op(Kind kind, String key, String value) {}

    @Property(tries = 300)
    void behavesLikeReferenceHashMap(@ForAll("operations") List<Op> ops) {
        Dict dict = new Dict();
        Map<String, String> reference = new HashMap<>();

        for (Op op : ops) {
            switch (op.kind()) {
                case PUT -> {
                    dict.put(op.key(), op.value().getBytes(StandardCharsets.UTF_8));
                    reference.put(op.key(), op.value());
                }
                case REMOVE -> {
                    boolean expected = reference.remove(op.key()) != null;
                    assertThat(dict.remove(op.key())).isEqualTo(expected);
                }
                case GET -> {
                    String expected = reference.get(op.key());
                    byte[] actual = dict.get(op.key());
                    if (expected == null) {
                        assertThat(actual).isNull();
                    } else {
                        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(expected);
                    }
                }
            }
            assertThat(dict.size()).isEqualTo(reference.size());
        }
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        // Tiny key space ('a'..'e', length 1..3) deliberately forces collisions and overwrites.
        Arbitrary<String> keys = Arbitraries.strings().withCharRange('a', 'e').ofMinLength(1).ofMaxLength(3);
        Arbitrary<String> values = Arbitraries.strings().alpha().ofMaxLength(8);

        Arbitrary<Op> put = Combinators.combine(keys, values).as((k, v) -> new Op(Kind.PUT, k, v));
        Arbitrary<Op> remove = keys.map(k -> new Op(Kind.REMOVE, k, ""));
        Arbitrary<Op> get = keys.map(k -> new Op(Kind.GET, k, ""));

        // Weight puts so the table actually fills up and resizes.
        Arbitrary<Op> anyOp = Arbitraries.oneOf(put, put, remove, get);
        return anyOp.list().ofMaxSize(200);
    }
}
