package se.ataulnasar.pricingexception.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import se.ataulnasar.pricingexception.model.ApplicationStatus;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestEventEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;

/**
 * Seed rows relied on: users rm-anna, rm-erik, rev-johan (limit 150). No seeded application is used;
 * the tests insert their own applications MA-T001..MA-T006 and MA-A001..MA-A009 (plus the case and
 * trailing-space variants "ma-a001" and "MA-A001 "). Timestamps are stored with microsecond precision.
 *
 * <p>There is no mapper any more. A "command" here sets the same columns the service's
 * {@code transition} / {@code recordDecision} set (status, activeApplicationId, decidedBy/At/comment,
 * approvedDiscountBps) and appends exactly one {@link RequestEventEntity}.
 */
@DataJpaTest
class PricingExceptionRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    @Autowired PricingExceptionRequestRepository requests;
    @Autowired MortgageApplicationRepository applications;
    @Autowired TestEntityManager em;

    @BeforeEach
    void insertApplications() {
        for (String id : new String[] {"MA-T001", "MA-T002", "MA-T003", "MA-T004", "MA-T005", "MA-T006", "MA-A001", "MA-A002",
                "MA-A003", "MA-A004", "MA-A005", "MA-A006", "MA-A007", "MA-A008", "MA-A009", "ma-a001", "MA-A001 "}) {
            applications.save(new MortgageApplicationEntity(id, "CUST-" + id.trim(), 450, ApplicationStatus.OPEN, "rm-anna"));
        }
        applications.flush();
    }

    /** What the service's newRequest builds: PENDING, slot held, one SUBMITTED event. */
    private static PricingExceptionRequestEntity submit(String app, String reason, Instant at, UUID supersedes) {
        PricingExceptionRequestEntity e = new PricingExceptionRequestEntity();
        e.setId(UUID.randomUUID());
        e.setApplicationId(app);
        e.setRequestedBy("rm-anna");
        e.setDiscountBps(40);
        e.setReason(reason);
        e.setSupersedesId(supersedes);
        e.setCreatedAt(at);
        e.setStatus(RequestStatus.PENDING);
        e.setActiveApplicationId(app);
        e.getEvents().add(new RequestEventEntity(EventType.SUBMITTED, "rm-anna", at, null, RequestStatus.PENDING, null));
        return e;
    }

    private static PricingExceptionRequestEntity submit(String app) {
        return submit(app, "Long-standing customer", NOW, null);
    }

    /** Same columns as the service's transition(): status, active slot, one event. */
    private static void move(PricingExceptionRequestEntity e, RequestStatus to, EventType type, String actor, Instant at, String comment) {
        RequestStatus from = e.getStatus();
        e.setStatus(to);
        e.setActiveApplicationId(to.isActive() ? e.getApplicationId() : null);
        e.getEvents().add(new RequestEventEntity(type, actor, at, from, to, comment));
    }

    /** Same columns as the service's recordDecision(): decidedBy/At/comment plus transition. */
    private static void decide(PricingExceptionRequestEntity e, boolean approve, String comment, Instant at) {
        if (approve) e.setApprovedDiscountBps(e.getDiscountBps());
        e.setDecidedBy("rev-johan");
        e.setDecidedAt(at);
        e.setDecisionComment(comment);
        move(e, approve ? RequestStatus.APPROVED : RequestStatus.DECLINED, approve ? EventType.APPROVED : EventType.DECLINED,
                "rev-johan", at, comment);
    }

    private static void withdraw(PricingExceptionRequestEntity e, Instant at) {
        move(e, RequestStatus.WITHDRAWN, EventType.WITHDRAWN, "rm-anna", at, null);
    }

    private static void supersede(PricingExceptionRequestEntity e, Instant at) {
        move(e, RequestStatus.SUPERSEDED, EventType.SUPERSEDED, "rm-anna", at, null);
    }

    /** A new entity in the given status with the columns a real transition would set. */
    private PricingExceptionRequestEntity entityIn(String app, RequestStatus status) {
        PricingExceptionRequestEntity e = submit(app);
        e.setStatus(status);
        e.setActiveApplicationId(status.isActive() ? app : null);
        if (status == RequestStatus.APPROVED) e.setApprovedDiscountBps(e.getDiscountBps());
        if (status != RequestStatus.PENDING) {
            e.setDecidedBy("rev-johan");
            e.setDecidedAt(NOW);
        }
        return e;
    }

    private PricingExceptionRequestEntity saveNew(PricingExceptionRequestEntity e) {
        requests.saveAndFlush(e);
        em.clear();
        return e;
    }

    private PricingExceptionRequestEntity load(UUID id) {
        return requests.findById(id).orElseThrow();
    }

    private long eventRows(UUID id) {
        return ((Number) em.getEntityManager().createNativeQuery("select count(*) from request_event where request_id = ?1")
                .setParameter(1, id).getSingleResult()).longValue();
    }

    /** Loads the entity, applies the change, writes it back, and returns the freshly reloaded entity. */
    private PricingExceptionRequestEntity change(UUID id, Consumer<PricingExceptionRequestEntity> command) {
        PricingExceptionRequestEntity e = load(id);
        command.accept(e);
        requests.saveAndFlush(e);
        em.clear();
        return load(id);
    }

    @Test
    void persistence_saves_and_loads_request_with_its_submitted_event() {
        PricingExceptionRequestEntity saved = saveNew(submit("MA-T001"));

        PricingExceptionRequestEntity e = load(saved.getId());

        assertThat(e).extracting(PricingExceptionRequestEntity::getId, PricingExceptionRequestEntity::getApplicationId,
                        PricingExceptionRequestEntity::getRequestedBy, PricingExceptionRequestEntity::getDiscountBps,
                        PricingExceptionRequestEntity::getReason, PricingExceptionRequestEntity::getStatus,
                        PricingExceptionRequestEntity::getCreatedAt, PricingExceptionRequestEntity::getActiveApplicationId)
                .containsExactly(saved.getId(), "MA-T001", "rm-anna", 40, "Long-standing customer", RequestStatus.PENDING, NOW, "MA-T001");
        assertThat(e).extracting(PricingExceptionRequestEntity::getSupersedesId, PricingExceptionRequestEntity::getDecidedBy,
                        PricingExceptionRequestEntity::getDecidedAt, PricingExceptionRequestEntity::getDecisionComment,
                        PricingExceptionRequestEntity::getApprovedDiscountBps).containsOnlyNulls();
        assertThat(e.getVersion()).isNotNull();
        assertThat(e.getEvents()).hasSize(1);
        RequestEventEntity ev = e.getEvents().get(0);
        assertThat(ev.getId()).isNotNull();
        assertThat(ev).extracting(RequestEventEntity::getType, RequestEventEntity::getActorId, RequestEventEntity::getOccurredAt,
                        RequestEventEntity::getFromStatus, RequestEventEntity::getToStatus, RequestEventEntity::getComment)
                .containsExactly(EventType.SUBMITTED, "rm-anna", NOW, null, RequestStatus.PENDING, null);
    }

    @Test
    void persistence_supersedesId_is_stored() {
        // The old request must exist first: supersedes_id is a foreign key to pricing_exception_request.id.
        PricingExceptionRequestEntity old = entityIn("MA-T001", RequestStatus.SUPERSEDED);
        old.getEvents().add(new RequestEventEntity(EventType.SUPERSEDED, "rm-anna", NOW, RequestStatus.PENDING,
                RequestStatus.SUPERSEDED, null));
        requests.saveAndFlush(old);

        PricingExceptionRequestEntity next = saveNew(submit("MA-T001", "Better offer elsewhere", NOW, old.getId()));

        assertThat(load(next.getId()).getSupersedesId()).isEqualTo(old.getId());
        assertThat(load(old.getId()).getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
    }

    @Test
    void INV02_round_trip_approve_writes_exactly_one_new_event_and_bumps_version() {
        PricingExceptionRequestEntity entity = submit("MA-T001");
        Long firstVersion = requests.saveAndFlush(entity).getVersion();
        em.clear();

        PricingExceptionRequestEntity reloaded = change(entity.getId(), a -> decide(a, true, "ok", NOW));

        assertThat(reloaded).extracting(PricingExceptionRequestEntity::getStatus, PricingExceptionRequestEntity::getDecidedBy,
                        PricingExceptionRequestEntity::getDecidedAt, PricingExceptionRequestEntity::getDecisionComment,
                        PricingExceptionRequestEntity::getApprovedDiscountBps, PricingExceptionRequestEntity::getActiveApplicationId)
                .containsExactly(RequestStatus.APPROVED, "rev-johan", NOW, "ok", 40, "MA-T001");
        assertThat(reloaded.getEvents()).extracting(RequestEventEntity::getType)
                .containsExactly(EventType.SUBMITTED, EventType.APPROVED);
        assertThat(reloaded.getEvents().get(1)).extracting(RequestEventEntity::getActorId, RequestEventEntity::getFromStatus,
                        RequestEventEntity::getToStatus, RequestEventEntity::getComment)
                .containsExactly("rev-johan", RequestStatus.PENDING, RequestStatus.APPROVED, "ok");
        assertThat(reloaded.getVersion()).isEqualTo(firstVersion + 1);
    }

    @Test
    void INV04_decline_clears_activeApplicationId() {
        PricingExceptionRequestEntity entity = saveNew(submit("MA-T001"));

        PricingExceptionRequestEntity reloaded = change(entity.getId(), a -> decide(a, false, "no", NOW));

        assertThat(reloaded.getStatus()).isEqualTo(RequestStatus.DECLINED);
        assertThat(reloaded.getActiveApplicationId()).isNull();
        assertThat(reloaded.getApprovedDiscountBps()).isNull();
        assertThat(reloaded.getEvents()).hasSize(2);
    }

    @Test
    void INV01_superseded_request_with_supersedesId_and_all_statuses_round_trip_without_loss() {
        PricingExceptionRequestEntity old = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity wd = saveNew(submit("MA-A002"));
        PricingExceptionRequestEntity ap = saveNew(submit("MA-A003"));
        PricingExceptionRequestEntity dc = saveNew(submit("MA-A004"));

        List<PricingExceptionRequestEntity> expected = new ArrayList<>();
        expected.add(change(old.getId(), r -> supersede(r, LATER)));
        expected.add(change(wd.getId(), r -> withdraw(r, LATER)));
        expected.add(change(ap.getId(), r -> decide(r, true, "ok", LATER)));
        expected.add(change(dc.getId(), r -> decide(r, false, "no", LATER)));
        PricingExceptionRequestEntity replacement = saveNew(submit("MA-A001", "new", LATER, old.getId()));
        expected.add(load(replacement.getId()));

        for (PricingExceptionRequestEntity e : expected) {
            em.clear();
            assertThat(load(e.getId())).usingRecursiveComparison().isEqualTo(e);
        }
        assertThat(load(old.getId()).getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(load(replacement.getId()).getSupersedesId()).isEqualTo(old.getId());
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

        // the same through the column changes of withdraw, decline, supersede, then a second active request is rejected
        for (int i = 0; i < 3; i++) {
            UUID id = saveNew(submit("MA-A001")).getId();
            final int step = i;
            change(id, r -> {
                if (step == 0) withdraw(r, LATER); else if (step == 1) decide(r, false, "no", LATER); else supersede(r, LATER);
            });
        }
        PricingExceptionRequestEntity current = saveNew(submit("MA-A001"));
        assertThat(requests.findByApplicationId("MA-A001")).hasSize(4);
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getId()).isEqualTo(current.getId());
        assertThatThrownBy(() -> requests.saveAndFlush(submit("MA-A001")))
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
        // Documented approach (service): mark the old request SUPERSEDED and flush before inserting
        // the new one. One single flush for both would hit ux_per_active_application (INSERTs run before UPDATEs).
        PricingExceptionRequestEntity old = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity oldEntity = load(old.getId());
        supersede(oldEntity, LATER);
        requests.saveAndFlush(oldEntity);
        PricingExceptionRequestEntity replacement = submit("MA-A001", "new", LATER, old.getId());
        requests.saveAndFlush(replacement);
        em.clear();
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getId()).isEqualTo(replacement.getId());
        assertThat(load(old.getId()).getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(load(old.getId()).getEvents()).hasSize(2);
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
        PricingExceptionRequestEntity e = submit(app, "x", NOW, supersedes);
        change.accept(e);
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV03_decided_by_must_be_an_existing_user() {
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = load(r.getId());
        e.setStatus(RequestStatus.DECLINED);
        e.setActiveApplicationId(null);
        e.setDecidedBy("rev-nobody");
        e.setDecidedAt(NOW);
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV02_each_command_adds_exactly_one_event_row_and_old_rows_keep_their_ids() {
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        Long firstId = load(r.getId()).getEvents().get(0).getId();
        em.clear();
        List<RequestEventEntity> rows = change(r.getId(), a -> decide(a, false, "no", LATER)).getEvents();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getId()).isEqualTo(firstId);
        assertThat(rows.get(1).getType()).isEqualTo(EventType.DECLINED);
        assertThat(eventRows(r.getId())).isEqualTo(2);
    }

    @Test
    void INV02_history_survives_a_backwards_clock_step_between_two_commands() {
        // JUDGEMENT: if a later command carries an earlier timestamp (clock step back, two nodes) the history must
        // still reload in insertion order, not become unreadable.
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity reloaded = change(r.getId(), l -> withdraw(l, NOW.minusSeconds(5)));
        assertThat(reloaded.getEvents()).extracting(RequestEventEntity::getType)
                .containsExactly(EventType.SUBMITTED, EventType.WITHDRAWN);
    }

    @Test
    void INV02_events_with_identical_timestamps_reload_in_insertion_order() {
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity again = change(r.getId(), l -> withdraw(l, NOW)); // same instant as SUBMITTED
        assertThat(again.getEvents()).extracting(RequestEventEntity::getType)
                .containsExactly(EventType.SUBMITTED, EventType.WITHDRAWN);
        assertThat(eventRows(r.getId())).isEqualTo(2);
    }

    @Test
    void INV01_reason_and_comment_of_exactly_500_chars_survive_a_round_trip() {
        String[] texts = {"a".repeat(500), "åäö".repeat(166) + "åä", "😀".repeat(250)}; // 500 UTF-16 units each
        for (int i = 0; i < texts.length; i++) {
            String text = texts[i];
            PricingExceptionRequestEntity r = saveNew(submit("MA-A00" + (1 + i), text, NOW, null));
            PricingExceptionRequestEntity got = change(r.getId(), l -> decide(l, false, text, LATER));
            assertThat(got.getReason()).isEqualTo(text);
            assertThat(got.getDecisionComment()).isEqualTo(text);
            assertThat(got.getEvents().get(1).getComment()).isEqualTo(text);
        }
    }

    @Test
    void INV01_markup_quotes_and_microsecond_instants_round_trip_exactly() {
        String text = "<script>alert('x')</script> \"q\" ; DROP TABLE app_user; --";
        assertThat(load(saveNew(submit("MA-A001", text, NOW, null)).getId()).getReason()).isEqualTo(text);

        Instant micros = Instant.parse("2026-01-15T10:00:00.123456Z");
        PricingExceptionRequestEntity got = load(saveNew(submit("MA-A002", "x", micros, null)).getId());
        assertThat(got.getCreatedAt()).isEqualTo(micros);
        assertThat(got.getEvents().get(0).getOccurredAt()).isEqualTo(micros);
    }

    @Test
    void INV05_second_save_of_a_stale_detached_copy_fails_with_optimistic_lock_error() {
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity copy1 = load(r.getId());
        copy1.getEvents().size();
        em.detach(copy1);
        PricingExceptionRequestEntity copy2 = load(r.getId());
        copy2.getEvents().size();
        em.detach(copy2);
        assertThat(copy1).isNotSameAs(copy2);

        withdraw(copy1, LATER);
        requests.saveAndFlush(copy1);

        decide(copy2, false, "no", LATER);
        assertThatThrownBy(() -> requests.saveAndFlush(copy2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void INV05_version_goes_up_by_one_per_command_and_not_at_all_for_a_save_with_no_change() {
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        Long v0 = load(r.getId()).getVersion();
        em.clear();
        PricingExceptionRequestEntity e = change(r.getId(), a -> decide(a, true, "ok", LATER)); // status, decision, active id, event
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
        requests.saveAndFlush(e); // nothing changed
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
        em.clear();
        assertThat(load(r.getId()).getVersion()).isEqualTo(v0 + 1);
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
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = load(r.getId());
        try {
            e.getEvents().clear();
            requests.flush();
        } catch (RuntimeException ignored) {
            // rejecting the change is also acceptable
        }
        em.clear();
        assertThat(eventRows(r.getId())).isEqualTo(1);
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
        PricingExceptionRequestEntity r = saveNew(submit("MA-A001"));
        change(r.getId(), a -> withdraw(a, LATER));
        assertThat(requests.findByActiveApplicationId("MA-A001")).isEmpty();
        assertThat(requests.findByActiveApplicationId("MA-DOES-NOT-EXIST")).isEmpty();
        assertThat(requests.findByActiveApplicationId("")).isEmpty();
        assertThat(requests.findByActiveApplicationId(null)).isEmpty();
    }

    @Test
    void seed_all_seeded_requests_reload_with_consistent_history() {
        for (String id : new String[] {"00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003"}) {
            PricingExceptionRequestEntity r = load(UUID.fromString(id));
            assertThat(r.getEvents().get(r.getEvents().size() - 1).getToStatus()).isEqualTo(r.getStatus());
            assertThat(r.getEvents().size()).isEqualTo(r.getStatus() == RequestStatus.PENDING ? 1 : 2);
            assertThat(r.getActiveApplicationId() != null).isEqualTo(r.getStatus().isActive());
        }
    }

    @Test
    void INV04_every_stored_row_has_an_active_application_id_that_matches_its_status() {
        // JUDGEMENT: the schema cannot tie active_application_id to status (ck_per_active only checks the value).
        // The old mapper rejected such a row on load; now this test checks every row, seed and test rows alike.
        for (RequestStatus s : RequestStatus.values()) {
            requests.saveAndFlush(entityIn("MA-T00" + (1 + s.ordinal()), s));
        }
        em.clear();
        List<PricingExceptionRequestEntity> all = requests.findAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(RequestStatus.values().length + 3);
        for (PricingExceptionRequestEntity r : all) {
            assertThat(r.getActiveApplicationId()).as("row %s in status %s", r.getId(), r.getStatus())
                    .isEqualTo(r.getStatus().isActive() ? r.getApplicationId() : null);
            assertThat(r.getApprovedDiscountBps()).as("approved discount of %s", r.getId())
                    .isEqualTo(r.getStatus() == RequestStatus.APPROVED ? Integer.valueOf(r.getDiscountBps()) : null);
        }
    }
}
