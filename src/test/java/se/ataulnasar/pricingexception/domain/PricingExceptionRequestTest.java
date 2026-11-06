package se.ataulnasar.pricingexception.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.ataulnasar.pricingexception.domain.Fixtures.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PricingExceptionRequestTest {

    private static final String LONG = "x".repeat(501);

    private static PricingExceptionRequest submitAs(Actor requester, int discount, UUID supersedes) {
        return PricingExceptionRequest.submit(UUID.randomUUID(), APPLICATION_ID, requester, new DiscountBps(discount),
                new Reason("r"), supersedes, CLOCK);
    }

    private static RequestEvent last(PricingExceptionRequest r) {
        return r.events().get(r.events().size() - 1);
    }

    private static void assertForbidden(Runnable cmd, String code) {
        assertThatThrownBy(cmd::run).isInstanceOfSatisfying(ForbiddenActionException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void submit_creates_pending_request_with_given_data_and_one_submitted_event() {
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
        assertThat(r.activeApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(r.events()).hasSize(1);
        RequestEvent e = r.events().get(0);
        assertThat(e.type()).isEqualTo(RequestEvent.Type.SUBMITTED);
        assertThat(e.requestId()).isEqualTo(id);
        assertThat(e.actorId()).isEqualTo("rm-anna");
        assertThat(e.occurredAt()).isEqualTo(NOW);
        assertThat(e.fromStatus()).isNull();
        assertThat(e.toStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void submit_records_supersedes_id() {
        UUID old = UUID.randomUUID();
        assertThat(submitAs(ANNA, 30, old).supersedesId()).isEqualTo(old);
    }

    @Test
    void submit_only_allowed_for_rm_role_even_when_the_id_looks_like_an_rm() {
        for (Actor a : new Actor[] {SARA, MORTGAGE, Actor.reviewer("rm-anna", 150)}) {
            assertForbidden(() -> submitAs(a, 10, null), "WRONG_ROLE");
        }
    }

    @Test
    void submit_rejects_a_request_that_supersedes_itself_and_a_blank_application_id() {
        // JUDGEMENT: a request cannot supersede itself (INV-01); a blank application id would break INV-04 uniqueness.
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> PricingExceptionRequest.submit(id, APPLICATION_ID, ANNA, new DiscountBps(10),
                new Reason("x"), id, CLOCK)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PricingExceptionRequest.submit(UUID.randomUUID(), "  ", ANNA, new DiscountBps(10),
                new Reason("x"), null, CLOCK)).isInstanceOf(RuntimeException.class);
    }

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
        // Immutability by API: no setter and no method (other than the static factory) takes a DiscountBps.
        Method[] methods = Arrays.stream(PricingExceptionRequest.class.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .toArray(Method[]::new);
        assertThat(methods)
                .noneMatch(m -> m.getName().startsWith("set"))
                .noneMatch(m -> m.getName().toLowerCase().contains("discount") && m.getParameterCount() > 0);
        assertThat(Arrays.stream(methods).filter(m -> !Modifier.isStatic(m.getModifiers()))
                .flatMap(m -> Arrays.stream(m.getParameterTypes())))
                .doesNotContain(DiscountBps.class, int.class, Integer.class);
        for (RequestStatus s : RequestStatus.values()) {
            assertThat(inStatus(s).discount()).as(s.name()).isEqualTo(new DiscountBps(40)); // unchanged by every command
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
                assertThatThrownBy(() -> r.markSuperseded(ANNA, CLOCK)).as(s.name()).isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void INV03_requester_cannot_approve_own_request() {
        Actor self = Actor.reviewer("rev-sara", 150); // same id as the requester, who acted as RM when submitting
        PricingExceptionRequest r = submitAs(Actor.rm(self.id()), 10, null);

        assertForbidden(() -> r.approve(self, null, CLOCK), "SELF_REVIEW");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV03_requester_cannot_decline_own_request() {
        Actor self = Actor.reviewer("rev-sara", 150);
        PricingExceptionRequest r = submitAs(Actor.rm(self.id()), 10, null);

        assertForbidden(() -> r.decline(self, null, CLOCK), "SELF_REVIEW");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void INV03_reviewer_cannot_approve_above_limit() {
        PricingExceptionRequest r = pending(51); // Sara's limit is 50
        assertForbidden(() -> r.approve(SARA, null, CLOCK), "OVER_APPROVAL_LIMIT");
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.approvedDiscount()).isNull();
        assertThat(r.events()).hasSize(1);
        assertThat(r.activeApplicationId()).isEqualTo(APPLICATION_ID);
        assertThatThrownBy(() -> r.approve(SARA, null, CLOCK)).hasMessageNotContaining("Long-standing customer");
    }

    /** display, discount, reviewer, approve (false = decline), expected status. */
    static Stream<Arguments> allowedDecisions() {
        return Stream.of(
                Arguments.of("INV03 exactly at the limit", 50, SARA, true, RequestStatus.APPROVED),
                Arguments.of("INV03 below the limit", 49, SARA, true, RequestStatus.APPROVED),
                Arguments.of("INV03 higher-limit reviewer approves what a lower one cannot", 100, JOHAN, true, RequestStatus.APPROVED),
                Arguments.of("INV03 the limit applies to approve only, not decline", 100, SARA, false, RequestStatus.DECLINED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedDecisions")
    void INV03_reviewer_decisions_within_authority(String display, int discount, Actor reviewer, boolean approve,
                                                   RequestStatus expected) {
        PricingExceptionRequest r = pending(discount);
        if (approve) r.approve(reviewer, null, CLOCK); else r.decline(reviewer, "too high", CLOCK);
        assertThat(r.status()).isEqualTo(expected);
    }

    /** display, command, event type, new status, actor, comment, is the application slot still held. */
    static Stream<Arguments> commandsOnPending() {
        return Stream.of(
                cmd("approve", r -> r.approve(JOHAN, "fine", LATER_CLOCK), RequestEvent.Type.APPROVED, RequestStatus.APPROVED,
                        "rev-johan", "fine", true),
                cmd("decline", r -> r.decline(JOHAN, "fine", LATER_CLOCK), RequestEvent.Type.DECLINED, RequestStatus.DECLINED,
                        "rev-johan", "fine", false),
                cmd("withdraw", r -> r.withdraw(ANNA, LATER_CLOCK), RequestEvent.Type.WITHDRAWN, RequestStatus.WITHDRAWN,
                        "rm-anna", null, false),
                cmd("supersede", r -> r.markSuperseded(ANNA, LATER_CLOCK), RequestEvent.Type.SUPERSEDED, RequestStatus.SUPERSEDED,
                        "rm-anna", null, false));
    }

    private static Arguments cmd(String name, Consumer<PricingExceptionRequest> c, RequestEvent.Type type, RequestStatus to,
                                 String actor, String comment, boolean active) {
        return Arguments.of(name, c, type, to, actor, comment, active);
    }

    @ParameterizedTest(name = "INV02_{0}_writes_one_event")
    @MethodSource("commandsOnPending")
    void INV02_each_command_writes_exactly_one_event(String name, Consumer<PricingExceptionRequest> command,
                                                     RequestEvent.Type type, RequestStatus to, String actor, String comment,
                                                     boolean active) {
        PricingExceptionRequest r = pending();
        command.accept(r);

        assertThat(r.status()).isEqualTo(to);
        assertThat(r.activeApplicationId()).isEqualTo(active ? APPLICATION_ID : null); // INV-04: only PENDING/APPROVED hold the slot
        assertThat(r.events()).hasSize(2);
        RequestEvent e = last(r);
        assertThat(e.type()).isEqualTo(type);
        assertThat(e.actorId()).isEqualTo(actor);
        assertThat(e.occurredAt()).isEqualTo(LATER);
        assertThat(e.fromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.toStatus()).isEqualTo(to);
        assertThat(e.comment()).isEqualTo(comment);
    }

    @Test
    void decision_records_decider_time_and_comment() {
        for (boolean approve : new boolean[] {true, false}) {
            PricingExceptionRequest r = pending();
            if (approve) r.approve(JOHAN, "fine", LATER_CLOCK); else r.decline(JOHAN, "fine", LATER_CLOCK);
            assertThat(r.decidedBy()).isEqualTo("rev-johan");
            assertThat(r.decidedAt()).isEqualTo(LATER);
            assertThat(r.decisionComment()).isEqualTo("fine");
        }
    }

    @Test
    void INV02_event_chain_is_consistent_with_status_and_timestamps() {
        PricingExceptionRequest r = pending();
        r.approve(JOHAN, "fine", LATER_CLOCK);
        List<RequestEvent> ev = r.events();
        assertThat(ev).extracting(RequestEvent::type).containsExactly(RequestEvent.Type.SUBMITTED, RequestEvent.Type.APPROVED);
        assertThat(ev.get(0).occurredAt()).isEqualTo(NOW);
        assertThat(ev.get(1).occurredAt()).isEqualTo(LATER).isEqualTo(r.decidedAt());
        assertThat(ev.get(1).fromStatus()).isEqualTo(ev.get(0).toStatus());
        assertThat(ev.get(1).toStatus()).isEqualTo(r.status());
        assertThat(ev).allSatisfy(e -> assertThat(e.requestId()).isEqualTo(r.id()));
    }

    @Test
    void INV02_events_view_is_read_only_and_an_earlier_view_is_a_snapshot() {
        // JUDGEMENT: a persistence layer or caller holding the list should not see it grow silently.
        PricingExceptionRequest r = pending();
        List<RequestEvent> held = r.events();
        assertThatThrownBy(() -> r.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        r.approve(JOHAN, null, CLOCK);
        assertThat(held).hasSize(1);
    }

    @Test
    void decision_comment_is_optional_and_500_chars_or_500_spaces_are_accepted() {
        for (String comment : new String[] {null, "c".repeat(500), " ".repeat(500)}) {
            PricingExceptionRequest a = pending();
            a.approve(JOHAN, comment, CLOCK);
            assertThat(a.status()).isEqualTo(RequestStatus.APPROVED);
            assertThat(a.decisionComment()).isEqualTo(comment);
            PricingExceptionRequest d = pending();
            d.decline(SARA, comment, CLOCK);
            assertThat(d.status()).isEqualTo(RequestStatus.DECLINED);
        }
    }

    /** One rejected call: the request, the command, and the error code it must give. */
    private record Rejected(String name, Supplier<PricingExceptionRequest> request, Consumer<PricingExceptionRequest> command,
                            String code) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Rejected rej(String name, Supplier<PricingExceptionRequest> request, Consumer<PricingExceptionRequest> command,
                                String code) {
        return new Rejected(name, request, command, code);
    }

    static Stream<Rejected> rejectedCommands() {
        Actor annaAsReviewer = Actor.reviewer("rm-anna", 150);
        Supplier<PricingExceptionRequest> declined = () -> inStatus(RequestStatus.DECLINED);
        return Stream.of(
                rej("INV03 over limit 80 by sara (no partial decision)", () -> pending(80), r -> r.approve(SARA, "no", LATER_CLOCK), "OVER_APPROVAL_LIMIT"),
                rej("INV03 reviewer with limit zero cannot approve anything", () -> pending(1), r -> r.approve(Actor.reviewer("rev-zero", 0), null, CLOCK), "OVER_APPROVAL_LIMIT"),
                rej("INV03 same id with reviewer role is self review on approve", Fixtures::pending, r -> r.approve(annaAsReviewer, null, CLOCK), "SELF_REVIEW"),
                rej("INV03 same id with reviewer role is self review on decline", Fixtures::pending, r -> r.decline(annaAsReviewer, null, CLOCK), "SELF_REVIEW"),
                rej("INV03 only reviewers decide: erik approve", Fixtures::pending, r -> r.approve(ERIK, null, CLOCK), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: erik decline", Fixtures::pending, r -> r.decline(ERIK, null, CLOCK), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: mortgage approve", Fixtures::pending, r -> r.approve(MORTGAGE, null, CLOCK), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: mortgage decline", Fixtures::pending, r -> r.decline(MORTGAGE, null, CLOCK), "WRONG_ROLE"),
                rej("check order: self review beats over limit", () -> pending(100), r -> r.approve(Actor.reviewer("rm-anna", 10), null, CLOCK), "SELF_REVIEW"),
                rej("check order: role beats self review (approve)", Fixtures::pending, r -> r.approve(Actor.mortgageProcess("rm-anna"), null, CLOCK), "WRONG_ROLE"),
                rej("check order: role beats self review (decline)", Fixtures::pending, r -> r.decline(Actor.rm("rev-sara"), null, CLOCK), "WRONG_ROLE"),
                rej("check order: status beats role (approve)", () -> inStatus(RequestStatus.WITHDRAWN), r -> r.approve(MORTGAGE, null, CLOCK), "INVALID_TRANSITION"),
                rej("check order: status beats role (decline)", () -> inStatus(RequestStatus.WITHDRAWN), r -> r.decline(ANNA, null, CLOCK), "INVALID_TRANSITION"),
                rej("withdraw by another RM is NOT_OWNER", Fixtures::pending, r -> r.withdraw(ERIK, CLOCK), "NOT_OWNER"),
                rej("supersede by another RM is NOT_OWNER", Fixtures::pending, r -> r.markSuperseded(ERIK, CLOCK), "NOT_OWNER"),
                rej("withdraw by a reviewer with the owner's id is WRONG_ROLE", Fixtures::pending, r -> r.withdraw(annaAsReviewer, CLOCK), "WRONG_ROLE"),
                rej("withdraw by reviewer sara is WRONG_ROLE", Fixtures::pending, r -> r.withdraw(SARA, CLOCK), "WRONG_ROLE"),
                rej("withdraw by reviewer johan is WRONG_ROLE", Fixtures::pending, r -> r.withdraw(JOHAN, CLOCK), "WRONG_ROLE"),
                rej("withdraw by mortgage process is WRONG_ROLE", Fixtures::pending, r -> r.withdraw(MORTGAGE, CLOCK), "WRONG_ROLE"),
                rej("supersede by reviewer sara is WRONG_ROLE", Fixtures::pending, r -> r.markSuperseded(SARA, CLOCK), "WRONG_ROLE"),
                rej("supersede by reviewer johan is WRONG_ROLE", Fixtures::pending, r -> r.markSuperseded(JOHAN, CLOCK), "WRONG_ROLE"),
                rej("supersede by mortgage process is WRONG_ROLE", Fixtures::pending, r -> r.markSuperseded(MORTGAGE, CLOCK), "WRONG_ROLE"),
                rej("comment of 501 chars is rejected on approve", Fixtures::pending, r -> r.approve(JOHAN, LONG, CLOCK), "INVALID_COMMENT"),
                rej("comment of 501 chars is rejected on decline", Fixtures::pending, r -> r.decline(SARA, LONG, CLOCK), "INVALID_COMMENT"),
                // JUDGEMENT: status errors come before input validation, authorisation errors before input validation
                rej("invalid comment on a final request reports INVALID_TRANSITION first", declined, r -> r.approve(JOHAN, LONG, CLOCK), "INVALID_TRANSITION"),
                rej("invalid comment with over limit reports OVER_APPROVAL_LIMIT first", () -> pending(80), r -> r.approve(SARA, LONG, CLOCK), "OVER_APPROVAL_LIMIT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCommands")
    void rejected_command_leaves_the_request_untouched(Rejected c) {
        assertRejectedAndUntouched(c.request().get(), c.command(), c.code());
    }

    @Test
    void INV03_after_a_failed_over_limit_approve_a_valid_approve_is_clean() {
        PricingExceptionRequest r = pending(80);
        assertRejectedAndUntouched(r, x -> x.approve(SARA, "no", LATER_CLOCK), "OVER_APPROVAL_LIMIT");
        r.approve(JOHAN, "ok", LATER_CLOCK);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decisionComment()).isEqualTo("ok");
        assertThat(r.events()).hasSize(2);
    }

    @Test
    void INV03_a_reviewer_with_limit_zero_may_still_decline() {
        PricingExceptionRequest r = pending(1);
        r.decline(Actor.reviewer("rev-zero", 0), null, CLOCK);
        assertThat(r.status()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void INV05_approved_request_keeps_original_decision_after_repeated_approve_and_decline() {
        PricingExceptionRequest r = pending(40);
        r.approve(JOHAN, "first", CLOCK);
        Actor other = Actor.reviewer("rev-other", 150);
        assertRejectedAndUntouched(r, x -> x.approve(other, "second", LATER_CLOCK), "INVALID_TRANSITION");
        assertRejectedAndUntouched(r, x -> x.decline(other, "second", LATER_CLOCK), "INVALID_TRANSITION");
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decisionComment()).isEqualTo("first");
        assertThat(r.decidedAt()).isEqualTo(NOW);
        assertThat(r.approvedDiscount().value()).isEqualTo(40);
    }
}
