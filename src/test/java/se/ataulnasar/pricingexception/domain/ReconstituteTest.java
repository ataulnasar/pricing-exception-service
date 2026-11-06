package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Rebuilding an aggregate from stored data (used by the persistence mapper). Plain JUnit. */
class ReconstituteTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String APP = "MA-1001";
    private static final Instant T0 = Fixtures.NOW;
    private static final Instant T1 = Fixtures.LATER;
    private static final DiscountBps DISCOUNT = new DiscountBps(40);
    private static final Reason REASON = new Reason("Long-standing customer");

    private static RequestEvent submitted(UUID requestId) {
        return new RequestEvent(requestId, RequestEvent.Type.SUBMITTED, "rm-anna", T0, null, RequestStatus.PENDING, null);
    }

    private static RequestEvent declined(UUID requestId, Instant at) {
        return new RequestEvent(requestId, RequestEvent.Type.DECLINED, "rev-johan", at,
                RequestStatus.PENDING, RequestStatus.DECLINED, "no");
    }

    /** Fully specified reconstitute; requester is rm-anna and the decision time is T1 for every status but PENDING. */
    private static PricingExceptionRequest build(String requestedBy, RequestStatus status, UUID supersedes, String decidedBy,
                                                 DiscountBps approved, List<RequestEvent> events) {
        return PricingExceptionRequest.reconstitute(ID, APP, requestedBy, DISCOUNT, REASON, T0, supersedes, status, decidedBy,
                status == RequestStatus.PENDING ? null : T1, null, approved, events);
    }

    private static PricingExceptionRequest build(RequestStatus status, UUID supersedes, String decidedBy,
                                                 DiscountBps approved, List<RequestEvent> events) {
        return build("rm-anna", status, supersedes, decidedBy, approved, events);
    }

    private static List<RequestEvent> eventsFor(RequestStatus status) {
        List<RequestEvent> events = new ArrayList<>();
        events.add(submitted(ID));
        if (status != RequestStatus.PENDING) {
            String actor = (status == RequestStatus.APPROVED || status == RequestStatus.DECLINED) ? "rev-johan" : "rm-anna";
            events.add(new RequestEvent(ID, RequestEvent.Type.valueOf(status.name()), actor, T1, RequestStatus.PENDING, status, null));
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
        // INV-04: the active application id follows the status
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
        assertThat(build(RequestStatus.PENDING, old, null, null, eventsFor(RequestStatus.PENDING)).supersedesId()).isEqualTo(old);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void INV05_reconstituted_final_request_rejects_every_command_and_events_stay(RequestStatus status) {
        PricingExceptionRequest r = rebuild(status);
        List<RequestEvent> before = r.events();

        for (ThrowingCallable command : List.<ThrowingCallable>of(
                () -> r.approve(Fixtures.JOHAN, null, Fixtures.CLOCK), () -> r.decline(Fixtures.JOHAN, null, Fixtures.CLOCK),
                () -> r.withdraw(Fixtures.ANNA, Fixtures.CLOCK), () -> r.markSuperseded(Fixtures.ANNA, Fixtures.CLOCK))) {
            assertThatThrownBy(command).isInstanceOfSatisfying(InvalidTransitionException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
        }
        assertThat(r.events()).isEqualTo(before);
        assertThat(r.status()).isEqualTo(status);
    }

    @Test
    void INV02_reconstituted_pending_can_be_approved_and_gains_exactly_one_event() {
        PricingExceptionRequest r = rebuild(RequestStatus.PENDING);

        r.approve(Fixtures.JOHAN, "fine", Fixtures.LATER_CLOCK);

        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.events()).hasSize(2);
        assertThat(r.events().get(0)).isEqualTo(submitted(ID));
        assertThat(r.events().get(1).type()).isEqualTo(RequestEvent.Type.APPROVED);
        assertThat(r.events().get(1).fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events().get(1).toStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.approvedDiscount()).isEqualTo(DISCOUNT);
    }

    @Test
    void INV03_rules_still_apply_after_reconstitution_requester_cannot_approve() {
        PricingExceptionRequest r = build("rev-johan", RequestStatus.PENDING, null, null, null,
                List.of(new RequestEvent(ID, RequestEvent.Type.SUBMITTED, "rev-johan", T0, null, RequestStatus.PENDING, null)));

        assertThatThrownBy(() -> r.approve(Fixtures.JOHAN, null, Fixtures.CLOCK)).isInstanceOf(DomainException.class);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV02_reconstituted_events_are_an_immutable_snapshot() {
        List<RequestEvent> input = new ArrayList<>(eventsFor(RequestStatus.PENDING));
        PricingExceptionRequest r = build(RequestStatus.PENDING, null, null, null, input);

        assertThatThrownBy(() -> r.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        input.clear(); // changing the input list afterwards does not change the aggregate
        assertThat(r.events()).hasSize(1);
    }

    // ---------- inconsistent stored data is rejected (IllegalArgumentException) ----------

    private record Bad(String name, ThrowingCallable call) {
        @Override
        public String toString() {
            return name;
        }
    }

    // A backwards clock step must not make a stored request unreadable, so time order is not checked
    // (persistence orders events by insertion id; see PricingExceptionRequestRepositoryTest).
    static Stream<Bad> inconsistentData() {
        List<RequestEvent> approvedEvents = eventsFor(RequestStatus.APPROVED);
        Stream<Bad> approvedDiscountOutsideApproved = Stream.of(RequestStatus.PENDING, RequestStatus.DECLINED,
                        RequestStatus.WITHDRAWN, RequestStatus.SUPERSEDED)
                .map(s -> new Bad("INV01 approvedDiscount on a " + s + " request",
                        () -> PricingExceptionRequest.reconstitute(ID, APP, "rm-anna", DISCOUNT, REASON, T0, null, s,
                                s == RequestStatus.DECLINED ? "rev-johan" : null, s == RequestStatus.DECLINED ? T1 : null,
                                null, DISCOUNT, eventsFor(s))));
        return Stream.concat(Stream.of(
                new Bad("INV02 empty events", () -> build(RequestStatus.PENDING, null, null, null, List.of())),
                new Bad("INV02 first event not SUBMITTED", () -> build(RequestStatus.APPROVED, null, "rev-johan", DISCOUNT,
                        List.of(new RequestEvent(ID, RequestEvent.Type.APPROVED, "rev-johan", T0, RequestStatus.PENDING,
                                RequestStatus.APPROVED, null)))),
                new Bad("INV01 APPROVED without approvedDiscount", () -> build(RequestStatus.APPROVED, null, "rev-johan", null, approvedEvents)),
                new Bad("INV01 APPROVED with approvedDiscount different from discount",
                        () -> build(RequestStatus.APPROVED, null, "rev-johan", new DiscountBps(41), approvedEvents)),
                // JUDGEMENT: the history of request A must not contain events of request B
                new Bad("INV02 event of another request", () -> build(RequestStatus.PENDING, null, null, null, List.of(submitted(OTHER)))),
                // JUDGEMENT: status DECLINED but history ends in PENDING means a lost or extra event
                new Bad("INV02 last toStatus differs from status", () -> build(RequestStatus.DECLINED, null, "rev-johan", null, List.of(submitted(ID)))),
                new Bad("INV02 history ends in a decision while status is PENDING",
                        () -> build(RequestStatus.PENDING, null, null, null, List.of(submitted(ID), declined(ID, T1)))),
                new Bad("INV05 two SUBMITTED events", () -> build(RequestStatus.PENDING, null, null, null, List.of(submitted(ID), submitted(ID)))),
                new Bad("INV05 events after a final status", () -> build(RequestStatus.APPROVED, null, "rev-johan", DISCOUNT,
                        List.of(submitted(ID), declined(ID, T1), new RequestEvent(ID, RequestEvent.Type.APPROVED, "rev-johan",
                                T1.plusSeconds(5), RequestStatus.DECLINED, RequestStatus.APPROVED, null)))),
                // submit() rejects this; reconstitute must not be a back door
                new Bad("INV01 request that supersedes itself", () -> build(RequestStatus.PENDING, ID, null, null, List.of(submitted(ID)))),
                new Bad("INV03 decided request without decidedBy", () -> build(RequestStatus.DECLINED, null, null, null,
                        List.of(submitted(ID), declined(ID, T1)))),
                new Bad("INV03 decision by the requester", () -> build(RequestStatus.DECLINED, null, "rm-anna", null,
                        List.of(submitted(ID), declined(ID, T1)))),
                new Bad("INV05 PENDING request with decidedBy", () -> build(RequestStatus.PENDING, null, "rev-johan", null, List.of(submitted(ID))))),
                approvedDiscountOutsideApproved);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentData")
    void reconstitute_rejects_inconsistent_data(Bad bad) {
        assertThatThrownBy(bad.call()).isInstanceOf(IllegalArgumentException.class);
    }
}
