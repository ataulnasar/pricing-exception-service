package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** INV-05 and design.md "Request statuses": the full status x command table. */
class StatusCommandMatrixTest {

    enum Command {
        APPROVE(RequestStatus.APPROVED),
        DECLINE(RequestStatus.DECLINED),
        WITHDRAW(RequestStatus.WITHDRAWN),
        SUPERSEDE(RequestStatus.SUPERSEDED);

        final RequestStatus target;

        Command(RequestStatus target) {
            this.target = target;
        }

        /** Runs the command with a fully authorised actor (so only the status can reject it). */
        void run(PricingExceptionRequest r) {
            switch (this) {
                case APPROVE -> r.approve(JOHAN, null, CLOCK);
                case DECLINE -> r.decline(JOHAN, null, CLOCK);
                case WITHDRAW -> r.withdraw(ANNA, CLOCK);
                case SUPERSEDE -> r.markSuperseded(ANNA, CLOCK);
            }
        }
    }

    static Stream<Arguments> allStatusAndCommand() {
        return Stream.of(RequestStatus.values()).flatMap(s ->
                Stream.of(Command.values()).map(c -> Arguments.of(s, c)));
    }

    static Stream<Arguments> nonPendingStatusAndCommand() {
        return allStatusAndCommand().filter(a -> a.get()[0] != RequestStatus.PENDING);
    }

    @ParameterizedTest(name = "INV05_transition_table: {0} x {1}")
    @MethodSource("allStatusAndCommand")
    void INV05_transition_table(RequestStatus from, Command cmd) {
        PricingExceptionRequest r = inStatus(from);
        int eventsBefore = r.events().size();

        if (from == RequestStatus.PENDING) {
            cmd.run(r);
            assertThat(r.status()).isEqualTo(cmd.target);
            assertThat(r.events()).hasSize(eventsBefore + 1);       // INV-02: exactly one event
            assertThat(r.events().get(eventsBefore).fromStatus()).isEqualTo(from);
            assertThat(r.events().get(eventsBefore).toStatus()).isEqualTo(cmd.target);
        } else {
            String activeBefore = r.activeApplicationId();
            assertThatThrownBy(() -> cmd.run(r))
                    .isInstanceOf(InvalidTransitionException.class)
                    .extracting("code").isEqualTo("INVALID_TRANSITION");
            assertThat(r.status()).isEqualTo(from);                 // unchanged
            assertThat(r.events()).hasSize(eventsBefore);           // no event on failure
            assertThat(r.activeApplicationId()).isEqualTo(activeBefore);
        }
    }

    @ParameterizedTest(name = "INV05_final_status_stays_final: {0} x {1}")
    @MethodSource("nonPendingStatusAndCommand")
    void INV05_final_status_stays_final(RequestStatus from, Command cmd) {
        PricingExceptionRequest r = inStatus(from);
        assertThatThrownBy(() -> cmd.run(r)).isInstanceOf(InvalidTransitionException.class);
        // and again: still final after the failed attempt
        assertThatThrownBy(() -> cmd.run(r)).isInstanceOf(InvalidTransitionException.class);
        assertThat(r.status()).isEqualTo(from);
    }

    @ParameterizedTest(name = "INV05_approved_request_cannot_be_decided_again_by_other_reviewer: {0}")
    @EnumSource(value = RequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void INV05_second_decision_is_rejected(RequestStatus from) {
        PricingExceptionRequest r = inStatus(from);
        assertThatThrownBy(() -> r.approve(SARA, null, CLOCK))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> r.decline(JOHAN, null, CLOCK))
                .isInstanceOf(InvalidTransitionException.class);
    }

    static final Map<RequestStatus, Boolean> ACTIVE = Map.of(
            RequestStatus.PENDING, true, RequestStatus.APPROVED, true,
            RequestStatus.DECLINED, false, RequestStatus.WITHDRAWN, false,
            RequestStatus.SUPERSEDED, false);

    @ParameterizedTest(name = "activeApplicationId_matches_status: {0}")
    @EnumSource(RequestStatus.class)
    void activeApplicationId_is_set_only_while_pending_or_approved(RequestStatus status) {
        PricingExceptionRequest r = inStatus(status);
        if (ACTIVE.get(status)) {
            assertThat(r.activeApplicationId()).isEqualTo(APPLICATION_ID);
        } else {
            assertThat(r.activeApplicationId()).isNull();
        }
    }
}
