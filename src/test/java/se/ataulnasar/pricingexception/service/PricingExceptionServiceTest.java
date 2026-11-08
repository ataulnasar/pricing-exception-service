package se.ataulnasar.pricingexception.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static se.ataulnasar.pricingexception.service.ServiceFixtures.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import se.ataulnasar.pricingexception.dto.Decision;
import se.ataulnasar.pricingexception.dto.EventResponse;
import se.ataulnasar.pricingexception.dto.PricingExceptionResponse;
import se.ataulnasar.pricingexception.exception.ApiProblem;
import se.ataulnasar.pricingexception.exception.BusinessRuleException;
import se.ataulnasar.pricingexception.exception.ForbiddenActionException;
import se.ataulnasar.pricingexception.exception.InvalidTransitionException;
import se.ataulnasar.pricingexception.exception.ValidationException;
import se.ataulnasar.pricingexception.model.Actor;
import se.ataulnasar.pricingexception.model.ApplicationStatus;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestEventEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;

/**
 * The business rules of docs/invariants.yaml against {@link PricingExceptionService}. Plain JUnit and
 * Mockito (no Spring), fixed clock. Repositories are mocks; the entity under test is returned by
 * {@code requests.findById}.
 */
class PricingExceptionServiceTest {

    private static final String LONG = "x".repeat(501);
    private static final String NEW_REASON = "new reason";

