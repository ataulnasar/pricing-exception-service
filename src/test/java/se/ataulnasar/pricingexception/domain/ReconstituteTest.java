package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Rebuilding an aggregate from stored data (used by the persistence mapper). Plain JUnit. */
class ReconstituteTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String APP = "MA-1001";
    private static final Instant T0 = Fixtures.NOW;
    private static final Instant T1 = Fixtures.LATER;
    private static final DiscountBps DISCOUNT = new DiscountBps(40);
    private static final Reason REASON = new Reason("Long-standing customer");

    private static RequestEvent submitted() {
        return new RequestEvent(ID, RequestEvent.Type.SUBMITTED, "rm-anna", T0, null, RequestStatus.PENDING, null);
    }

    private static void assertInvalidTransition(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
    }

    private static RequestEvent.Type typeFor(RequestStatus s) {
        return RequestEvent.Type.valueOf(s.name());
    }

    private static List<RequestEvent> eventsFor(RequestStatus status) {
        List<RequestEvent> events = new ArrayList<>();
        events.add(submitted());
        if (status != RequestStatus.PENDING) {
            String actor = (status == RequestStatus.APPROVED || status == RequestStatus.DECLINED) ? "rev-johan" : "rm-anna";
            events.add(new RequestEvent(ID, typeFor(status), actor, T1, RequestStatus.PENDING, status, null));
        }
        return events;
    }

    private static PricingExceptionRequest rebuild(RequestStatus status) {
        boolean decided = status == RequestStatus.APPROVED || status == RequestStatus.DECLINED;
        return PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0, null, status,
                decided ? "rev-johan" : null, decided ? T1 : null, decided ? "ok" : null,
                status == RequestStatus.APPROVED ? DISCOUNT : null, eventsFor(status));
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void INV02_reconstitute_returns_exactly_the_given_events_and_status(RequestStatus status) {
        PricingExceptionRequest r = rebuild(status);

        assertThat(r.status()).isEqualTo(status);
        assertThat(r.events()).isEqualTo(eventsFor(status));
        assertThat(r.id()).isEqualTo(ID);
        assertThat(r.applicationId()).isEqualTo(APP);
        assertThat(r.requestedBy()).isEqualTo("rm-anna");
        assertThat(r.discount()).isEqualTo(DISCOUNT);
        assertThat(r.reason()).isEqualTo(REASON);
        assertThat(r.createdAt()).isEqualTo(T0);
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void INV04_reconstituted_activeApplicationId_follows_status(RequestStatus status) {
        PricingExceptionRequest r = rebuild(status);

        assertThat(r.activeApplicationId()).isEqualTo(status.isActive() ? APP : null);
    }

    @Test
    void INV01_reconstituted_approved_keeps_approved_discount_and_decision_fields() {
        PricingExceptionRequest r = rebuild(RequestStatus.APPROVED);

        assertThat(r.approvedDiscount()).isEqualTo(DISCOUNT);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decidedAt()).isEqualTo(T1);
        assertThat(r.decisionComment()).isEqualTo("ok");
    }

    @Test
    void INV01_reconstitute_keeps_supersedesId() {
        UUID old = UUID.randomUUID();
        PricingExceptionRequest r = PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                old, RequestStatus.PENDING, null, null, null, null, eventsFor(RequestStatus.PENDING));

        assertThat(r.supersedesId()).isEqualTo(old);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void INV05_reconstituted_final_request_rejects_every_command_and_events_stay(RequestStatus status) {
        PricingExceptionRequest r = rebuild(status);
        List<RequestEvent> before = r.events();

        assertInvalidTransition(() -> r.approve(Fixtures.JOHAN, null, Fixtures.CLOCK));
        assertInvalidTransition(() -> r.decline(Fixtures.JOHAN, null, Fixtures.CLOCK));
        assertInvalidTransition(() -> r.withdraw(Fixtures.ANNA, Fixtures.CLOCK));
        assertInvalidTransition(() -> r.markSuperseded(Fixtures.ANNA, Fixtures.CLOCK));

        assertThat(r.events()).isEqualTo(before);
        assertThat(r.status()).isEqualTo(status);
    }

    @Test
    void INV05_reconstituted_approved_rejects_approve_with_INVALID_TRANSITION() {
        PricingExceptionRequest r = rebuild(RequestStatus.APPROVED);

        assertThatThrownBy(() -> r.approve(Fixtures.JOHAN, null, Fixtures.CLOCK))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
        assertThat(r.events()).hasSize(2);
    }

    @Test
    void INV02_reconstituted_pending_can_be_approved_and_gains_exactly_one_event() {
        PricingExceptionRequest r = rebuild(RequestStatus.PENDING);

        r.approve(Fixtures.JOHAN, "fine", Fixtures.LATER_CLOCK);

        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.events()).hasSize(2);
        assertThat(r.events().get(0)).isEqualTo(submitted());
        assertThat(r.events().get(1).type()).isEqualTo(RequestEvent.Type.APPROVED);
        assertThat(r.events().get(1).fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events().get(1).toStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.approvedDiscount()).isEqualTo(DISCOUNT);
    }

    @Test
    void INV03_rules_still_apply_after_reconstitution_requester_cannot_approve() {
        PricingExceptionRequest r = PricingExceptionRequest.reconstitute(ID, APP, "rev-johan", DISCOUNT, REASON, T0,
                null, RequestStatus.PENDING, null, null, null, null,
                List.of(new RequestEvent(ID, RequestEvent.Type.SUBMITTED, "rev-johan", T0, null,
                        RequestStatus.PENDING, null)));

        assertThatThrownBy(() -> r.approve(Fixtures.JOHAN, null, Fixtures.CLOCK))
                .isInstanceOf(DomainException.class);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV02_reconstituted_events_are_an_immutable_snapshot() {
        PricingExceptionRequest r = rebuild(RequestStatus.PENDING);

        assertThatThrownBy(() -> r.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void INV02_changing_the_input_list_afterwards_does_not_change_the_aggregate() {
        List<RequestEvent> input = new ArrayList<>(eventsFor(RequestStatus.PENDING));
        PricingExceptionRequest r = PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, RequestStatus.PENDING, null, null, null, null, input);

        input.clear();

        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV02_reconstitute_rejects_empty_events() {
        assertThatThrownBy(() -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, RequestStatus.PENDING, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV02_reconstitute_rejects_first_event_not_SUBMITTED() {
        List<RequestEvent> bad = List.of(new RequestEvent(ID, RequestEvent.Type.APPROVED, "rev-johan", T0,
                RequestStatus.PENDING, RequestStatus.APPROVED, null));

        assertThatThrownBy(() -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, RequestStatus.APPROVED, "rev-johan", T0, null, DISCOUNT, bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV01_reconstitute_rejects_APPROVED_without_approvedDiscount() {
        assertThatThrownBy(() -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, RequestStatus.APPROVED, "rev-johan", T1, null, null, eventsFor(RequestStatus.APPROVED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV01_reconstitute_rejects_APPROVED_with_approvedDiscount_different_from_discount() {
        assertThatThrownBy(() -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, RequestStatus.APPROVED, "rev-johan", T1, null, new DiscountBps(41),
                eventsFor(RequestStatus.APPROVED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void INV01_reconstitute_rejects_approvedDiscount_when_not_APPROVED(RequestStatus status) {
        assertThatThrownBy(() -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0,
                null, status, null, null, null, DISCOUNT, eventsFor(status)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
