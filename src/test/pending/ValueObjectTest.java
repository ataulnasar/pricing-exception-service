package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectTest {

    private static Arguments row(String name, String code, ThrowingCallable call) {
        return Arguments.of(name, code, call);
    }

    // --- DiscountBps: whole number, 1..max (design.md "Validation"); Reason: not blank, max 500 chars ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 99, 100})
    void discount_accepts_values_from_1_to_max(int v) {
        assertThat(DiscountBps.of(v, 100).value()).isEqualTo(v);
    }

    @Test
    void discount_max_is_configurable_and_the_canonical_constructor_accepts_one() {
        assertThat(DiscountBps.of(150, 200).value()).isEqualTo(150);
        assertThat(new DiscountBps(1).value()).isEqualTo(1);
    }

    @Test
    void reason_accepts_1_and_500_characters_and_keeps_markup_as_plain_text() {
        assertThat(new Reason("x").value()).isEqualTo("x");
        assertThat(new Reason("x".repeat(500)).value()).hasSize(500);
        assertThat(new Reason("a" + " ".repeat(499)).value()).hasSize(500);
        assertThat(new Reason("<script>alert(1)</script>").value()).isEqualTo("<script>alert(1)</script>");
    }

    static Stream<Arguments> rejectedValues() {
        Stream<Arguments> discounts = Stream.of(Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE)
                .map(v -> row("discount " + v + " with max 100", "INVALID_DISCOUNT", () -> DiscountBps.of(v, 100)));
        Stream<Arguments> blanks = Stream.of("", " ", "   ", "\t", "\n", " \t\n ", "　  ", "  ")
                .map(b -> row("blank reason " + b.codePoints().boxed().toList(), "INVALID_REASON", () -> new Reason(b)));
        return Stream.of(discounts, blanks, Stream.of(
                row("discount 201 with max 200", "INVALID_DISCOUNT", () -> DiscountBps.of(201, 200)),
                row("canonical constructor rejects 0", "INVALID_DISCOUNT", () -> new DiscountBps(0)),
                row("canonical constructor rejects -5", "INVALID_DISCOUNT", () -> new DiscountBps(-5)),
                row("max below one accepts nothing", "INVALID_DISCOUNT", () -> DiscountBps.of(1, 0)),
                row("reason of 501 characters", "INVALID_REASON", () -> new Reason("x".repeat(501))),
                row("reason of text plus 500 spaces (501 characters)", "INVALID_REASON", () -> new Reason("a" + " ".repeat(500))),
                row("reason null", "INVALID_REASON", () -> new Reason(null)))).flatMap(s -> s);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedValues")
    void value_objects_reject_invalid_input_with_a_stable_code(String display, String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void discount_has_value_equality() {
        assertThat(DiscountBps.of(40, 100)).isEqualTo(new DiscountBps(40)).hasSameHashCodeAs(new DiscountBps(40));
    }

    // --- Actor / Role / RequestStatus ---

    @Test
    void role_has_the_three_roles_from_design_and_status_the_five_statuses() {
        assertThat(Role.values()).containsExactlyInAnyOrder(Role.RM, Role.REVIEWER, Role.MORTGAGE_PROCESS);
        assertThat(RequestStatus.values()).containsExactlyInAnyOrder(
                RequestStatus.PENDING, RequestStatus.APPROVED, RequestStatus.DECLINED,
                RequestStatus.WITHDRAWN, RequestStatus.SUPERSEDED);
    }

    @Test
    void actor_factories_set_id_role_and_limit() {
        assertThat(Actor.rm("rm-anna").id()).isEqualTo("rm-anna");
        assertThat(Actor.rm("rm-anna").role()).isEqualTo(Role.RM);
        Actor rev = Actor.reviewer("rev-sara", 50);
        assertThat(rev.role()).isEqualTo(Role.REVIEWER);
        assertThat(rev.approvalLimitBps()).isEqualTo(50);
        assertThat(Actor.mortgageProcess("mortgage-system").role()).isEqualTo(Role.MORTGAGE_PROCESS);
        assertThat(Actor.rm("x")).isNotEqualTo(Actor.mortgageProcess("x")); // same id, different role
    }

    static Stream<Arguments> invalidActors() {
        return Stream.of(
                row("blank id", null, () -> Actor.rm(" ")),
                row("whitespace-only id", null, () -> Actor.rm(" \t")),
                row("negative reviewer limit", null, () -> Actor.reviewer("rev-x", -1)));
    }

    @ParameterizedTest(name = "actor rejects {0}")
    @MethodSource("invalidActors")
    void actor_rejects_invalid_arguments(String display, String unused, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void status_active_is_only_pending_or_approved_and_final_is_everything_but_pending() {
        for (RequestStatus s : RequestStatus.values()) {
            assertThat(s.isActive()).as(s.name()).isEqualTo(s == RequestStatus.PENDING || s == RequestStatus.APPROVED);
            // APPROVED is final for status changes (design: "All other statuses are final").
            assertThat(s.isFinal()).as(s.name()).isEqualTo(s != RequestStatus.PENDING);
        }
    }

    @Test
    void exceptions_carry_a_stable_code_and_are_runtime_exceptions() {
        assertThatThrownBy(() -> inStatus(RequestStatus.DECLINED).withdraw(ANNA, CLOCK))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Reason(" ")).isInstanceOf(RuntimeException.class);
    }
}
