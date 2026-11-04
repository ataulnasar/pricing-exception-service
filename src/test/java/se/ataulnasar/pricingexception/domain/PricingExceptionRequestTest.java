package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
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
}
