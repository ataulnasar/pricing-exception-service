package se.ataulnasar.pricingexception.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.ataulnasar.pricingexception.exception.ForbiddenActionException;
import se.ataulnasar.pricingexception.exception.InvalidTransitionException;
import se.ataulnasar.pricingexception.exception.ValidationException;

/** Actor, Role, RequestStatus and the business exceptions. Plain JUnit. */
class ModelTest {

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
                Arguments.of("blank id", (ThrowingCallable) () -> Actor.rm(" ")),
                Arguments.of("whitespace-only id", (ThrowingCallable) () -> Actor.rm(" \t")),
                Arguments.of("null id", (ThrowingCallable) () -> Actor.rm(null)),
                Arguments.of("null role", (ThrowingCallable) () -> new Actor("x", null, 0)),
                Arguments.of("negative reviewer limit", (ThrowingCallable) () -> Actor.reviewer("rev-x", -1)));
    }

    @ParameterizedTest(name = "actor rejects {0}")
    @MethodSource("invalidActors")
    void actor_rejects_invalid_arguments(String display, ThrowingCallable call) {
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
        InvalidTransitionException invalid = new InvalidTransitionException(RequestStatus.DECLINED, "withdraw");
        assertThat(invalid.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(invalid).isInstanceOf(RuntimeException.class).hasMessage("cannot withdraw a request in status DECLINED");

        assertThat(new ValidationException(ValidationException.INVALID_REASON, "m").code()).isEqualTo("INVALID_REASON");
        assertThat(ValidationException.INVALID_DISCOUNT).isEqualTo("INVALID_DISCOUNT");
        assertThat(ValidationException.INVALID_COMMENT).isEqualTo("INVALID_COMMENT");
        assertThat(new ForbiddenActionException(ForbiddenActionException.NOT_OWNER, "m")).isInstanceOf(RuntimeException.class);
        assertThat(ForbiddenActionException.WRONG_ROLE).isEqualTo("WRONG_ROLE");
        assertThat(ForbiddenActionException.SELF_REVIEW).isEqualTo("SELF_REVIEW");
        assertThat(ForbiddenActionException.OVER_APPROVAL_LIMIT).isEqualTo("OVER_APPROVAL_LIMIT");
        assertThat(ForbiddenActionException.NOT_OWNER).isEqualTo("NOT_OWNER");
    }
}
