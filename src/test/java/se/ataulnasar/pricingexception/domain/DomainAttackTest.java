package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Adversarial tests for the domain package. Tests marked JUDGEMENT encode an undocumented expectation. */
class DomainAttackTest {

    private record Snapshot(RequestStatus status, List<RequestEvent> events, String decidedBy,
                            java.time.Instant decidedAt, String comment, DiscountBps approved, String active) {
        static Snapshot of(PricingExceptionRequest r) {
            return new Snapshot(r.status(), List.copyOf(r.events()), r.decidedBy(), r.decidedAt(),
                    r.decisionComment(), r.approvedDiscount(), r.activeApplicationId());
        }
    }

    private static void assertRejectedAndUntouched(PricingExceptionRequest r, Consumer<PricingExceptionRequest> cmd,
                                                   String code) {
        Snapshot before = Snapshot.of(r);
        assertThatThrownBy(() -> cmd.accept(r)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo(code));
        assertThat(Snapshot.of(r)).isEqualTo(before);
    }

    // ---------- value objects ----------

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

    // ---------- submit ----------

    @Test
    void submit_with_supersedes_id_equal_to_own_id_is_rejected() {
        // JUDGEMENT: a request cannot supersede itself (INV-01 only allows replacing another PENDING request).
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> PricingExceptionRequest.submit(id, APPLICATION_ID, ANNA, new DiscountBps(10),
                new Reason("x"), id, CLOCK)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void submit_with_blank_application_id_is_rejected() {
        // JUDGEMENT: a blank application id would also break active_application_id uniqueness (INV-04).
        assertThatThrownBy(() -> PricingExceptionRequest.submit(UUID.randomUUID(), "  ", ANNA, new DiscountBps(10),
                new Reason("x"), null, CLOCK)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void submit_by_reviewer_with_rm_like_id_is_wrong_role_and_creates_nothing() {
        assertThatThrownBy(() -> PricingExceptionRequest.submit(UUID.randomUUID(), APPLICATION_ID,
                Actor.reviewer("rm-anna", 150), new DiscountBps(10), new Reason("x"), null, CLOCK))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("WRONG_ROLE"));
    }

    // ---------- decisions: failure leaves state untouched ----------

    @Test
    void INV03_failed_approve_over_limit_leaves_no_partial_decision() {
        PricingExceptionRequest r = pending(80);
        assertRejectedAndUntouched(r, x -> x.approve(SARA, "no", LATER_CLOCK), "OVER_APPROVAL_LIMIT");
        assertThat(r.decidedBy()).isNull();
        assertThat(r.decidedAt()).isNull();
        assertThat(r.decisionComment()).isNull();
        // a later valid approve by a higher-limit reviewer is clean
        r.approve(JOHAN, "ok", LATER_CLOCK);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decisionComment()).isEqualTo("ok");
        assertThat(r.events()).hasSize(2);
    }

    @Test
    void INV03_reviewer_with_limit_zero_cannot_approve_anything() {
        PricingExceptionRequest r = pending(1);
        assertRejectedAndUntouched(r, x -> x.approve(Actor.reviewer("rev-zero", 0), null, CLOCK), "OVER_APPROVAL_LIMIT");
        // but may decline
        r.decline(Actor.reviewer("rev-zero", 0), null, CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void INV03_same_id_with_reviewer_role_is_still_self_review() {
        PricingExceptionRequest r = pending();
        Actor annaAsReviewer = Actor.reviewer("rm-anna", 150);
        assertRejectedAndUntouched(r, x -> x.approve(annaAsReviewer, null, CLOCK), "SELF_REVIEW");
        assertRejectedAndUntouched(r, x -> x.decline(annaAsReviewer, null, CLOCK), "SELF_REVIEW");
    }

    @Test
    void check_order_self_review_beats_over_limit() {
        PricingExceptionRequest r = pending(100);
        Actor annaAsLowReviewer = Actor.reviewer("rm-anna", 10);
        assertRejectedAndUntouched(r, x -> x.approve(annaAsLowReviewer, null, CLOCK), "SELF_REVIEW");
    }

    @Test
    void check_order_role_beats_self_review_and_status_beats_role() {
        PricingExceptionRequest r = pending();
        assertRejectedAndUntouched(r, x -> x.approve(Actor.mortgageProcess("rm-anna"), null, CLOCK), "WRONG_ROLE");
        assertRejectedAndUntouched(r, x -> x.decline(Actor.rm("rev-sara"), null, CLOCK), "WRONG_ROLE");

        PricingExceptionRequest done = inStatus(RequestStatus.WITHDRAWN);
        assertRejectedAndUntouched(done, x -> x.approve(MORTGAGE, null, CLOCK), "INVALID_TRANSITION");
        assertRejectedAndUntouched(done, x -> x.decline(ANNA, null, CLOCK), "INVALID_TRANSITION");
    }

    @Test
    void INV05_every_rejected_command_on_every_final_status_leaves_state_untouched() {
        for (RequestStatus s : RequestStatus.values()) {
            if (s == RequestStatus.PENDING) continue;
            assertRejectedAndUntouched(inStatus(s), x -> x.approve(JOHAN, "c", LATER_CLOCK), "INVALID_TRANSITION");
            assertRejectedAndUntouched(inStatus(s), x -> x.decline(JOHAN, "c", LATER_CLOCK), "INVALID_TRANSITION");
            assertRejectedAndUntouched(inStatus(s), x -> x.withdraw(ANNA, LATER_CLOCK), "INVALID_TRANSITION");
            assertRejectedAndUntouched(inStatus(s), x -> x.markSuperseded(ANNA, LATER_CLOCK), "INVALID_TRANSITION");
        }
    }

    @Test
    void INV05_approved_request_keeps_original_decision_after_repeated_approve_and_decline() {
        PricingExceptionRequest r = pending(40);
        r.approve(JOHAN, "first", CLOCK);
        assertRejectedAndUntouched(r, x -> x.approve(Actor.reviewer("rev-other", 150), "second", LATER_CLOCK),
                "INVALID_TRANSITION");
        assertRejectedAndUntouched(r, x -> x.decline(Actor.reviewer("rev-other", 150), "second", LATER_CLOCK),
                "INVALID_TRANSITION");
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decisionComment()).isEqualTo("first");
        assertThat(r.decidedAt()).isEqualTo(NOW);
        assertThat(r.approvedDiscount().value()).isEqualTo(40);
    }

    @Test
    void owner_commands_with_wrong_actor_leave_state_untouched() {
        PricingExceptionRequest r = pending();
        assertRejectedAndUntouched(r, x -> x.withdraw(ERIK, CLOCK), "NOT_OWNER");
        assertRejectedAndUntouched(r, x -> x.markSuperseded(ERIK, CLOCK), "NOT_OWNER");
        assertRejectedAndUntouched(r, x -> x.withdraw(Actor.reviewer("rm-anna", 150), CLOCK), "WRONG_ROLE");
        assertRejectedAndUntouched(r, x -> x.markSuperseded(SARA, CLOCK), "WRONG_ROLE");
    }

    // ---------- comment validation ----------

    @Test
    void comment_of_501_chars_is_rejected_on_decline_and_leaves_state_untouched() {
        PricingExceptionRequest r = pending();
        assertRejectedAndUntouched(r, x -> x.decline(SARA, "x".repeat(501), CLOCK), "INVALID_COMMENT");
        assertRejectedAndUntouched(r, x -> x.approve(JOHAN, "x".repeat(501), CLOCK), "INVALID_COMMENT");
    }

    @Test
    void comment_of_500_whitespace_chars_is_accepted_on_both_decisions() {
        PricingExceptionRequest a = pending();
        a.approve(JOHAN, " ".repeat(500), CLOCK);
        assertThat(a.status()).isEqualTo(RequestStatus.APPROVED);
        PricingExceptionRequest d = pending();
        d.decline(SARA, " ".repeat(500), CLOCK);
        assertThat(d.status()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void invalid_comment_on_final_request_reports_invalid_transition_first() {
        // JUDGEMENT: design check order says "status, then role, ..."; comment validation is not listed,
        // so status should arguably win.
        PricingExceptionRequest r = inStatus(RequestStatus.DECLINED);
        assertRejectedAndUntouched(r, x -> x.approve(JOHAN, "x".repeat(501), CLOCK), "INVALID_TRANSITION");
    }

    @Test
    void invalid_comment_with_over_limit_reports_over_limit_first() {
        // JUDGEMENT: authorisation errors should not be masked by input validation.
        PricingExceptionRequest r = pending(80);
        assertRejectedAndUntouched(r, x -> x.approve(SARA, "x".repeat(501), CLOCK), "OVER_APPROVAL_LIMIT");
    }

    // ---------- events ----------

    @Test
    void INV02_events_returned_earlier_is_a_snapshot_not_a_live_view() {
        // JUDGEMENT: a persistence layer or caller holding the list should not see it grow silently.
        PricingExceptionRequest r = pending();
        List<RequestEvent> held = r.events();
        r.approve(JOHAN, null, CLOCK);
        assertThat(held).hasSize(1);
    }

    @Test
    void INV02_event_chain_is_consistent_with_status_and_timestamps() {
        PricingExceptionRequest r = pending();
        r.approve(JOHAN, "fine", LATER_CLOCK);
        List<RequestEvent> ev = r.events();
        assertThat(ev).extracting(RequestEvent::type)
                .containsExactly(RequestEvent.Type.SUBMITTED, RequestEvent.Type.APPROVED);
        assertThat(ev.get(0).occurredAt()).isEqualTo(NOW);
        assertThat(ev.get(1).occurredAt()).isEqualTo(LATER).isEqualTo(r.decidedAt());
        assertThat(ev.get(1).fromStatus()).isEqualTo(ev.get(0).toStatus());
        assertThat(ev.get(1).toStatus()).isEqualTo(r.status());
        assertThat(ev.get(1).comment()).isEqualTo("fine");
        assertThat(ev).allSatisfy(e -> assertThat(e.requestId()).isEqualTo(r.id()));
        assertThat(r.createdAt()).isEqualTo(NOW);
    }

    @Test
    void exception_messages_do_not_contain_user_reason_text() {
        PricingExceptionRequest r = pending(100);
        assertThatThrownBy(() -> r.approve(SARA, null, CLOCK))
                .hasMessageNotContaining("Long-standing customer");
    }
}