    private Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness();
    }

    // ---------- helpers ----------

    private PricingExceptionResponse approve(PricingExceptionRequestEntity e, Actor by, String comment) {
        return h.service.decide(by, e.getId(), e.getVersion(), Decision.APPROVED, comment);
    }

    private PricingExceptionResponse decline(PricingExceptionRequestEntity e, Actor by, String comment) {
        return h.service.decide(by, e.getId(), e.getVersion(), Decision.DECLINED, comment);
    }

    private PricingExceptionResponse withdraw(PricingExceptionRequestEntity e, Actor by) {
        return h.service.withdraw(by, e.getId(), e.getVersion());
    }

    private PricingExceptionResponse supersede(PricingExceptionRequestEntity e, Actor by) {
        return h.service.supersede(by, e.getId(), e.getVersion(), 30, NEW_REASON);
    }

    private PricingExceptionRequestEntity savedCreate(Actor by, int discount, String reason) {
        h.service.create(by, APPLICATION_ID, discount, reason);
        ArgumentCaptor<PricingExceptionRequestEntity> c = ArgumentCaptor.forClass(PricingExceptionRequestEntity.class);
        verify(h.requests).saveAndFlush(c.capture());
        return c.getValue();
    }

    private static RequestEventEntity last(PricingExceptionRequestEntity r) {
        return r.getEvents().get(r.getEvents().size() - 1);
    }

    private static void assertForbidden(Runnable cmd, String code) {
        assertThatThrownBy(cmd::run).isInstanceOfSatisfying(ForbiddenActionException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private PricingExceptionRequestEntity stubbed(PricingExceptionRequestEntity e) {
        return h.stub(e);
    }

    // ---------- create (former submit) ----------

    @Test
    void create_creates_pending_request_with_given_data_and_one_submitted_event() {
        PricingExceptionResponse response = h.service.create(ANNA, APPLICATION_ID, 40, "why");

        ArgumentCaptor<PricingExceptionRequestEntity> c = ArgumentCaptor.forClass(PricingExceptionRequestEntity.class);
        verify(h.requests).saveAndFlush(c.capture());
        PricingExceptionRequestEntity r = c.getValue();

        assertThat(r.getId()).isNotNull().isEqualTo(response.id());
        assertThat(r.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(r.getRequestedBy()).isEqualTo("rm-anna");
        assertThat(r.getDiscountBps()).isEqualTo(40);
        assertThat(r.getReason()).isEqualTo("why");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getCreatedAt()).isEqualTo(NOW);
        assertThat(r.getSupersedesId()).isNull();
        assertThat(r.getDecidedBy()).isNull();
        assertThat(r.getDecidedAt()).isNull();
        assertThat(r.getApprovedDiscountBps()).isNull();
        assertThat(r.getActiveApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(r.getEvents()).hasSize(1);
        RequestEventEntity e = r.getEvents().get(0);
        assertThat(e.getType()).isEqualTo(EventType.SUBMITTED);
        assertThat(e.getActorId()).isEqualTo("rm-anna");
        assertThat(e.getOccurredAt()).isEqualTo(NOW);
        assertThat(e.getFromStatus()).isNull();
        assertThat(e.getToStatus()).isEqualTo(RequestStatus.PENDING);

        assertThat(response.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(response.discountBps()).isEqualTo(40);
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).type()).isEqualTo(EventType.SUBMITTED);
    }

    @Test
    void create_records_supersedes_id_when_a_request_is_superseded() {
        PricingExceptionRequestEntity old = stubbed(pending());

        PricingExceptionResponse replacement = h.service.supersede(ANNA, old.getId(), old.getVersion(), 30, NEW_REASON);

        assertThat(replacement.supersedesId()).isEqualTo(old.getId());
        assertThat(replacement.id()).isNotEqualTo(old.getId());
    }

    @Test
    void create_only_allowed_for_rm_role_even_when_the_id_looks_like_an_rm() {
        for (Actor a : new Actor[] {SARA, MORTGAGE, Actor.reviewer("rm-anna", 150)}) {
            assertForbidden(() -> h.service.create(a, APPLICATION_ID, 10, "r"), "WRONG_ROLE");
        }
        h.assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "  "})
    void create_rejects_a_blank_application_id(String blank) {
        // JUDGEMENT: a blank application id would break INV-04 uniqueness.
        assertThatThrownBy(() -> h.service.create(ANNA, blank, 10, "x")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> h.service.create(ANNA, null, 10, "x")).isInstanceOf(RuntimeException.class);
        h.assertNothingSaved();
    }

    @Test
    void INV06_create_is_rejected_when_the_application_is_not_open() {
        h.setApplicationStatus(ApplicationStatus.CLOSED);

        assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, 10, "x"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.APPLICATION_NOT_OPEN));
        h.assertNothingSaved();
    }

    @Test
    void INV04_create_is_rejected_when_an_active_request_exists_for_the_application() {
        when(h.requests.findByActiveApplicationId(APPLICATION_ID)).thenReturn(Optional.of(pending()));

        assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, 10, "x"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.ACTIVE_REQUEST_EXISTS));
        h.assertNothingSaved();
    }

    // ---------- INV-01 ----------

    @Test
    void INV01_approval_records_reviewed_discount() {
        PricingExceptionRequestEntity r = stubbed(pending(42));

        PricingExceptionResponse response = approve(r, JOHAN, "ok");

        assertThat(r.getApprovedDiscountBps()).isEqualTo(42);
        assertThat(r.getDiscountBps()).isEqualTo(42);
        assertThat(response.approvedDiscountBps()).isEqualTo(42);
        assertThat(response.discountBps()).isEqualTo(42);
    }

    @Test
    void INV01_declined_request_has_no_approved_discount() {
        PricingExceptionRequestEntity r = stubbed(pending(42));
        decline(r, JOHAN, null);
        assertThat(r.getApprovedDiscountBps()).isNull();
    }

    @Test
    void INV01_request_has_no_way_to_change_the_discount() {
        // Behaviour: no command changes the discount of the request it runs on.
        for (RequestStatus s : RequestStatus.values()) {
            for (BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> command : List.<BiConsumer<PricingExceptionService, PricingExceptionRequestEntity>>of(
                    (svc, e) -> svc.decide(JOHAN, e.getId(), e.getVersion(), Decision.APPROVED, "c"),
                    (svc, e) -> svc.decide(JOHAN, e.getId(), e.getVersion(), Decision.DECLINED, "c"),
                    (svc, e) -> svc.withdraw(ANNA, e.getId(), e.getVersion()),
                    (svc, e) -> svc.supersede(ANNA, e.getId(), e.getVersion(), 77, "another"))) {
                PricingExceptionRequestEntity e = stubbed(inStatus(s));
                try {
                    command.accept(h.service, e);
                } catch (BusinessRuleException expected) {
                    // rejected for a final status; the discount must be unchanged either way
                }
                assertThat(e.getDiscountBps()).as(s.name()).isEqualTo(40);
            }
        }

        // API: the only public method taking a request id together with a discount is supersede.
        Method[] methods = Arrays.stream(PricingExceptionService.class.getMethods())
                .filter(m -> m.getDeclaringClass() == PricingExceptionService.class)
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toArray(Method[]::new);
        assertThat(methods).noneMatch(m -> m.getName().startsWith("set") || m.getName().startsWith("update")
                || m.getName().startsWith("change") || m.getName().startsWith("edit"));
        assertThat(Arrays.stream(methods)
                .filter(m -> Arrays.asList(m.getParameterTypes()).contains(UUID.class))
                .filter(m -> Arrays.stream(m.getParameterTypes()).anyMatch(t -> t == int.class || t == Integer.class))
                .map(Method::getName))
                .containsExactly("supersede");

        // supersede creates a NEW entity with the new value; the old one keeps its discount.
        PricingExceptionRequestEntity old = stubbed(pending(40));
        PricingExceptionResponse created = h.service.supersede(ANNA, old.getId(), old.getVersion(), 77, "another");
        assertThat(old.getDiscountBps()).isEqualTo(40);
        assertThat(created.discountBps()).isEqualTo(77);
        assertThat(created.id()).isNotEqualTo(old.getId());
        ArgumentCaptor<PricingExceptionRequestEntity> saved = ArgumentCaptor.forClass(PricingExceptionRequestEntity.class);
        verify(h.requests, atLeastOnce()).saveAndFlush(saved.capture());
        PricingExceptionRequestEntity replacement = saved.getAllValues().stream()
                .filter(x -> x.getId().equals(created.id())).findFirst().orElseThrow();
        assertThat(replacement).isNotSameAs(old);
        assertThat(replacement.getDiscountBps()).isEqualTo(77);
        assertThat(replacement.getReason()).isEqualTo("another");
    }

    @Test
    void INV01_only_pending_request_can_be_superseded() {
        for (RequestStatus s : RequestStatus.values()) {
            PricingExceptionRequestEntity r = stubbed(inStatus(s));
            if (s == RequestStatus.PENDING) {
                supersede(r, ANNA);
                assertThat(r.getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
            } else {
                assertThatThrownBy(() -> supersede(r, ANNA)).as(s.name()).isInstanceOf(InvalidTransitionException.class);
            }
        }
    }

    @Test
    void INV01_supersede_marks_the_old_request_superseded_before_the_new_one_is_inserted() {
        PricingExceptionRequestEntity old = stubbed(pending());

        PricingExceptionResponse created = supersede(old, ANNA);

        assertThat(old.getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(old.getActiveApplicationId()).isNull();
        assertThat(old.getEvents()).extracting(RequestEventEntity::getType).containsExactly(EventType.SUBMITTED, EventType.SUPERSEDED);
        assertThat(created.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(created.events()).hasSize(1);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(h.requests);
        order.verify(h.requests).saveAndFlush(old);                                 // frees the unique slot first
        order.verify(h.requests).saveAndFlush(org.mockito.ArgumentMatchers.argThat(x -> x != old));
    }

    @Test
    void INV06_supersede_is_rejected_when_the_application_is_not_open_and_changes_nothing() {
        PricingExceptionRequestEntity old = stubbed(pending());
        h.setApplicationStatus(ApplicationStatus.CLOSED);

        assertThatThrownBy(() -> supersede(old, ANNA))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.APPLICATION_NOT_OPEN));
        assertThat(old.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(old.getEvents()).hasSize(1);
        h.assertNothingSaved();
    }

    // ---------- INV-03 ----------

    @Test
    void INV03_requester_cannot_approve_own_request() {
        Actor self = Actor.reviewer("rev-sara", 150); // same id as the requester, who acted as RM when submitting
        PricingExceptionRequestEntity r = stubbed(pendingBy("rev-sara", 10));

        assertForbidden(() -> approve(r, self, null), "SELF_REVIEW");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getEvents()).hasSize(1);
        h.assertNothingSaved();
    }

    @Test
    void INV03_requester_cannot_decline_own_request() {
        Actor self = Actor.reviewer("rev-sara", 150);
        PricingExceptionRequestEntity r = stubbed(pendingBy("rev-sara", 10));

        assertForbidden(() -> decline(r, self, null), "SELF_REVIEW");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getEvents()).hasSize(1);
        h.assertNothingSaved();
    }

    @Test
    void INV03_reviewer_cannot_approve_above_limit() {
        PricingExceptionRequestEntity r = stubbed(pending(51)); // Sara's limit is 50
        assertForbidden(() -> approve(r, SARA, null), "OVER_APPROVAL_LIMIT");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getApprovedDiscountBps()).isNull();
        assertThat(r.getEvents()).hasSize(1);
        assertThat(r.getActiveApplicationId()).isEqualTo(APPLICATION_ID);
        assertThatThrownBy(() -> approve(r, SARA, null)).hasMessageNotContaining("Long-standing customer");
        h.assertNothingSaved();
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
        PricingExceptionRequestEntity r = stubbed(pending(discount));
        if (approve) approve(r, reviewer, null); else decline(r, reviewer, "too high");
        assertThat(r.getStatus()).isEqualTo(expected);
    }

    // ---------- INV-02 ----------

    /** display, command, event type, new status, actor, comment, is the application slot still held. */
    static Stream<Arguments> commandsOnPending() {
        return Stream.of(
                cmd("approve", (s, r) -> s.decide(JOHAN, r.getId(), r.getVersion(), Decision.APPROVED, "fine"), EventType.APPROVED,
                        RequestStatus.APPROVED, "rev-johan", "fine", true),
                cmd("decline", (s, r) -> s.decide(JOHAN, r.getId(), r.getVersion(), Decision.DECLINED, "fine"), EventType.DECLINED,
                        RequestStatus.DECLINED, "rev-johan", "fine", false),
                cmd("withdraw", (s, r) -> s.withdraw(ANNA, r.getId(), r.getVersion()), EventType.WITHDRAWN,
                        RequestStatus.WITHDRAWN, "rm-anna", null, false),
                cmd("supersede", (s, r) -> s.supersede(ANNA, r.getId(), r.getVersion(), 30, NEW_REASON), EventType.SUPERSEDED,
                        RequestStatus.SUPERSEDED, "rm-anna", null, false));
    }

    private static Arguments cmd(String name, BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> c, EventType type,
                                 RequestStatus to, String actor, String comment, boolean active) {
        return Arguments.of(name, c, type, to, actor, comment, active);
    }

    @ParameterizedTest(name = "INV02_{0}_writes_one_event")
    @MethodSource("commandsOnPending")
    void INV02_each_command_writes_exactly_one_event(String name,
                                                     BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> command,
                                                     EventType type, RequestStatus to, String actor, String comment,
                                                     boolean active) {
        PricingExceptionRequestEntity r = stubbed(pending());
        h.clock.set(LATER);
        command.accept(h.service, r);

        assertThat(r.getStatus()).isEqualTo(to);
        assertThat(r.getActiveApplicationId()).isEqualTo(active ? APPLICATION_ID : null); // INV-04: only PENDING/APPROVED hold the slot
        assertThat(r.getEvents()).hasSize(2);
        RequestEventEntity e = last(r);
        assertThat(e.getType()).isEqualTo(type);
        assertThat(e.getActorId()).isEqualTo(actor);
        assertThat(e.getOccurredAt()).isEqualTo(LATER);
        assertThat(e.getFromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.getToStatus()).isEqualTo(to);
        assertThat(e.getComment()).isEqualTo(comment);
        verify(h.requests, atLeastOnce()).saveAndFlush(r); // same transaction: the change is flushed
    }

    @Test
    void decision_records_decider_time_and_comment() {
        for (boolean approve : new boolean[] {true, false}) {
            PricingExceptionRequestEntity r = stubbed(pending());
            h.clock.set(LATER);
            PricingExceptionResponse response = approve ? approve(r, JOHAN, "fine") : decline(r, JOHAN, "fine");
            assertThat(r.getDecidedBy()).isEqualTo("rev-johan");
            assertThat(r.getDecidedAt()).isEqualTo(LATER);
            assertThat(r.getDecisionComment()).isEqualTo("fine");
            assertThat(response.decidedBy()).isEqualTo("rev-johan");
            assertThat(response.decidedAt()).isEqualTo(LATER);
            assertThat(response.decisionComment()).isEqualTo("fine");
        }
    }

    @Test
    void INV02_event_chain_is_consistent_with_status_and_timestamps() {
        PricingExceptionRequestEntity r = stubbed(pending());
        h.clock.set(LATER);
        approve(r, JOHAN, "fine");
        List<RequestEventEntity> ev = r.getEvents();
        assertThat(ev).extracting(RequestEventEntity::getType).containsExactly(EventType.SUBMITTED, EventType.APPROVED);
        assertThat(ev.get(0).getOccurredAt()).isEqualTo(NOW);
        assertThat(ev.get(1).getOccurredAt()).isEqualTo(LATER).isEqualTo(r.getDecidedAt());
        assertThat(ev.get(1).getFromStatus()).isEqualTo(ev.get(0).getToStatus());
        assertThat(ev.get(1).getToStatus()).isEqualTo(r.getStatus());
    }

    @Test
    void INV02_events_view_is_read_only_and_an_earlier_view_is_a_snapshot() {
        // JUDGEMENT: a caller holding the response should not see its history grow silently.
        PricingExceptionRequestEntity r = stubbed(pending());
        PricingExceptionResponse held = h.service.get(r.getId());
        List<EventResponse> heldEvents = held.events();
        assertThatThrownBy(() -> held.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        approve(r, JOHAN, null);
        assertThat(heldEvents).hasSize(1);
        assertThat(held.events()).hasSize(1);
    }

    @Test
    void decision_comment_is_optional_and_500_chars_or_500_spaces_are_accepted() {
        for (String comment : new String[] {null, "c".repeat(500), " ".repeat(500)}) {
            PricingExceptionRequestEntity a = stubbed(pending());
            approve(a, JOHAN, comment);
            assertThat(a.getStatus()).isEqualTo(RequestStatus.APPROVED);
            assertThat(a.getDecisionComment()).isEqualTo(comment);
            PricingExceptionRequestEntity d = stubbed(pending());
            decline(d, SARA, comment);
            assertThat(d.getStatus()).isEqualTo(RequestStatus.DECLINED);
        }
    }

    // ---------- rejected commands leave the request untouched ----------

    /** One rejected call: the request, the command, and the error code it must give. */
    private record Rejected(String name, Supplier<PricingExceptionRequestEntity> request,
                            BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> command, String code) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Rejected rej(String name, Supplier<PricingExceptionRequestEntity> request,
                                BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> command, String code) {
        return new Rejected(name, request, command, code);
    }

    private static BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> approveBy(Actor a, String comment) {
        return (s, r) -> s.decide(a, r.getId(), r.getVersion(), Decision.APPROVED, comment);
    }

    private static BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> declineBy(Actor a, String comment) {
        return (s, r) -> s.decide(a, r.getId(), r.getVersion(), Decision.DECLINED, comment);
    }

    private static BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> withdrawBy(Actor a) {
        return (s, r) -> s.withdraw(a, r.getId(), r.getVersion());
    }

    private static BiConsumer<PricingExceptionService, PricingExceptionRequestEntity> supersedeBy(Actor a) {
        return (s, r) -> s.supersede(a, r.getId(), r.getVersion(), 30, NEW_REASON);
    }

    static Stream<Rejected> rejectedCommands() {
        Actor annaAsReviewer = Actor.reviewer("rm-anna", 150);
        Supplier<PricingExceptionRequestEntity> declined = () -> inStatus(RequestStatus.DECLINED);
        Supplier<PricingExceptionRequestEntity> pending = ServiceFixtures::pending;
        return Stream.of(
                rej("INV03 over limit 80 by sara (no partial decision)", () -> pending(80), approveBy(SARA, "no"), "OVER_APPROVAL_LIMIT"),
                rej("INV03 reviewer with limit zero cannot approve anything", () -> pending(1), approveBy(Actor.reviewer("rev-zero", 0), null), "OVER_APPROVAL_LIMIT"),
                rej("INV03 same id with reviewer role is self review on approve", pending, approveBy(annaAsReviewer, null), "SELF_REVIEW"),
                rej("INV03 same id with reviewer role is self review on decline", pending, declineBy(annaAsReviewer, null), "SELF_REVIEW"),
                rej("INV03 only reviewers decide: erik approve", pending, approveBy(ERIK, null), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: erik decline", pending, declineBy(ERIK, null), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: mortgage approve", pending, approveBy(MORTGAGE, null), "WRONG_ROLE"),
                rej("INV03 only reviewers decide: mortgage decline", pending, declineBy(MORTGAGE, null), "WRONG_ROLE"),
                rej("check order: self review beats over limit", () -> pending(100), approveBy(Actor.reviewer("rm-anna", 10), null), "SELF_REVIEW"),
                rej("check order: role beats self review (approve)", pending, approveBy(Actor.mortgageProcess("rm-anna"), null), "WRONG_ROLE"),
                rej("check order: role beats self review (decline)", pending, declineBy(Actor.rm("rev-sara"), null), "WRONG_ROLE"),
                rej("check order: status beats role (approve)", () -> inStatus(RequestStatus.WITHDRAWN), approveBy(MORTGAGE, null), "INVALID_TRANSITION"),
                rej("check order: status beats role (decline)", () -> inStatus(RequestStatus.WITHDRAWN), declineBy(ANNA, null), "INVALID_TRANSITION"),
                rej("withdraw by another RM is NOT_OWNER", pending, withdrawBy(ERIK), "NOT_OWNER"),
                rej("supersede by another RM is NOT_OWNER", pending, supersedeBy(ERIK), "NOT_OWNER"),
                rej("withdraw by a reviewer with the owner's id is WRONG_ROLE", pending, withdrawBy(annaAsReviewer), "WRONG_ROLE"),
                rej("withdraw by reviewer sara is WRONG_ROLE", pending, withdrawBy(SARA), "WRONG_ROLE"),
                rej("withdraw by reviewer johan is WRONG_ROLE", pending, withdrawBy(JOHAN), "WRONG_ROLE"),
                rej("withdraw by mortgage process is WRONG_ROLE", pending, withdrawBy(MORTGAGE), "WRONG_ROLE"),
                rej("supersede by reviewer sara is WRONG_ROLE", pending, supersedeBy(SARA), "WRONG_ROLE"),
                rej("supersede by reviewer johan is WRONG_ROLE", pending, supersedeBy(JOHAN), "WRONG_ROLE"),
                rej("supersede by mortgage process is WRONG_ROLE", pending, supersedeBy(MORTGAGE), "WRONG_ROLE"),
                rej("comment of 501 chars is rejected on approve", pending, approveBy(JOHAN, LONG), "INVALID_COMMENT"),
                rej("comment of 501 chars is rejected on decline", pending, declineBy(SARA, LONG), "INVALID_COMMENT"),
                // JUDGEMENT: status errors come before input validation, authorisation errors before input validation
                rej("invalid comment on a final request reports INVALID_TRANSITION first", declined, approveBy(JOHAN, LONG), "INVALID_TRANSITION"),
                rej("invalid comment with over limit reports OVER_APPROVAL_LIMIT first", () -> pending(80), approveBy(SARA, LONG), "OVER_APPROVAL_LIMIT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCommands")
    void rejected_command_leaves_the_request_untouched(Rejected c) {
        PricingExceptionRequestEntity r = stubbed(c.request().get());
        h.clock.set(LATER);
        assertRejectedAndUntouched(h, r, s -> c.command().accept(s, r), c.code());
    }

    @Test
    void INV03_after_a_failed_over_limit_approve_a_valid_approve_is_clean() {
        PricingExceptionRequestEntity r = stubbed(pending(80));
        h.clock.set(LATER);
        assertRejectedAndUntouched(h, r, s -> s.decide(SARA, r.getId(), r.getVersion(), Decision.APPROVED, "no"), "OVER_APPROVAL_LIMIT");
        approve(r, JOHAN, "ok");
        assertThat(r.getDecidedBy()).isEqualTo("rev-johan");
        assertThat(r.getDecisionComment()).isEqualTo("ok");
        assertThat(r.getEvents()).hasSize(2);
    }

    @Test
    void INV03_a_reviewer_with_limit_zero_may_still_decline() {
        PricingExceptionRequestEntity r = stubbed(pending(1));
        decline(r, Actor.reviewer("rev-zero", 0), null);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.DECLINED);
    }

    @Test
    void INV05_approved_request_keeps_original_decision_after_repeated_approve_and_decline() {
        PricingExceptionRequestEntity r = stubbed(pending(40));
        approve(r, JOHAN, "first"); // at NOW
        Actor other = Actor.reviewer("rev-other", 150);
        h.clock.set(LATER);
        long version = r.getVersion();
        org.mockito.Mockito.clearInvocations(h.requests);
        assertRejectedAndUntouched(h, r, s -> s.decide(other, r.getId(), version, Decision.APPROVED, "second"), "INVALID_TRANSITION");
        assertRejectedAndUntouched(h, r, s -> s.decide(other, r.getId(), version, Decision.DECLINED, "second"), "INVALID_TRANSITION");
        assertThat(r.getDecidedBy()).isEqualTo("rev-johan");
        assertThat(r.getDecisionComment()).isEqualTo("first");
        assertThat(r.getDecidedAt()).isEqualTo(NOW);
        assertThat(r.getApprovedDiscountBps()).isEqualTo(40);
    }

    @Test
    void INV05_a_decision_with_a_stale_version_is_rejected_and_changes_nothing() {
        PricingExceptionRequestEntity r = stubbed(pending());
        long stale = r.getVersion() + 1;
        Snapshot before = Snapshot.of(r);

        assertThatThrownBy(() -> h.service.decide(JOHAN, r.getId(), stale, Decision.APPROVED, null))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.VERSION_MISMATCH));
        assertThatThrownBy(() -> h.service.withdraw(ANNA, r.getId(), stale)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> h.service.supersede(ANNA, r.getId(), stale, 30, NEW_REASON)).isInstanceOf(ApiProblem.class);
        assertThat(Snapshot.of(r)).isEqualTo(before);
        h.assertNothingSaved();
    }

    @Test
    void INV06_approve_is_rejected_when_the_application_is_not_open_and_changes_nothing() {
        PricingExceptionRequestEntity r = stubbed(pending());
        h.setApplicationStatus(ApplicationStatus.CLOSED);
        Snapshot before = Snapshot.of(r);

        assertThatThrownBy(() -> approve(r, JOHAN, null))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.APPLICATION_NOT_OPEN));
        assertThat(Snapshot.of(r)).isEqualTo(before);
        h.assertNothingSaved();
    }

    @Test
    void INV06_decline_and_withdraw_do_not_need_an_open_application() {
        h.setApplicationStatus(ApplicationStatus.CLOSED);
        PricingExceptionRequestEntity d = stubbed(pending());
        PricingExceptionRequestEntity w = stubbed(pending());
        decline(d, JOHAN, null);
        withdraw(w, ANNA);
        assertThat(d.getStatus()).isEqualTo(RequestStatus.DECLINED);
        assertThat(w.getStatus()).isEqualTo(RequestStatus.WITHDRAWN);
    }

    // ---------- INV-06: the application row is read with a lock (no window for a concurrent close) ----------

    @Test
    void INV06_create_locks_the_application_row() {
        h.service.create(ANNA, APPLICATION_ID, 40, "why");

        verify(h.applications).findForUpdateById(APPLICATION_ID);
    }

    @Test
    void INV06_approve_locks_the_application_row() {
        PricingExceptionRequestEntity r = stubbed(pending());

        approve(r, JOHAN, null);

        verify(h.applications).findForUpdateById(APPLICATION_ID);
    }

    @Test
    void INV06_supersede_locks_the_application_row() {
        PricingExceptionRequestEntity r = stubbed(pending());

        supersede(r, ANNA);

        verify(h.applications).findForUpdateById(APPLICATION_ID);
    }

    @Test
    void INV06_decline_does_not_read_the_application() {
        PricingExceptionRequestEntity r = stubbed(pending());

        decline(r, JOHAN, null);

        verify(h.applications, never()).findForUpdateById(any());
        verify(h.applications, never()).findById(any());
    }

    @Test
    void INV06_approved_discount_lookup_does_not_lock() {
        PricingExceptionRequestEntity r = inStatus(RequestStatus.APPROVED);
        when(h.requests.findByApplicationIdAndStatus(APPLICATION_ID, RequestStatus.APPROVED)).thenReturn(List.of(r));

        h.service.approvedDiscount(MORTGAGE, APPLICATION_ID);

        verify(h.applications).findById(APPLICATION_ID);
        verify(h.applications, never()).findForUpdateById(any());
    }

    @Test
    void INV06_approve_rejected_when_the_locked_row_is_CLOSED() {
        // the plain read says OPEN (stale), the locked read says CLOSED: the locked read must decide
        PricingExceptionRequestEntity r = stubbed(pending());
        when(h.applications.findForUpdateById(any())).thenAnswer(inv -> Optional.of(
                new MortgageApplicationEntity(inv.getArgument(0), "CUST-1", 450, ApplicationStatus.CLOSED, "rm-anna")));

        Snapshot before = Snapshot.of(r);

        // (assertRejectedAndUntouched expects BusinessRuleException; APPLICATION_NOT_OPEN is an ApiProblem, so inline)
        assertThatThrownBy(() -> approve(r, JOHAN, null))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.APPLICATION_NOT_OPEN));
        assertThat(Snapshot.of(r)).isEqualTo(before);
        h.assertNothingSaved();
    }

    @Test
    void INV05_unknown_request_is_reported_as_not_found() {
        assertThatThrownBy(() -> h.service.get(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ApiProblem.REQUEST_NOT_FOUND));
    }

    // ---------- status x command matrix (INV-05, design.md "Request statuses") ----------

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
        void run(PricingExceptionService s, PricingExceptionRequestEntity r) {
            switch (this) {
                case APPROVE -> s.decide(JOHAN, r.getId(), r.getVersion(), Decision.APPROVED, null);
                case DECLINE -> s.decide(JOHAN, r.getId(), r.getVersion(), Decision.DECLINED, null);
                case WITHDRAW -> s.withdraw(ANNA, r.getId(), r.getVersion());
                case SUPERSEDE -> s.supersede(ANNA, r.getId(), r.getVersion(), 30, NEW_REASON);
            }
        }
    }

    static Stream<Arguments> allStatusAndCommand() {
        return Stream.of(RequestStatus.values()).flatMap(s -> Stream.of(Command.values()).map(c -> Arguments.of(s, c)));
    }

    @ParameterizedTest(name = "INV05_transition_table: {0} x {1}")
    @MethodSource("allStatusAndCommand")
    void INV05_transition_table(RequestStatus from, Command cmd) {
        PricingExceptionRequestEntity r = stubbed(inStatus(from));
        int eventsBefore = r.getEvents().size();

        if (from == RequestStatus.PENDING) {
            cmd.run(h.service, r);
            assertThat(r.getStatus()).isEqualTo(cmd.target);
            assertThat(r.getEvents()).hasSize(eventsBefore + 1);       // INV-02: exactly one event
            assertThat(r.getEvents().get(eventsBefore).getFromStatus()).isEqualTo(from);
            assertThat(r.getEvents().get(eventsBefore).getToStatus()).isEqualTo(cmd.target);
        } else {
            assertRejectedAndUntouched(h, r, s -> cmd.run(s, r), "INVALID_TRANSITION"); // status, events, decision fields, active id unchanged
            // and again: still final after the failed attempt
            assertThatThrownBy(() -> cmd.run(h.service, r)).isInstanceOf(InvalidTransitionException.class);
            assertThat(r.getStatus()).isEqualTo(from);
        }
    }

    static final Map<RequestStatus, Boolean> ACTIVE = Map.of(
            RequestStatus.PENDING, true, RequestStatus.APPROVED, true,
            RequestStatus.DECLINED, false, RequestStatus.WITHDRAWN, false,
            RequestStatus.SUPERSEDED, false);

    @ParameterizedTest(name = "activeApplicationId_matches_status: {0}")
    @EnumSource(RequestStatus.class)
    void activeApplicationId_is_set_only_while_pending_or_approved(RequestStatus status) {
        PricingExceptionRequestEntity r;
        if (status == RequestStatus.PENDING) {
            r = savedCreate(ANNA, 40, "why");
        } else {
            r = stubbed(pending());
            Command.values()[Arrays.stream(Command.values()).map(c -> c.target).toList().indexOf(status)].run(h.service, r);
        }
        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(r.getActiveApplicationId()).isEqualTo(ACTIVE.get(status) ? APPLICATION_ID : null);
    }

    // ---------- a request loaded from the repository (former ReconstituteTest) ----------

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void INV02_get_returns_exactly_the_stored_events_and_status(RequestStatus status) {
        PricingExceptionRequestEntity stored = stubbed(inStatus(status));

        PricingExceptionResponse r = h.service.get(stored.getId());

        assertThat(r.status()).isEqualTo(status);
        assertThat(r.events()).isEqualTo(stored.getEvents().stream().map(EventResponse::from).toList());
        assertThat(r.id()).isEqualTo(stored.getId());
        assertThat(r.applicationId()).isEqualTo(APPLICATION_ID);
        assertThat(r.requestedBy()).isEqualTo("rm-anna");
        assertThat(r.discountBps()).isEqualTo(40);
        assertThat(r.reason()).isEqualTo("Long-standing customer");
        assertThat(r.createdAt()).isEqualTo(NOW);
        assertThat(r.version()).isEqualTo(stored.getVersion());
    }

    @Test
    void INV01_loaded_approved_keeps_approved_discount_and_decision_fields() {
        PricingExceptionRequestEntity stored = inStatus(RequestStatus.APPROVED);
        stored.setDecisionComment("ok");
        PricingExceptionResponse r = h.service.get(stubbed(stored).getId());

        assertThat(r.approvedDiscountBps()).isEqualTo(40);
        assertThat(r.decidedBy()).isEqualTo("rev-johan");
        assertThat(r.decidedAt()).isEqualTo(NOW);
        assertThat(r.decisionComment()).isEqualTo("ok");
    }

    @Test
    void INV01_loaded_request_keeps_supersedesId_and_a_superseded_one_reports_its_replacement() {
        PricingExceptionRequestEntity replacement = pending();
        UUID old = UUID.randomUUID();
        replacement.setSupersedesId(old);
        assertThat(h.service.get(stubbed(replacement).getId()).supersedesId()).isEqualTo(old);

        PricingExceptionRequestEntity superseded = stubbed(inStatus(RequestStatus.SUPERSEDED));
        when(h.requests.findBySupersedesId(superseded.getId())).thenReturn(Optional.of(replacement));
        assertThat(h.service.get(superseded.getId()).supersededById()).isEqualTo(replacement.getId());
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void INV05_loaded_final_request_rejects_every_command_and_events_stay(RequestStatus status) {
        PricingExceptionRequestEntity r = stubbed(inStatus(status));
        List<RequestEventEntity> before = List.copyOf(r.getEvents());

        for (Command command : Command.values()) {
            assertThatThrownBy(() -> command.run(h.service, r)).isInstanceOfSatisfying(InvalidTransitionException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"));
        }
        assertThat(r.getEvents()).isEqualTo(before);
        assertThat(r.getStatus()).isEqualTo(status);
        h.assertNothingSaved();
    }

    @Test
    void INV02_loaded_pending_can_be_approved_and_gains_exactly_one_event() {
        PricingExceptionRequestEntity r = stubbed(pending());
        RequestEventEntity submitted = r.getEvents().get(0);
        h.clock.set(LATER);

        approve(r, JOHAN, "fine");

        assertThat(r.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.getEvents()).hasSize(2);
        assertThat(r.getEvents().get(0)).isSameAs(submitted);
        assertThat(r.getEvents().get(1).getType()).isEqualTo(EventType.APPROVED);
        assertThat(r.getEvents().get(1).getFromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getEvents().get(1).getToStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.getApprovedDiscountBps()).isEqualTo(40);
    }

    @Test
    void INV03_rules_still_apply_to_a_loaded_request_requester_cannot_approve() {
        PricingExceptionRequestEntity r = stubbed(pendingBy("rev-johan", 40));

        assertThatThrownBy(() -> approve(r, JOHAN, null)).isInstanceOf(BusinessRuleException.class);
        assertThat(r.getEvents()).hasSize(1);
        h.assertNothingSaved();
    }

    @Test
    void INV02_response_events_are_an_immutable_snapshot() {
        PricingExceptionRequestEntity r = stubbed(pending());
        PricingExceptionResponse response = h.service.get(r.getId());

        assertThatThrownBy(() -> response.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        r.getEvents().clear(); // changing the entity's list afterwards does not change the response
        assertThat(response.events()).hasSize(1);
    }

    // ---------- value checks (former ValueObjectTest) ----------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 99, 100})
    void discount_accepts_values_from_1_to_max(int v) {
        assertThat(h.service.create(ANNA, APPLICATION_ID, v, "r").discountBps()).isEqualTo(v);
    }

    @Test
    void discount_max_is_configurable() {
        Harness big = new Harness(200);
        assertThat(big.service.create(ANNA, APPLICATION_ID, 150, "r").discountBps()).isEqualTo(150);
        assertThat(big.service.create(ANNA, APPLICATION_ID, 200, "r").discountBps()).isEqualTo(200);
        assertThatThrownBy(() -> big.service.create(ANNA, APPLICATION_ID, 201, "r")).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
        Harness zero = new Harness(0); // max below one accepts nothing
        assertThatThrownBy(() -> zero.service.create(ANNA, APPLICATION_ID, 1, "r")).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_DISCOUNT"));
    }

    @Test
    void reason_accepts_1_and_500_characters_and_keeps_markup_as_plain_text() {
        for (String reason : new String[] {"x", "x".repeat(500), "a" + " ".repeat(499), "<script>alert(1)</script>"}) {
            Harness fresh = new Harness();
            PricingExceptionResponse response = fresh.service.create(ANNA, APPLICATION_ID, 10, reason);
            assertThat(response.reason()).isEqualTo(reason); // stored as given, never rewritten or escaped
            ArgumentCaptor<PricingExceptionRequestEntity> c = ArgumentCaptor.forClass(PricingExceptionRequestEntity.class);
            verify(fresh.requests).saveAndFlush(c.capture());
            assertThat(c.getValue().getReason()).isEqualTo(reason);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void create_rejects_discounts_outside_1_to_max_with_a_stable_code_and_message(int v) {
        assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, v, "r")).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.code()).isEqualTo("INVALID_DISCOUNT");
            assertThat(e.getMessage()).isEqualTo("discountBps must be between 1 and 100, was " + v);
        });
        h.assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void supersede_rejects_discounts_outside_1_to_max_and_changes_nothing(int v) {
        PricingExceptionRequestEntity old = stubbed(pending());
        Snapshot before = Snapshot.of(old);
        assertThatThrownBy(() -> h.service.supersede(ANNA, old.getId(), old.getVersion(), v, "r"))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_DISCOUNT");
                    assertThat(e.getMessage()).isEqualTo("discountBps must be between 1 and 100, was " + v);
                });
        assertThat(Snapshot.of(old)).isEqualTo(before);
        h.assertNothingSaved();
    }

    static List<String> blankReasons() {
        List<String> l = new ArrayList<>(List.of("", " ", "   ", "\t", "\n", " \t\n ",
                "　",               // ideographic space
                "　  ",
                " ",               // no-break space
                "  ",         // figure space, narrow no-break space
                "​",               // zero-width space (format character)
                "​‌‍",   // zero-width space, non-joiner, joiner
                "﻿",               // zero-width no-break space / BOM
                " ​　 "));
        l.add(null);
        return l;
    }

    @ParameterizedTest
    @MethodSource("blankReasons")
    void create_rejects_a_blank_reason(String blank) {
        assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, 10, blank)).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.code()).isEqualTo("INVALID_REASON");
            assertThat(e.getMessage()).isEqualTo("reason must not be blank");
        });
        h.assertNothingSaved();
    }

    @ParameterizedTest
    @MethodSource("blankReasons")
    void supersede_rejects_a_blank_reason_and_changes_nothing(String blank) {
        PricingExceptionRequestEntity old = stubbed(pending());
        Snapshot before = Snapshot.of(old);
        assertThatThrownBy(() -> h.service.supersede(ANNA, old.getId(), old.getVersion(), 10, blank))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
        assertThat(Snapshot.of(old)).isEqualTo(before);
        h.assertNothingSaved();
    }

    @Test
    void create_and_supersede_reject_a_reason_of_501_characters() {
        for (String reason : new String[] {LONG, "a" + " ".repeat(500)}) {
            assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, 10, reason)).isInstanceOfSatisfying(ValidationException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
            PricingExceptionRequestEntity old = stubbed(pending());
            assertThatThrownBy(() -> h.service.supersede(ANNA, old.getId(), old.getVersion(), 10, reason))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REASON"));
            assertThat(old.getStatus()).isEqualTo(RequestStatus.PENDING);
        }
        h.assertNothingSaved();
    }

    @Test
    void supersede_accepts_500_character_reason_and_markup_as_plain_text() {
        for (String reason : new String[] {"x".repeat(500), "<script>alert(1)</script>"}) {
            PricingExceptionRequestEntity old = stubbed(pending());
            assertThat(h.service.supersede(ANNA, old.getId(), old.getVersion(), 10, reason).reason()).isEqualTo(reason);
        }
    }

    @Test
    void exceptions_from_the_service_carry_a_stable_code_and_are_runtime_exceptions() {
        PricingExceptionRequestEntity declined = stubbed(inStatus(RequestStatus.DECLINED));
        assertThatThrownBy(() -> withdraw(declined, ANNA))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TRANSITION"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> h.service.create(ANNA, APPLICATION_ID, 10, " ")).isInstanceOf(RuntimeException.class);
    }
}
