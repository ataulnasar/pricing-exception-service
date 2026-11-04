package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Attack tests for the persistence layer. Own applications MA-A001..MA-A009 plus case variants.
 * Timestamps are stored with microsecond precision; the application Clock ticks in microseconds (Phase 3).
 */
@DataJpaTest
@Import(PricingExceptionRequestMapper.class)
class PersistenceAttackTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Clock LATER = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);
    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor JOHAN = Actor.reviewer("rev-johan", 150);

    @Autowired PricingExceptionRequestRepository requests;
    @Autowired MortgageApplicationRepository applications;
    @Autowired AppUserRepository users;
    @Autowired PricingExceptionRequestMapper mapper;
    @Autowired TestEntityManager em;

    @BeforeEach
    void insertApplications() {
        for (String id : new String[] {"MA-A001", "MA-A002", "MA-A003", "MA-A004", "MA-A005", "MA-A006",
                "MA-A007", "MA-A008", "MA-A009", "ma-a001", "MA-A001 "}) {
            applications.save(new MortgageApplicationEntity(id, "CUST-" + id.trim(), 450,
                    ApplicationStatus.OPEN, "rm-anna"));
        }
        applications.flush();
    }

    private static PricingExceptionRequest submit(String app, Reason reason, Clock clock, UUID supersedes) {
        return PricingExceptionRequest.submit(UUID.randomUUID(), app, ANNA, DiscountBps.of(40, 100), reason,
                supersedes, clock);
    }

    private static PricingExceptionRequest submit(String app) {
        return submit(app, new Reason("Long-standing customer"), CLOCK, null);
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
        return ((Number) em.getEntityManager()
                .createNativeQuery("select count(*) from request_event where request_id = ?1")
                .setParameter(1, id).getSingleResult()).longValue();
    }

    private void persistChange(PricingExceptionRequest changed) {
        PricingExceptionRequestEntity e = requests.findById(changed.id()).orElseThrow();
        mapper.applyTo(changed, e);
        requests.saveAndFlush(e);
        em.clear();
    }

    // ---------- round trips ----------

    @Test
    void INV01_superseded_request_with_supersedesId_and_all_statuses_round_trip_without_loss() {
        PricingExceptionRequest old = saveNew(submit("MA-A001"));
        PricingExceptionRequest wd = saveNew(submit("MA-A002"));
        PricingExceptionRequest ap = saveNew(submit("MA-A003"));
        PricingExceptionRequest dc = saveNew(submit("MA-A004"));

        PricingExceptionRequest o = load(old.id());
        o.markSuperseded(ANNA, LATER);
        persistChange(o);
        PricingExceptionRequest replacement = saveNew(submit("MA-A001", new Reason("new"), LATER, old.id()));
        PricingExceptionRequest w = load(wd.id());
        w.withdraw(ANNA, LATER);
        persistChange(w);
        PricingExceptionRequest a = load(ap.id());
        a.approve(JOHAN, "ok", LATER);
        persistChange(a);
        PricingExceptionRequest d = load(dc.id());
        d.decline(JOHAN, "no", LATER);
        persistChange(d);

        for (PricingExceptionRequest expected : List.of(o, replacement, w, a, d)) {
            PricingExceptionRequest got = load(expected.id());
            assertThat(got).usingRecursiveComparison().isEqualTo(expected);
        }
        assertThat(load(old.id()).status()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(load(replacement.id()).supersedesId()).isEqualTo(old.id());
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
    void INV02_each_command_adds_exactly_one_event_row_and_old_rows_keep_their_ids() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        Long firstId = requests.findById(r.id()).orElseThrow().getEvents().get(0).getId();
        em.clear();
        PricingExceptionRequest l = load(r.id());
        l.decline(JOHAN, "no", LATER);
        persistChange(l);
        List<RequestEventEntity> rows = requests.findById(r.id()).orElseThrow().getEvents();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getId()).isEqualTo(firstId);
        assertThat(rows.get(1).getType()).isEqualTo(RequestEvent.Type.DECLINED);
    }

    @Test
    void INV02_applyTo_rejects_an_entity_that_already_has_more_events_than_the_aggregate() {
        // JUDGEMENT: the entity is ahead of the aggregate (stale aggregate). The loop copies nothing and
        // overwrites status/decision fields silently; the mapper should detect it.
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

    @Test
    void INV01_toDomain_rejects_entity_with_approvedDiscount_different_from_discount() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        e.setStatus(RequestStatus.APPROVED);
        e.setDecidedBy("rev-johan");
        e.setDecidedAt(NOW);
        e.setApprovedDiscountBps(e.getDiscountBps() + 10);
        assertThatThrownBy(() -> mapper.toDomain(e)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV01_toDomain_rejects_entity_whose_first_event_is_not_SUBMITTED() {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-A001"));
        e.getEvents().add(0, new RequestEventEntity(RequestEvent.Type.WITHDRAWN, "rm-anna", NOW,
                RequestStatus.PENDING, RequestStatus.WITHDRAWN, null));
        assertThatThrownBy(() -> mapper.toDomain(e)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV02_history_survives_a_backwards_clock_step_between_two_commands() {
        // JUDGEMENT: events are ordered by occurredAt then id. If a later command carries an earlier
        // timestamp (clock step back, two nodes), the WITHDRAWN row reloads before SUBMITTED and
        // toDomain throws: the request becomes unreadable. Ordering by id alone would be safe.
        Clock behind = Clock.fixed(NOW.minusSeconds(5), ZoneOffset.UTC);
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest l = load(r.id());
        l.withdraw(ANNA, behind);
        persistChange(l);
        PricingExceptionRequest again = load(r.id());
        assertThat(again.events()).extracting(RequestEvent::type)
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

    // ---------- value fidelity ----------

    @Test
    void INV01_reason_and_comment_of_exactly_500_chars_survive_a_round_trip() {
        String ascii = "a".repeat(500);
        String nonAscii = "åäö".repeat(166) + "åä"; // 500 chars
        String emoji = "😀".repeat(250); // 500 UTF-16 units
        for (String text : new String[] {ascii, nonAscii, emoji}) {
            PricingExceptionRequest r = saveNew(submit("MA-A00" + (1 + List.of(ascii, nonAscii, emoji).indexOf(text)),
                    new Reason(text), CLOCK, null));
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
    void INV01_markup_and_quotes_in_reason_are_stored_verbatim() {
        String text = "<script>alert('x')</script> \"q\" ; DROP TABLE app_user; --";
        PricingExceptionRequest r = saveNew(submit("MA-A001", new Reason(text), CLOCK, null));
        assertThat(load(r.id()).reason().value()).isEqualTo(text);
    }

    @Test
    void INV01_instants_with_microsecond_precision_round_trip_exactly() {
        Instant micros = Instant.parse("2026-01-15T10:00:00.123456Z");
        PricingExceptionRequest r = saveNew(submit("MA-A001", new Reason("x"), Clock.fixed(micros, ZoneOffset.UTC), null));
        PricingExceptionRequest got = load(r.id());
        assertThat(got.createdAt()).isEqualTo(micros);
        assertThat(got.events().get(0).occurredAt()).isEqualTo(micros);
    }

    // ---------- INV-05 optimistic locking ----------

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
        assertThatThrownBy(() -> requests.saveAndFlush(copy2))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void INV05_version_goes_up_by_one_per_command_and_not_at_all_for_a_no_op_applyTo() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        Long v0 = requests.findById(r.id()).orElseThrow().getVersion();
        em.clear();
        PricingExceptionRequest l = load(r.id());
        l.approve(JOHAN, "ok", LATER); // changes status, decision fields, approved discount, active id, adds event
        persistChange(l);
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
        mapper.applyTo(mapper.toDomain(e), e);
        requests.saveAndFlush(e);
        assertThat(e.getVersion()).isEqualTo(v0 + 1);
    }

    // ---------- INV-04 ----------

    @Test
    void INV04_application_ids_that_differ_in_case_or_trailing_space_are_different_applications() {
        // Design: ids are exact. "MA-A001", "ma-a001" and "MA-A001 " are three applications.
        saveNew(submit("MA-A001"));
        saveNew(submit("ma-a001"));
        saveNew(submit("MA-A001 "));
        assertThat(requests.findByActiveApplicationId("MA-A001")).isPresent();
        assertThat(requests.findByActiveApplicationId("ma-a001")).isPresent();
        assertThat(requests.findByActiveApplicationId("MA-A001 ")).isPresent();
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getApplicationId())
                .isEqualTo("MA-A001");
    }

    @Test
    void INV04_many_inactive_requests_and_one_active_request_can_share_an_application() {
        for (int i = 0; i < 3; i++) {
            PricingExceptionRequest r = saveNew(submit("MA-A001"));
            PricingExceptionRequest l = load(r.id());
            if (i == 0) {
                l.withdraw(ANNA, LATER);
            } else if (i == 1) {
                l.decline(JOHAN, "no", LATER);
            } else {
                l.markSuperseded(ANNA, LATER);
            }
            persistChange(l);
        }
        PricingExceptionRequest active = saveNew(submit("MA-A001"));
        assertThat(requests.findByApplicationId("MA-A001")).hasSize(4);
        assertThat(requests.findByActiveApplicationId("MA-A001").orElseThrow().getId()).isEqualTo(active.id());
        assertThatThrownBy(() -> requests.saveAndFlush(mapper.toNewEntity(submit("MA-A001"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV04_active_row_whose_status_is_final_but_activeApplicationId_is_still_set_is_rejected() {
        // JUDGEMENT: nothing in the DB or in toDomain ties active_application_id to status. A hand-edited
        // WITHDRAWN row with active_application_id set blocks the application forever and goes unnoticed.
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequestEntity e = requests.findById(r.id()).orElseThrow();
        e.setStatus(RequestStatus.WITHDRAWN);
        e.setDecidedBy("rev-johan");
        e.setDecidedAt(NOW);
        // activeApplicationId deliberately left as "MA-A001"
        assertThatThrownBy(() -> mapper.toDomain(e)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INV01_supersede_works_when_the_old_request_is_flushed_before_the_replacement_is_inserted() {
        // Documented approach (docs/agent-workflow.md Phase 5, application service): mark the old request
        // SUPERSEDED and flush before inserting the new one. One single flush for both would hit
        // ux_per_active_application because Hibernate runs INSERTs before UPDATEs.
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
        PricingExceptionRequest reloadedOld = load(old.id());
        assertThat(reloadedOld.status()).isEqualTo(RequestStatus.SUPERSEDED);
        assertThat(reloadedOld.events()).hasSize(2);
    }

    // ---------- foreign keys and checks ----------

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
    void INV01_supersedes_id_must_reference_an_existing_request() {
        PricingExceptionRequest orphan = submit("MA-A001", new Reason("x"), CLOCK, UUID.randomUUID());
        assertThatThrownBy(() -> requests.saveAndFlush(mapper.toNewEntity(orphan)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV06_request_for_an_unknown_application_is_rejected_by_the_database() {
        PricingExceptionRequest r = submit("MA-NOPE");
        assertThatThrownBy(() -> requests.saveAndFlush(mapper.toNewEntity(r)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV01_a_discount_of_zero_is_rejected_by_the_database_check() {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-A001"));
        e.setDiscountBps(0);
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void INV01_reason_of_501_chars_is_rejected_by_the_database_even_if_the_domain_is_bypassed() {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-A001"));
        e.setReason("x".repeat(501));
        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- insert-only history ----------

    @Test
    void INV02_request_repository_exposes_no_delete_or_remove_method() {
        // request_event is insert only; the repository must not offer any way to delete requests.
        assertThat(PricingExceptionRequestRepository.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
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

    // ---------- queries and seed ----------

    @Test
    void findByActiveApplicationId_returns_empty_for_unknown_blank_and_inactive_applications() {
        PricingExceptionRequest r = saveNew(submit("MA-A001"));
        PricingExceptionRequest l = load(r.id());
        l.withdraw(ANNA, LATER);
        persistChange(l);
        assertThat(requests.findByActiveApplicationId("MA-A001")).isEmpty();
        assertThat(requests.findByActiveApplicationId("MA-DOES-NOT-EXIST")).isEmpty();
        assertThat(requests.findByActiveApplicationId("")).isEmpty();
    }

    @Test
    void findByActiveApplicationId_with_null_returns_empty_and_does_not_match_all_inactive_rows() {
        // JUDGEMENT: Spring Data turns a null argument into "IS NULL", which matches every inactive row
        // (seed already has a DECLINED one) and throws IncorrectResultSizeDataAccessException.
        assertThat(requests.findByActiveApplicationId(null)).isEmpty();
    }

    @Test
    void seed_all_seeded_requests_reload_into_valid_aggregates_with_consistent_history() {
        for (String id : new String[] {"00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003"}) {
            PricingExceptionRequest r = load(UUID.fromString(id));
            assertThat(r.events().get(r.events().size() - 1).toStatus()).isEqualTo(r.status());
            assertThat(r.events().size()).isEqualTo(r.status() == RequestStatus.PENDING ? 1 : 2);
            assertThat(r.activeApplicationId() != null).isEqualTo(r.status().isActive());
        }
    }

    @Test
    void seed_matches_design_limits_closed_application_and_valid_owners() {
        assertThat(users.findById("rm-anna").orElseThrow().getApprovalLimitBps()).isNull();
        assertThat(users.findById("rm-erik").orElseThrow().getApprovalLimitBps()).isNull();
        assertThat(users.findById("mortgage-system").orElseThrow().getApprovalLimitBps()).isNull();
        assertThat(users.findById("rev-sara").orElseThrow().getApprovalLimitBps()).isEqualTo(50);
        assertThat(users.findById("rev-johan").orElseThrow().getApprovalLimitBps()).isEqualTo(150);
        assertThat(applications.findById("MA-1004").orElseThrow().getStatus()).isEqualTo(ApplicationStatus.CLOSED);
        for (MortgageApplicationEntity a : applications.findAll()) {
            assertThat(users.findById(a.getOwnerRmId())).as("owner of " + a.getId()).isPresent();
        }
    }
}
