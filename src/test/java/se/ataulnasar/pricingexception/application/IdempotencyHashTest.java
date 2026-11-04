package se.ataulnasar.pricingexception.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The Idempotency-Key hash: SHA-256 (lower-case hex) over the UTF-8 canonical form of the command. */
class IdempotencyHashTest {

    private static final String CANONICAL = "create\n7:MA-1003\n2:40\n22:Long-standing customer";

    // If this changes, stored keys within the TTL would be rejected as reused.
    private static final String PINNED_HASH = "203308b1a7bcb24328daa093db03b1428bb8248236abd9c20390e31c5a79e1d8";

    private static CreateRequestCommand base() {
        return new CreateRequestCommand("MA-1003", 40, "Long-standing customer");
    }

    @Test
    void hash_canonical_form_is_operation_then_length_prefixed_fields() {
        assertThat(base().canonicalForm()).isEqualTo(CANONICAL);
    }

    @Test
    void hash_is_pinned_to_a_literal_so_a_serialization_change_is_noticed() throws Exception {
        String computed = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CANONICAL.getBytes(StandardCharsets.UTF_8)));

        assertThat(computed).isEqualTo(PINNED_HASH);
        assertThat(IdempotencyService.hash(base())).isEqualTo(PINNED_HASH);
    }

    @Test
    void hash_differs_when_any_field_changes() {
        String h = IdempotencyService.hash(base());

        assertThat(IdempotencyService.hash(new CreateRequestCommand("MA-1004", 40, "Long-standing customer"))).isNotEqualTo(h);
        assertThat(IdempotencyService.hash(new CreateRequestCommand("MA-1003", 41, "Long-standing customer"))).isNotEqualTo(h);
        assertThat(IdempotencyService.hash(new CreateRequestCommand("MA-1003", 40, "Long-standing customer."))).isNotEqualTo(h);
    }

    @Test
    void hash_does_not_collide_when_fields_shift() {
        assertThat(IdempotencyService.hash(new CreateRequestCommand("AB", 1, "C")))
                .isNotEqualTo(IdempotencyService.hash(new CreateRequestCommand("A", 1, "BC")));
    }
}
