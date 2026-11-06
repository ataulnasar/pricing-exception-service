package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectTest {

    // --- DiscountBps: whole number, 1..max (design.md "Validation") ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 99, 100})
    void discount_accepts_values_from_1_to_max(int v) {
        assertThat(DiscountBps.of(v, 100).value()).isEqualTo(v);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void discount_rejects_values_outside_1_to_max(int v) {
        assertThatThrownBy(() -> DiscountBps.of(v, 100)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
    }

    @Test
    void discount_max_is_configurable() {
        assertThat(DiscountBps.of(150, 200).value()).isEqualTo(150);
        assertThatThrownBy(() -> DiscountBps.of(201, 200)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
    }

    @Test
    void discount_canonical_constructor_rejects_zero_and_negative() {
        assertThatThrownBy(() -> new DiscountBps(0)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
        assertThatThrownBy(() -> new DiscountBps(-5)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
        assertThat(new DiscountBps(1).value()).isEqualTo(1);
    }

    @Test
    void discount_has_value_equality() {
        assertThat(new DiscountBps(25)).isEqualTo(DiscountBps.of(25, 100));
    }

    // --- Reason: not blank, max 500 chars ---

    @Test
    void reason_accepts_1_and_500_characters() {
        assertThat(new Reason("x").value()).isEqualTo("x");
        assertThat(new Reason("x".repeat(500)).value()).hasSize(500);
    }

    @Test
    void reason_rejects_501_characters() {
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> new Reason(tooLong)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    void reason_rejects_blank(String blank) {
        assertThatThrownBy(() -> new Reason(blank)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
    }

    @Test
    void reason_rejects_null() {
        assertThatThrownBy(() -> new Reason(null)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
    }

    @Test
    void reason_keeps_markup_as_plain_text() {
        String s = "<script>alert(1)</script>";
        assertThat(new Reason(s).value()).isEqualTo(s);
    }

    // --- Actor / Role ---

    @Test
    void role_has_the_three_roles_from_design() {
        assertThat(Role.values()).containsExactlyInAnyOrder(Role.RM, Role.REVIEWER, Role.MORTGAGE_PROCESS);
    }

    @Test
    void actor_factories_set_id_role_and_limit() {
        Actor rm = Actor.rm("rm-anna");
        assertThat(rm.id()).isEqualTo("rm-anna");
        assertThat(rm.role()).isEqualTo(Role.RM);

        Actor rev = Actor.reviewer("rev-sara", 50);
        assertThat(rev.role()).isEqualTo(Role.REVIEWER);
        assertThat(rev.approvalLimitBps()).isEqualTo(50);

        assertThat(Actor.mortgageProcess("mortgage-system").role()).isEqualTo(Role.MORTGAGE_PROCESS);
    }

    @Test
    void actor_rejects_blank_id() {
        assertThatThrownBy(() -> Actor.rm(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviewer_rejects_negative_limit() {
        assertThatThrownBy(() -> Actor.reviewer("rev-x", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- RequestStatus ---

    @Test
    void status_active_is_only_pending_or_approved() {
        for (RequestStatus s : RequestStatus.values()) {
            boolean expected = s == RequestStatus.PENDING || s == RequestStatus.APPROVED;
            assertThat(s.isActive()).as(s.name()).isEqualTo(expected);
        }
    }

    @Test
    void status_final_is_everything_but_pending() {
        for (RequestStatus s : RequestStatus.values()) {
            // APPROVED is final for status changes (design: "All other statuses are final").
            assertThat(s.isFinal()).as(s.name()).isEqualTo(s != RequestStatus.PENDING);
        }
    }

    @Test
    void status_has_exactly_the_five_statuses() {
        assertThat(RequestStatus.values()).containsExactlyInAnyOrder(
                RequestStatus.PENDING, RequestStatus.APPROVED, RequestStatus.DECLINED,
                RequestStatus.WITHDRAWN, RequestStatus.SUPERSEDED);
    }

    // --- Exceptions carry a stable code ---

    @Test
    void invalid_transition_exception_has_stable_code() {
        InvalidTransitionException e = catchInvalid();
        assertThat(e.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void validation_exception_has_stable_code() {
        ValidationException e = org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class, () -> new Reason(" "));
        assertThat(e.code()).isEqualTo("INVALID_REASON");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    private InvalidTransitionException catchInvalid() {
        return org.junit.jupiter.api.Assertions.assertThrows(InvalidTransitionException.class,
                () -> Fixtures.inStatus(RequestStatus.DECLINED).withdraw(Fixtures.ANNA, Fixtures.CLOCK));
    }

    // --- edge cases (merged from DomainAttackTest) ---

    @Test
    void discount_of_rejects_max_plus_one_and_accepts_max_and_one() {
        assertThat(DiscountBps.of(100, 100).value()).isEqualTo(100);
        assertThat(DiscountBps.of(1, 100).value()).isEqualTo(1);
        assertThatThrownBy(() -> DiscountBps.of(101, 100)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> DiscountBps.of(0, 100)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> DiscountBps.of(Integer.MIN_VALUE, 100)).isInstanceOf(ValidationException.class);
    }

    @Test
    void discount_of_with_max_below_one_accepts_nothing() {
        assertThatThrownBy(() -> DiscountBps.of(1, 0))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
    }

    @Test
    void reason_of_500_whitespace_padded_chars_with_text_is_accepted_and_501_rejected() {
        assertThat(new Reason("a" + " ".repeat(499)).value()).hasSize(500);
        assertThatThrownBy(() -> new Reason("a" + " ".repeat(500)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
    }

    @Test
    void reason_of_only_unicode_whitespace_is_blank() {
        // ideographic space, em space, line separator
        assertThatThrownBy(() -> new Reason("　  ")).isInstanceOf(ValidationException.class);
    }

    @Test
    void reason_of_only_non_breaking_space_is_blank() {
        // JUDGEMENT: design says "not blank"; String.isBlank() treats NBSP / zero-width space as text.
        assertThatThrownBy(() -> new Reason("  ")).isInstanceOf(ValidationException.class);
    }

    // Length limits count UTF-16 units (String.length()), same as @Size and never more than the DB column.
    // A 500-code-point emoji reason is therefore rejected by design.

    @Test
    void discount_value_objects_from_both_constructors_are_equal() {
        assertThat(DiscountBps.of(40, 100)).isEqualTo(new DiscountBps(40)).hasSameHashCodeAs(new DiscountBps(40));
    }

    @Test
    void actor_same_id_different_role_is_not_equal() {
        assertThat(Actor.rm("x")).isNotEqualTo(Actor.mortgageProcess("x"));
    }

    @Test
    void actor_rejects_whitespace_only_id() {
        assertThatThrownBy(() -> Actor.rm(" \t")).isInstanceOf(IllegalArgumentException.class);
    }

}
