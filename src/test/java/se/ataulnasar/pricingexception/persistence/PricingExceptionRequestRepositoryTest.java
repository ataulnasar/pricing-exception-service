package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.RequestEvent;
import se.ataulnasar.pricingexception.domain.RequestStatus;

/**
 * Seed rows relied on: users rm-anna, rm-erik, rev-johan (limit 150). No seeded application is used;
 * the tests insert their own applications MA-T001..MA-T006 and MA-A001..MA-A009 (plus the case and
 * trailing-space variants "ma-a001" and "MA-A001 "). Timestamps are stored with microsecond precision.
 */
@DataJpaTest
@Import(PricingExceptionRequestMapper.class)
class PricingExceptionRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Clock LATER = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);
    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor JOHAN = Actor.reviewer("rev-johan", 150);

    @Autowired PricingExceptionRequestRepository requests;
    @Autowired MortgageApplicationRepository applications;
    @Autowired PricingExceptionRequestMapper mapper;
    @Autowired TestEntityManager em;

    @BeforeEach
    void insertApplications() {
        for (String id : new String[] {"MA-T001", "MA-T002", "MA-T003", "MA-T004", "MA-T005", "MA-T006", "MA-A001", "MA-A002",
                "MA-A003", "MA-A004", "MA-A005", "MA-A006", "MA-A007", "MA-A008", "MA-A009", "ma-a001", "MA-A001 "}) {
            applications.save(new MortgageApplicationEntity(id, "CUST-" + id.trim(), 450, ApplicationStatus.OPEN, "rm-anna"));
        }
        applications.flush();
    }

    private static PricingExceptionRequest submit(String app, Reason reason, Clock clock, UUID supersedes) {
        return PricingExceptionRequest.submit(UUID.randomUUID(), app, ANNA, DiscountBps.of(40, 100), reason, supersedes, clock);
    }

    private static PricingExceptionRequest submit(String app) {
        return submit(app, new Reason("Long-standing customer"), CLOCK, null);
    }

    /** A new entity in the given status with the columns a real transition would set (no domain command). */
    private PricingExceptionRequestEntity entityIn(String app, RequestStatus status) {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit(app));
        e.setStatus(status);
        e.setActiveApplicationId(status.isActive() ? app : null);
        if (status == RequestStatus.APPROVED) e.setApprovedDiscountBps(e.getDiscountBps());
        if (status != RequestStatus.PENDING) {
            e.setDecidedBy("rev-johan");
            e.setDecidedAt(NOW);
        }
        return e;
    }

    private PricingExceptionRequest saveNew(PricingExceptionRequest r) {
        requests.saveAndFlush(mapper.toNewEntity(r));
        em.clear();
        return r;
    }

    private PricingExceptionRequest load(UUID id) {
        return mapper.toDomain(requests.findById(id).orElseThrow());
    }

    private long eventRows(UUID id) {
        return ((Number) em.getEntityManager().createNativeQuery("select count(*) from request_event where request_id = ?1")
                .setParameter(1, id).getSingleResult()).longValue();
    }

    private void persistChange(PricingExceptionRequest changed) {
        PricingExceptionRequestEntity e = requests.findById(changed.id()).orElseThrow();
        mapper.applyTo(changed, e);
        requests.saveAndFlush(e);
        em.clear();
    }

    /** Loads the aggregate, runs the command on it, writes it back, and returns the freshly reloaded entity. */
    private PricingExceptionRequestEntity change(UUID id, Consumer<PricingExceptionRequest> command) {
        PricingExceptionRequest aggregate = load(id);
        command.accept(aggregate);
        persistChange(aggregate);
        return requests.findById(id).orElseThrow();
    }

    @Test
    void persistence_saves_and_loads_request_with_its_submitted_event() {
        PricingExceptionRequest domain = saveNew(submit("MA-T001"));

        PricingExceptionRequestEntity e = requests.findById(domain.id()).orElseThrow();

        assertThat(e).extracting(PricingExceptionRequestEntity::getId, PricingExceptionRequestEntity::getApplicationId,
                        PricingExceptionRequestEntity::getRequestedBy, PricingExceptionRequestEntity::getDiscountBps,
                        PricingExceptionRequestEntity::getReason, PricingExceptionRequestEntity::getStatus,
                        PricingExceptionRequestEntity::getCreatedAt, PricingExceptionRequestEntity::getActiveApplicationId)
                .containsExactly(domain.id(), "MA-T001", "rm-anna", 40, "Long-standing customer", RequestStatus.PENDING, NOW, "MA-T001");
        assertThat(e).extracting(PricingExceptionRequestEntity::getSupersedesId, PricingExceptionRequestEntity::getDecidedBy,
                        PricingExceptionRequestEntity::getDecidedAt, PricingExceptionRequestEntity::getDecisionComment,
                        PricingExceptionRequestEntity::getApprovedDiscountBps).containsOnlyNulls();
        assertThat(e.getVersion()).isNotNull();
        assertThat(e.getEvents()).hasSize(1);
        RequestEventEntity ev = e.getEvents().get(0);
        assertThat(ev.getId()).isNotNull();
        assertThat(ev).extracting(RequestEventEntity::getType, RequestEventEntity::getActorId, RequestEventEntity::getOccurredAt,
                        RequestEventEntity::getFromStatus, RequestEventEntity::getToStatus, RequestEventEntity::getComment)
                .containsExactly(RequestEvent.Type.SUBMITTED, "rm-anna", NOW, null, RequestStatus.PENDING, null);
        // and the entity maps back to an equal aggregate
        assertThat(mapper.toDomain(e)).usingRecursiveComparison().isEqualTo(domain);
    }

    @Test
    void persistence_supersedesId_is_stored() {
        // The old request must exist first: supersedes_id is a foreign key to pricing_exception_request.id.
        PricingExceptionRequestEntity old = entityIn("MA-T001", RequestStatus.SUPERSEDED);
        old.getEvents().add(new RequestEventEntity(RequestEvent.Type.SUPERSEDED, "rm-anna", NOW, RequestStatus.PENDING,
                RequestStatus.SUPERSEDED, null));
        requests.saveAndFlush(old);

        PricingExceptionRequest domain = saveNew(submit("MA-T001", new Reason("Better offer elsewhere"), CLOCK, old.getId()));

        assertThat(requests.findById(domain.id()).orElseThrow().getSupersedesId()).isEqualTo(old.getId());
        assertThat(requests.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
    }

    @Test
    void INV02_round_trip_approve_writes_exactly_one_new_event_and_bumps_version() {
        PricingExceptionRequest domain = submit("MA-T001");
        Long firstVersion = requests.saveAndFlush(mapper.toNewEntity(domain)).getVersion();
        em.clear();

        PricingExceptionRequestEntity reloaded = change(domain.id(), a -> a.approve(JOHAN, "ok", CLOCK));

        assertThat(reloaded).extracting(PricingExceptionRequestEntity::getStatus, PricingExceptionRequestEntity::getDecidedBy,
                        PricingExceptionRequestEntity::getDecidedAt, PricingExceptionRequestEntity::getDecisionComment,
                        PricingExceptionRequestEntity::getApprovedDiscountBps, PricingExceptionRequestEntity::getActiveApplicationId)
                .containsExactly(RequestStatus.APPROVED, "rev-johan", NOW, "ok", 40, "MA-T001");
        assertThat(reloaded.getEvents()).extracting(RequestEventEntity::getType)
                .containsExactly(RequestEvent.Type.SUBMITTED, RequestEvent.Type.APPROVED);
        assertThat(reloaded.getEvents().get(1)).extracting(RequestEventEntity::getActorId, RequestEventEntity::getFromStatus,
                        RequestEventEntity::getToStatus, RequestEventEntity::getComment)
                .containsExactly("rev-johan", RequestStatus.PENDING, RequestStatus.APPROVED, "ok");
        assertThat(reloaded.getVersion()).isEqualTo(firstVersion + 1);
    }

    @Test
    void INV04_decline_clears_activeApplicationId_through_applyTo() {
        PricingExceptionRequest domain = saveNew(submit("MA-T001"));

        PricingExceptionRequestEntity reloaded = change(domain.id(), a -> a.decline(JOHAN, "no", CLOCK));

        assertThat(reloaded.getStatus()).isEqualTo(RequestStatus.DECLINED);
        assertThat(reloaded.getActiveApplicationId()).isNull();
        assertThat(reloaded.getApprovedDiscountBps()).isNull();
        assertThat(reloaded.getEvents()).hasSize(2);
    }

    @Test
    void INV01_superseded_request_with_supersedesId_and_all_statuses_round_trip_without_loss() {
        PricingExceptionRequest old = saveNew(submit("MA-A001"));
        PricingExceptionRequest wd = saveNew(submit("MA-A002"));
        PricingExceptionRequest ap = saveNew(submit("MA-A003"));
        PricingExceptionRequest dc = saveNew(submit("MA-A004"));

        List<PricingExceptionRequest> expected = new java.util.ArrayList<>();
        for (Object[] step : new Object[][] {{old, "supersede"}, {wd, "withdraw"}, {ap, "approve"}, {dc, "decline"}}) {
            PricingExceptionRequest r = load(((PricingExceptionRequest) step[0]).id());
            switch ((String) step[1]) {
                case "supersede" -> r.markSuperseded(ANNA, LATER);
                case "withdraw" -> r.withdraw(ANNA, LATER);
                case "approve" -> r.approve(JOHAN, "ok", LATER);
                default -> r.decline(JOHAN, "no", LATER);
            }
            persistChange(r);
            expected.add(r);
        }
        expected.add(saveNew(submit("MA-A001", new Reason("new"), LATER, old.id())));

        for (PricingExceptionRequest e : expected) {
            assertThat(load(e.id())).usingRecursiveComparison().isEqualTo(e);
        }
        assertThat(load(old.id()).status()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(load(expected.get(4).id()).supersedesId()).isEqualTo(old.id());
    }

    @Test
    void INV04_second_active_request_is_rejected() {
        requests.saveAndFlush(entityIn("MA-T001", RequestStatus.PENDING));

        assertThatThrownBy(() -> requests.saveAndFlush(entityIn("MA-T001", RequestStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV04_pending_is_rejected_when_an_approved_request_exists() {
        requests.saveAndFlush(entityIn("MA-T002", RequestStatus.APPROVED));

        assertThatThrownBy(() -> requests.saveAndFlush(entityIn("MA-T002", RequestStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV04_active_requests_of_different_applications_coexist_and_a_new_pending_is_allowed_after_a_decline() {
        requests.saveAndFlush(entityIn("MA-T001", RequestStatus.PENDING));
        requests.saveAndFlush(entityIn("MA-T002", RequestStatus.PENDING));
        assertThat(requests.findByActiveApplicationId("MA-T001")).isPresent();
        assertThat(requests.findByActiveApplicationId("MA-T002")).isPresent();

        requests.saveAndFlush(entityIn("MA-T003", RequestStatus.DECLINED));
        requests.saveAndFlush(entityIn("MA-T003", RequestStatus.PENDING));
        assertThat(requests.findByApplicationId("MA-T003")).hasSize(2);
        assertThat(requests.findByActiveApplicationId("MA-T003")).isPresent();
    }

    @Test
    void INV04_many_inactive_requests_and_one_active_request_can_share_an_application() {
        for (RequestStatus s : new RequestStatus[] {RequestStatus.DECLINED, RequestStatus.WITHDRAWN, RequestStatus.SUPERSEDED, RequestStatus.DECLINED}) {
            requests.saveAndFlush(entityIn("MA-T004", s));
        }
        assertThat(requests.findByApplicationId("MA-T004")).hasSize(4);
        assertThat(requests.findByActiveApplicationId("MA-T004")).isEmpty();

        PricingExceptionRequestEntity active = requests.saveAndFlush(entityIn("MA-T004", RequestStatus.PENDING));
        assertThat(requests.findByActiveApplicationId("MA-T004")).get().extracting(PricingExceptionRequestEntity::getId)
                .isEqualTo(active.getId());

        // the same through domain commands (withdraw, decline, supersede), then a second active request is rejected
        for (int i = 0; i < 3; i++) {
            PricingExceptionRequest r = load(saveNew(submit("MA-A001")).id());
            if (i == 0) r.withdraw(ANNA, LATER); else if (i == 1) r.decline(JOHAN, "no", LATER); else r.markSuperseded(ANNA, LATER);
            persistChange(r);
        }
        PricingExceptionRequest current = saveNew(submit("MA-A001"));
        assertThat(requests.findByApplicationId("MA-A001")).hasSize(4);
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getId()).isEqualTo(current.id());
        assertThatThrownBy(() -> requests.saveAndFlush(mapper.toNewEntity(submit("MA-A001"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV04_application_ids_that_differ_in_case_or_trailing_space_are_different_applications() {
        // Design: ids are exact. "MA-A001", "ma-a001" and "MA-A001 " are three applications.
        saveNew(submit("MA-A001"));
        saveNew(submit("ma-a001"));
        saveNew(submit("MA-A001 "));
        for (String id : new String[] {"MA-A001", "ma-a001", "MA-A001 "}) {
            assertThat(requests.findByActiveApplicationId(id).orElseThrow().getApplicationId()).isEqualTo(id);
        }
    }

    @Test
    void INV01_supersede_works_when_the_old_request_is_flushed_before_the_replacement_is_inserted() {
        // Documented approach (application service): mark the old request SUPERSEDED and flush before inserting
        // the new one. One single flush for both would hit ux_per_active_application (INSERTs run before UPDATEs).
        PricingExceptionRequest old = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity oldEntity = requests.findById(old.id()).orElseThrow();
        PricingExceptionRequest oldDomain = mapper.toDomain(oldEntity);
        oldDomain.markSuperseded(ANNA, LATER);
        mapper.applyTo(oldDomain, oldEntity);
        requests.saveAndFlush(oldEntity);
        PricingExceptionRequest replacement = submit("MA-A001", new Reason("new"), LATER, old.id());
        requests.saveAndFlush(mapper.toNewEntity(replacement));
        em.clear();
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getId()).isEqualTo(replacement.id());
        assertThat(load(old.id()).status()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(load(old.id()).events()).hasSize(2);
    }

    /** display, application, supersedes id, change applied to the new entity. */
    static Stream<Arguments> badRows() {
        Consumer<PricingExceptionRequestEntity> none = e -> { };
        return Stream.of(
                Arguments.of("foreign key rejects an unknown application", "MA-DOES-NOT-EXIST", null, none),
                Arguments.of("foreign key rejects an unknown requester", "MA-T001", null, (Consumer<PricingExceptionRequestEntity>) e -> e.setRequestedBy("nobody")),
                Arguments.of("INV01 supersedes_id must reference an existing request", "MA-A001", UUID.randomUUID(), none),
                Arguments.of("INV01 a discount of zero is rejected by the check", "MA-A001", null, (Consumer<PricingExceptionRequestEntity>) e -> e.setDiscountBps(0)),
                Arguments.of("INV01 a reason of 501 chars is rejected", "MA-A001", null, (Consumer<PricingExceptionRequestEntity>) e -> e.setReason("x".repeat(501))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badRows")
    void database_rejects_bad_rows(String display, String app, UUID supersedes, Consumer<PricingExceptionRequestEntity> change) {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit(app, new Reason("x"), CLOCK, supersedes));
        change.accept(e);
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV03_decided_by_must_be_an_existing_user() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        e.setStatus(RequestStatus.DECLINED);
        e.setActiveApplicationId(null);
        e.setDecidedBy("rev-nobody");
        e.setDecidedAt(NOW);
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV02_each_command_adds_exactly_one_event_row_and_old_rows_keep_their_ids() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        Long firstId = requests.findById(r.id()).orElseThrow().getEvents().get(0).getId();
        em.clear();
        List<RequestEventEntity> rows = change(r.id(), a -> a.decline(JOHAN, "no", LATER)).getEvents();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getId()).isEqualTo(firstId);
        assertThat(rows.get(1).getType()).isEqualTo(RequestEvent.Type.DECLINED);
    }

    @Test
    void INV02_applyTo_called_twice_appends_each_event_only_once() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest loaded = load(r.id());
        loaded.withdraw(ANNA, LATER);
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        mapper.applyTo(loaded, e);
        mapper.applyTo(loaded, e);
        requests.saveAndFlush(e);
        assertThat(e.getEvents()).hasSize(2);
        em.clear();
        assertThat(eventRows(r.id())).isEqualTo(2);
    }

    @Test
    void INV02_applyTo_rejects_an_entity_that_already_has_more_events_than_the_aggregate() {
        // JUDGEMENT: the entity is ahead of the aggregate (stale aggregate); the mapper must detect it.
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest stale = load(r.id());
        PricingExceptionRequest fresh = load(r.id());
        fresh.decline(JOHAN, "no", LATER);
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        mapper.applyTo(fresh, e);
        requests.flush();
        assertThatThrownBy(() -> mapper.applyTo(stale, e)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void INV01_applyTo_rejects_an_aggregate_with_a_different_id_than_the_entity() {
        // JUDGEMENT: copying request A onto the row of request B corrupts B.
        PricingExceptionRequest a = saveNew(submit("MA-A001"));
        PricingExceptionRequest b = saveNew(submit("MA-A002"));
        PricingExceptionRequestEntity eb = requests.findById(b.id()).orElseThrow();
        assertThatThrownBy(() -> mapper.applyTo(a, eb)).isInstanceOf(RuntimeException.class);
    }

    /** display, change applied to a fresh PENDING entity. */
    static Stream<Arguments> inconsistentRows() {
        return Stream.of(
                Arguments.of("INV01 approvedDiscount different from discount", (Consumer<PricingExceptionRequestEntity>) e -> {
                    e.setStatus(RequestStatus.APPROVED);
                    e.setDecidedBy("rev-johan");
                    e.setDecidedAt(NOW);
                    e.setApprovedDiscountBps(e.getDiscountBps() + 10);
                }),
                Arguments.of("INV01 first event is not SUBMITTED", (Consumer<PricingExceptionRequestEntity>) e ->
                        e.getEvents().add(0, new RequestEventEntity(RequestEvent.Type.WITHDRAWN, "rm-anna", NOW,
                                RequestStatus.PENDING, RequestStatus.WITHDRAWN, null))),
                // JUDGEMENT: nothing in the DB ties active_application_id to status. A hand-edited WITHDRAWN row with
                // active_application_id set would block the application forever and go unnoticed.
                Arguments.of("INV04 final status but activeApplicationId still set", (Consumer<PricingExceptionRequestEntity>) e -> {
                    e.setStatus(RequestStatus.WITHDRAWN);
                    e.setDecidedBy("rev-johan");
                    e.setDecidedAt(NOW);
                }));
    }

    @ParameterizedTest(name = "toDomain rejects a row with {0}")
    @MethodSource("inconsistentRows")
    void toDomain_rejects_inconsistent_rows(String display, Consumer<PricingExceptionRequestEntity> change) {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-A001"));
        change.accept(e);
        assertThatThrownBy(() -> mapper.toDomain(e)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV02_history_survives_a_backwards_clock_step_between_two_commands() {
        // JUDGEMENT: if a later command carries an earlier timestamp (clock step back, two nodes) the history must
        // still reload in insertion order, not become unreadable.
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest l = load(r.id());
        l.withdraw(ANNA, Clock.fixed(NOW.minusSeconds(5), ZoneOffset.UTC));
        persistChange(l);
        assertThat(load(r.id()).events()).extracting(RequestEvent::type)
                .containsExactly(RequestEvent.Type.SUBMITTED, RequestEvent.Type.WITHDRAWN);
    }

    @Test
    void INV02_events_with_identical_timestamps_reload_in_insertion_order_and_carry_request_id() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest l = load(r.id());
        l.withdraw(ANNA, CLOCK); // same instant as SUBMITTED
        persistChange(l);
        PricingExceptionRequest again = load(r.id());
        assertThat(again.events()).extracting(RequestEvent::type)
                .containsExactly(RequestEvent.Type.SUBMITTED, RequestEvent.Type.WITHDRAWN);
        assertThat(again.events()).allSatisfy(ev -> assertThat(ev.requestId()).isEqualTo(r.id()));
        assertThat(eventRows(r.id())).isEqualTo(2);
    }

    @Test
    void INV01_reason_and_comment_of_exactly_500_chars_survive_a_round_trip() {
        String[] texts = {"a".repeat(500), "\u00e5\u00e4\u00f6".repeat(166) + "\u00e5\u00e4", "\uD83D\uDE00".repeat(250)}; // 500 UTF-16 units each
        for (int i = 0; i < texts.length; i++) {
            String text = texts[i];
            PricingExceptionRequest r = saveNew(submit("MA-A00" + (1 + i), new Reason(text), CLOCK, null));
            PricingExceptionRequest l = load(r.id());
            l.decline(JOHAN, text, LATER);
            persistChange(l);
            PricingExceptionRequest got = load(r.id());
            assertThat(got.reason().value()).isEqualTo(text);
            assertThat(got.decisionComment()).isEqualTo(text);
            assertThat(got.events().get(1).comment()).isEqualTo(text);
        }
    }

    @Test
    void INV01_markup_quotes_and_microsecond_instants_round_trip_exactly() {
        String text = "<script>alert('x')</script> \"q\" ; DROP TABLE app_user; --";
        assertThat(load(saveNew(submit("MA-A001", new Reason(text), CLOCK, null)).id()).reason().value()).isEqualTo(text);

        Instant micros = Instant.parse("2026-01-15T10:00:00.123456Z");
        PricingExceptionRequest got = load(saveNew(submit("MA-A002", new Reason("x"), Clock.fixed(micros, ZoneOffset.UTC), null)).id());
        assertThat(got.createdAt()).isEqualTo(micros);
        assertThat(got.events().get(0).occurredAt()).isEqualTo(micros);
    }

    @Test
    void INV05_second_save_of_a_stale_detached_copy_fails_with_optimistic_lock_error() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity copy1 = requests.findById(r.id()).orElseThrow();
        copy1.getEvents().size();
        em.detach(copy1);
        PricingExceptionRequestEntity copy2 = requests.findById(r.id()).orElseThrow();
        copy2.getEvents().size();
        em.detach(copy2);
        assertThat(copy1).isNotSameAs(copy2);

        PricingExceptionRequest d1 = mapper.toDomain(copy1);
        d1.withdraw(ANNA, LATER);
        mapper.applyTo(d1, copy1);
        requests.saveAndFlush(copy1);

        PricingExceptionRequest d2 = mapper.toDomain(copy2);
        d2.decline(JOHAN, "no", LATER);
        mapper.applyTo(d2, copy2);
        assertThatThrownBy(() -> requests.saveAndFlush(copy2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void INV05_version_goes_up_by_one_per_command_and_not_at_all_for_a_no_op_applyTo() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        Long v0 = requests.findById(r.id()).orElseThrow().getVersion();
        em.clear();
        PricingExceptionRequestEntity e = change(r.id(), a -> a.approve(JOHAN, "ok", LATER)); // status, decision, active id, event
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
        mapper.applyTo(mapper.toDomain(e), e);
        requests.saveAndFlush(e);
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
    }

    @Test
    void INV02_request_repository_exposes_no_delete_or_remove_method() {
        // request_event is insert only; the repository must not offer any way to delete requests.
        assertThat(PricingExceptionRequestRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .noneMatch(n -> n.startsWith("delete") || n.startsWith("remove"));
    }

    @Test
    void INV02_removing_events_from_the_entity_list_does_not_delete_history_rows() {
        // JUDGEMENT: orphanRemoval = true turns list.clear() + flush into DELETE FROM request_event.
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        try {
            e.getEvents().clear();
            requests.flush();
        } catch (RuntimeException ignored) {
            // rejecting the change is also acceptable
        }
        em.clear();
        assertThat(eventRows(r.id())).isEqualTo(1);
    }

    @Test
    void persistence_findByStatus_filters() {
        PricingExceptionRequestEntity w = requests.saveAndFlush(entityIn("MA-T006", RequestStatus.WITHDRAWN));

        assertThat(requests.findByStatus(RequestStatus.WITHDRAWN)).extracting(PricingExceptionRequestEntity::getId).contains(w.getId());
        assertThat(requests.findByStatus(RequestStatus.PENDING)).extracting(PricingExceptionRequestEntity::getId).doesNotContain(w.getId());
    }

    @Test
    void findByActiveApplicationId_returns_empty_for_unknown_blank_null_and_inactive_applications() {
        // JUDGEMENT: Spring Data turns a null argument into "IS NULL", which would match every inactive row.
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        change(r.id(), a -> a.withdraw(ANNA, LATER));
        assertThat(requests.findByActiveApplicationId("MA-A001")).isEmpty();
        assertThat(requests.findByActiveApplicationId("MA-DOES-NOT-EXIST")).isEmpty();
        assertThat(requests.findByActiveApplicationId("")).isEmpty();
        assertThat(requests.findByActiveApplicationId(null)).isEmpty();
    }

    @Test
    void seed_all_seeded_requests_reload_into_valid_aggregates_with_consistent_history() {
        for (String id : new String[] {"00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003"}) {
            PricingExceptionRequest r = load(UUID.fromString(id));
            assertThat(r.events().get(r.events().size() - 1).toStatus()).isEqualTo(r.status());
            assertThat(r.events().size()).isEqualTo(r.status() == RequestStatus.PENDING ? 1 : 2);
            assertThat(r.activeApplicationId() != null).isEqualTo(r.status().isActive());
        }
    }
}
