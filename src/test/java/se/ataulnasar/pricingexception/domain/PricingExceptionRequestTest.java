package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PricingExceptionRequestTest {

    // ---------- submit ----------

    @Test
    void submit_creates_pending_request_with_given_data() {
        UUID id = UUID.randomUUID();
        PricingExceptionRequest r = PricingExceptionRequest.submit(
                id, APPLICATION_ID, ANNA, new DiscountBps(40), new Reason("why"), null, CLOCK);

        assertThat(r.id()).isEqualTo(id);
        assertThat(r.applicationId()).isEqualTo(APPLICATION_ID);
        assertThat(r.requestedBy()).isEqualTo("rm-anna");
        assertThat(r.discount()).isEqualTo(new DiscountBps(40));
        assertThat(r.reason()).isEqualTo(new Reason("why"));
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.createdAt()).isEqualTo(NOW);
        assertThat(r.supersedesId()).isNull();
        assertThat(r.decidedBy()).isNull();
        assertThat(r.decidedAt()).isNull();
        assertThat(r.approvedDiscount()).isNull();
    }

    @Test
    void submit_records_supersedes_id() {
        UUID old = UUID.randomUUID();
        PricingExceptionRequest r = PricingExceptionRequest.submit(
                UUID.randomUUID(), APPLICATION_ID, ANNA, new DiscountBps(30), new Reason("r"), old, CLOCK);
        assertThat(r.supersedesId()).isEqualTo(old);
    }

    @Test
    void submit_writes_one_submitted_event() {
        PricingExceptionRequest r = pending();
        assertThat(r.events()).hasSize(1);
        RequestEvent e = r.events().get(0);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.SUBMITTED);
        assertThat(e.requestId()).isEqualTo(r.id());
        assertThat(e.actorId()).isEqualTo("rm-anna");
        assertThat(e.occurredAt()).isEqualTo(NOW);
        assertThat(e.fromStatus()).isNull();
        assertThat(e.toStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void submit_only_allowed_for_rm_role() {
        for (Actor a : new Actor[] {SARA, MORTGAGE}) {
            assertThatThrownBy(() -> PricingExceptionRequest.submit(
                    UUID.randomUUID(), APPLICATION_ID, a, new DiscountBps(10), new Reason("r"), null, CLOCK))
                    .isInstanceOf(ForbiddenActionException.class)
                    .extracting("code").isEqualTo("WRONG_ROLE");
        }
    }

    @Test
    void activeApplicationId_is_set_for_pending_request() {
        assertThat(pending().activeApplicationId()).isEqualTo(APPLICATION_ID);
    }

    // ---------- INV-01 ----------

    @Test
    void INV01_approval_records_reviewed_discount() {
        PricingExceptionRequest r = pending(42);
        r.approve(JOHAN, "ok", CLOCK);

        assertThat(r.approvedDiscount()).isEqualTo(new DiscountBps(42));
        assertThat(r.discount()).isEqualTo(new DiscountBps(42));
    }

    @Test
    void INV01_declined_request_has_no_approved_discount() {
        PricingExceptionRequest r = pending(42);
        r.decline(JOHAN, null, CLOCK);
        assertThat(r.approvedDiscount()).isNull();
    }

    @Test
    void INV01_request_has_no_way_to_change_the_discount() {
        // Immutability by API: no setter and no method (other than the static factory)
        // takes a DiscountBps.
        Method[] methods = Arrays.stream(PricingExceptionRequest.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .toArray(Method[]::new);
        assertThat(methods)
                .noneMatch(m -> m.getName().startsWith("set"))
                .noneMatch(m -> m.getName().toLowerCase().contains("discount")
                        && m.getParameterCount() > 0);
        assertThat(Arrays.stream(methods)
                .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .flatMap(m -> Arrays.stream(m.getParameterTypes())))
                .doesNotContain(DiscountBps.class, int.class, Integer.class);
    }

    @Test
    void INV01_discount_is_unchanged_by_every_command() {
        for (RequestStatus s : RequestStatus.values()) {
            PricingExceptionRequest r = inStatus(s);
            assertThat(r.discount()).as(s.name()).isEqualTo(new DiscountBps(40));
        }
    }

    @Test
    void INV01_only_pending_request_can_be_superseded() {
        for (RequestStatus s : RequestStatus.values()) {
            PricingExceptionRequest r = inStatus(s);
            if (s == RequestStatus.PENDING) {
                r.markSuperseded(ANNA, CLOCK);
                assertThat(r.status()).isEqualTo(RequestStatus.SUPERSEDED);
            } else {
                assertThatThrownBy(() -> r.markSuperseded(ANNA, CLOCK))
                        .as(s.name())
                        .isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void INV01_superseding_clears_active_application_id() {
        PricingExceptionRequest r = pending();
        r.markSuperseded(ANNA, CLOCK);
        assertThat(r.activeApplicationId()).isNull();
    }

    // ---------- INV-03 ----------

    @Test
    void INV03_requester_cannot_approve_own_request() {
        // A reviewer who is also the requester (same id) must be rejected.
        Actor selfReviewer = Actor.reviewer("rev-sara", 150);
        PricingExceptionRequest r = PricingExceptionRequest.submit(
                UUID.randomUUID(), APPLICATION_ID, selfAsRm(selfReviewer),
                new DiscountBps(10), new Reason("r"), null, CLOCK);

        assertThatThrownBy(() -> r.approve(selfReviewer, null, CLOCK))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting("code").isEqualTo("SELF_REVIEW");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV03_requester_cannot_decline_own_request() {
        Actor selfReviewer = Actor.reviewer("rev-sara", 150);
        PricingExceptionRequest r = PricingExceptionRequest.submit(
                UUID.randomUUID(), APPLICATION_ID, selfAsRm(selfReviewer),
                new DiscountBps(10), new Reason("r"), null, CLOCK);

        assertThatThrownBy(() -> r.decline(selfReviewer, null, CLOCK))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting("code").isEqualTo("SELF_REVIEW");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events()).hasSize(1);
    }

    /** Same id as the reviewer, but acting as RM when submitting (identity is the id). */
    private static Actor selfAsRm(Actor reviewer) {
        return Actor.rm(reviewer.id());
    }

    @Test
    void INV03_reviewer_cannot_approve_above_limit() {
        PricingExceptionRequest r = pending(51); // Sara's limit is 50
        assertThatThrownBy(() -> r.approve(SARA, null, CLOCK))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting("code").isEqualTo("OVER_APPROVAL_LIMIT");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.approvedDiscount()).isNull();
        assertThat(r.events()).hasSize(1);
        assertThat(r.activeApplicationId()).isEqualTo(APPLICATION_ID);
    }

    @Test
    void INV03_reviewer_can_approve_exactly_at_limit() {
        PricingExceptionRequest r = pending(50);
        r.approve(SARA, null, CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void INV03_reviewer_can_approve_below_limit() {
        PricingExceptionRequest r = pending(49);
        r.approve(SARA, null, CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void INV03_higher_limit_reviewer_can_approve_what_lower_limit_cannot() {
        PricingExceptionRequest r = pending(100);
        r.approve(JOHAN, null, CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void INV03_limit_applies_to_approve_only_not_decline() {
        PricingExceptionRequest r = pending(100);
        r.decline(SARA, "too high", CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void INV03_only_reviewers_can_decide() {
        for (Actor a : new Actor[] {ERIK, MORTGAGE}) {
            PricingExceptionRequest r = pending();
            assertThatThrownBy(() -> r.approve(a, null, CLOCK))
                    .isInstanceOf(ForbiddenActionException.class).extracting("code").isEqualTo("WRONG_ROLE");
            assertThatThrownBy(() -> r.decline(a, null, CLOCK))
                    .isInstanceOf(ForbiddenActionException.class).extracting("code").isEqualTo("WRONG_ROLE");
            assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        }
    }

    // ---------- decisions: data recorded ----------

    @Test
    void approve_records_decider_time_comment_and_event() {
        PricingExceptionRequest r = pending();
        r.approve(JOHAN, "fine", LATER_CLOCK);

        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decidedAt()).isEqualTo(LATER);
        assertThat(r.decisionComment()).isEqualTo("fine");
        assertThat(r.activeApplicationId()).isEqualTo(APPLICATION_ID); // APPROVED is still active

        assertThat(r.events()).hasSize(2);
        RequestEvent e = r.events().get(1);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.APPROVED);
        assertThat(e.actorId()).isEqualTo("rev-johan");
        assertThat(e.occurredAt()).isEqualTo(LATER);
        assertThat(e.fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(e.comment()).isEqualTo("fine");
    }

    @Test
    void decline_records_decider_time_comment_event_and_clears_active() {
        PricingExceptionRequest r = pending();
        r.decline(JOHAN, "no", LATER_CLOCK);

        assertThat(r.status()).isEqualTo(RequestStatus.DECLINED);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decidedAt()).isEqualTo(LATER);
        assertThat(r.decisionComment()).isEqualTo("no");
        assertThat(r.activeApplicationId()).isNull();

        assertThat(r.events()).hasSize(2);
        RequestEvent e = r.events().get(1);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.DECLINED);
        assertThat(e.fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void decision_comment_is_optional() {
        PricingExceptionRequest r = pending();
        r.approve(JOHAN, null, CLOCK);
        assertThat(r.decisionComment()).isNull();
    }

    @Test
    void decision_comment_allows_500_but_rejects_501_characters() {
        PricingExceptionRequest ok = pending();
        ok.approve(JOHAN, "c".repeat(500), CLOCK);
        assertThat(ok.status()).isEqualTo(RequestStatus.APPROVED);

        PricingExceptionRequest tooLong = pending();
        assertThatThrownBy(() -> tooLong.approve(JOHAN, "c".repeat(501), CLOCK))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_COMMENT"));
        assertThatThrownBy(() -> tooLong.decline(JOHAN, "c".repeat(501), CLOCK))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_COMMENT"));
        assertThat(tooLong.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(tooLong.events()).hasSize(1);
    }

    // ---------- withdraw / supersede: owner only ----------

    @Test
    void withdraw_by_owner_sets_withdrawn_clears_active_and_writes_event() {
        PricingExceptionRequest r = pending();
        r.withdraw(ANNA, LATER_CLOCK);

        assertThat(r.status()).isEqualTo(RequestStatus.WITHDRAWN);
        assertThat(r.activeApplicationId()).isNull();
        assertThat(r.events()).hasSize(2);
        RequestEvent e = r.events().get(1);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.WITHDRAWN);
        assertThat(e.actorId()).isEqualTo("rm-anna");
        assertThat(e.occurredAt()).isEqualTo(LATER);
        assertThat(e.fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(RequestStatus.WITHDRAWN);
    }

    @Test
    void supersede_by_owner_writes_event() {
        PricingExceptionRequest r = pending();
        r.markSuperseded(ANNA, LATER_CLOCK);
        assertThat(r.events()).hasSize(2);
        RequestEvent e = r.events().get(1);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.SUPERSEDED);
        assertThat(e.fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(e.occurredAt()).isEqualTo(LATER);
    }

    @Test
    void withdraw_and_supersede_by_non_owner_are_forbidden() {
        for (Actor a : new Actor[] {ERIK, SARA, JOHAN, MORTGAGE}) {
            PricingExceptionRequest r = pending();
            assertThatThrownBy(() -> r.withdraw(a, CLOCK))
                    .as("withdraw " + a.id())
                    .isInstanceOf(ForbiddenActionException.class);
            assertThatThrownBy(() -> r.markSuperseded(a, CLOCK))
                    .as("supersede " + a.id())
                    .isInstanceOf(ForbiddenActionException.class);
            assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
            assertThat(r.events()).hasSize(1);
        }
    }

    @Test
    void non_owner_rm_gets_not_owner_code() {
        PricingExceptionRequest r = pending();
        assertThatThrownBy(() -> r.withdraw(ERIK, CLOCK))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting("code").isEqualTo("NOT_OWNER");
    }

    // ---------- events list is read-only ----------

    @Test
    void events_view_cannot_be_modified_from_outside() {
        PricingExceptionRequest r = pending();
        assertThatThrownBy(() -> r.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- exception codes ----------

    @Test
    void forbidden_action_exception_has_stable_code() {
        PricingExceptionRequest r = pending(51);
        assertThatThrownBy(() -> r.approve(SARA, null, CLOCK))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("OVER_APPROVAL_LIMIT"));
    }

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

    // ---------- submit: edge cases ----------

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

    // ---------- decisions: a failed command leaves state untouched ----------

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

    // ---------- decision comment validation ----------

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

    // ---------- events: snapshot and chain ----------

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
