package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Attack tests for {@link PricingExceptionRequest#reconstitute}. Plain JUnit, fixed instants. */
class ReconstituteAttackTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-15T11:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final DiscountBps D40 = new DiscountBps(40);

    private static RequestEvent submitted(UUID requestId) {
        return new RequestEvent(requestId, RequestEvent.Type.SUBMITTED, "rm-anna", T0, null,
                RequestStatus.PENDING, null);
    }

    private static RequestEvent declined(UUID requestId, Instant at) {
        return new RequestEvent(requestId, RequestEvent.Type.DECLINED, "rev-johan", at,
                RequestStatus.PENDING, RequestStatus.DECLINED, "no");
    }

    private static PricingExceptionRequest build(RequestStatus status, UUID supersedes, String decidedBy,
                                                 DiscountBps approved, List<RequestEvent> events) {
        return PricingExceptionRequest.reconstitute(ID, "MA-A001", "rm-anna", D40, new Reason("why"), T0,
                supersedes, status, decidedBy, status == RequestStatus.PENDING ? null : T1, null, approved, events);
    }

    @Test
    void INV02_reconstitute_rejects_event_that_belongs_to_another_request() {
        // JUDGEMENT: history of request A must not contain events of request B. Code accepts it today.
        assertThatThrownBy(() -> build(RequestStatus.PENDING, null, null, null, List.of(submitted(OTHER))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV02_reconstitute_rejects_history_whose_last_toStatus_differs_from_status() {
        // JUDGEMENT: status DECLINED but history ends in PENDING (or the reverse) means a lost or extra event.
        assertThatThrownBy(() -> build(RequestStatus.DECLINED, null, "rev-johan", null, List.of(submitted(ID))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV02_reconstitute_rejects_history_that_ends_in_a_decision_while_status_is_pending() {
        // JUDGEMENT: PENDING row with a DECLINED event as the last entry.
        assertThatThrownBy(() -> build(RequestStatus.PENDING, null, null, null,
                List.of(submitted(ID), declined(ID, T1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV05_reconstitute_rejects_history_with_two_SUBMITTED_events() {
        // JUDGEMENT: SUBMITTED may only appear first.
        assertThatThrownBy(() -> build(RequestStatus.PENDING, null, null, null,
                List.of(submitted(ID), submitted(ID))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV05_reconstitute_rejects_events_after_a_final_status() {
        // JUDGEMENT: a DECLINED request cannot later have another transition in its history.
        RequestEvent later = new RequestEvent(ID, RequestEvent.Type.APPROVED, "rev-johan", T1.plusSeconds(5),
                RequestStatus.DECLINED, RequestStatus.APPROVED, null);
        assertThatThrownBy(() -> build(RequestStatus.APPROVED, null, "rev-johan", D40,
                List.of(submitted(ID), declined(ID, T1), later)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // No time-order check: a backwards clock step must not make a stored request unreadable; persistence orders events by insertion id (see PersistenceAttackTest backwards-clock test).

    @Test
    void INV01_reconstitute_rejects_request_that_supersedes_itself() {
        // submit() rejects this; reconstitute should not be a back door. JUDGEMENT.
        assertThatThrownBy(() -> build(RequestStatus.PENDING, ID, null, null, List.of(submitted(ID))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV03_reconstitute_rejects_decided_request_without_decidedBy() {
        // JUDGEMENT: a DECLINED request has been decided by someone.
        assertThatThrownBy(() -> build(RequestStatus.DECLINED, null, null, null,
                List.of(submitted(ID), declined(ID, T1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV03_reconstitute_rejects_decision_by_the_requester() {
        // JUDGEMENT: decidedBy == requestedBy can never be produced by the domain.
        assertThatThrownBy(() -> build(RequestStatus.DECLINED, null, "rm-anna", null,
                List.of(submitted(ID), declined(ID, T1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV05_reconstitute_rejects_pending_request_with_decidedBy() {
        // JUDGEMENT: PENDING means undecided.
        assertThatThrownBy(() -> build(RequestStatus.PENDING, null, "rev-johan", null, List.of(submitted(ID))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV01_reconstitute_copies_the_event_list_so_later_caller_changes_do_not_leak_in() {
        List<RequestEvent> events = new ArrayList<>(List.of(submitted(ID)));
        PricingExceptionRequest r = build(RequestStatus.PENDING, null, null, null, events);
        events.add(declined(ID, T1));
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV01_reconstitute_rejects_approved_with_null_approvedDiscount() {
        assertThatThrownBy(() -> build(RequestStatus.APPROVED, null, "rev-johan", null,
                List.of(submitted(ID))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
