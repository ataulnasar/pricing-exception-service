package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.RequestEvent;
import se.ataulnasar.pricingexception.domain.RequestStatus;

/**
 * Seed rows relied on: users rm-anna, rm-erik, rev-johan (limit 150). No seeded application is used;
 * the tests insert their own applications MA-T001..MA-T006.
 */
@DataJpaTest
@Import(PricingExceptionRequestMapper.class)
class PricingExceptionRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor JOHAN = Actor.reviewer("rev-johan", 150);

    @Autowired PricingExceptionRequestRepository requests;
    @Autowired MortgageApplicationRepository applications;
    @Autowired PricingExceptionRequestMapper mapper;
    @Autowired TestEntityManager em;

    @BeforeEach
    void insertApplications() {
        for (String id : new String[] {"MA-T001", "MA-T002", "MA-T003", "MA-T004", "MA-T005", "MA-T006"}) {
            MortgageApplicationEntity a = new MortgageApplicationEntity();
            a.setId(id);
            a.setCustomerRef("CUST-" + id);
            a.setStandardRateBps(450);
            a.setStatus(ApplicationStatus.OPEN);
            a.setOwnerRmId("rm-anna");
            applications.save(a);
        }
        applications.flush();
    }

    private static PricingExceptionRequest submit(String applicationId, int discount) {
        return PricingExceptionRequest.submit(UUID.randomUUID(), applicationId, ANNA,
                DiscountBps.of(discount, 100), new Reason("Long-standing customer"), null, CLOCK);
    }

    private PricingExceptionRequestEntity entityIn(String applicationId, RequestStatus status) {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit(applicationId, 40));
        e.setStatus(status);
        e.setActiveApplicationId(status.isActive() ? applicationId : null);
        if (status == RequestStatus.APPROVED) {
            e.setApprovedDiscountBps(e.getDiscountBps());
        }
        if (status != RequestStatus.PENDING) {
            e.setDecidedBy("rev-johan");
            e.setDecidedAt(NOW);
        }
        return e;
    }

    @Test
    void persistence_saves_and_loads_request_with_its_submitted_event() {
        PricingExceptionRequest domain = submit("MA-T001", 40);
        requests.saveAndFlush(mapper.toNewEntity(domain));
        em.clear();

        PricingExceptionRequestEntity e = requests.findById(domain.id()).orElseThrow();

        assertThat(e.getId()).isEqualTo(domain.id());
        assertThat(e.getApplicationId()).isEqualTo("MA-T001");
        assertThat(e.getRequestedBy()).isEqualTo("rm-anna");
        assertThat(e.getDiscountBps()).isEqualTo(40);
        assertThat(e.getReason()).isEqualTo("Long-standing customer");
        assertThat(e.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(e.getSupersedesId()).isNull();
        assertThat(e.getDecidedBy()).isNull();
        assertThat(e.getDecidedAt()).isNull();
        assertThat(e.getDecisionComment()).isNull();
        assertThat(e.getApprovedDiscountBps()).isNull();
        assertThat(e.getCreatedAt()).isEqualTo(NOW);
        assertThat(e.getVersion()).isNotNull();
        assertThat(e.getActiveApplicationId()).isEqualTo("MA-T001");
        assertThat(e.getEvents()).hasSize(1);
        RequestEventEntity ev = e.getEvents().get(0);
        assertThat(ev.getId()).isNotNull();
        assertThat(ev.getType()).isEqualTo(RequestEvent.Type.SUBMITTED);
        assertThat(ev.getActorId()).isEqualTo("rm-anna");
        assertThat(ev.getOccurredAt()).isEqualTo(NOW);
        assertThat(ev.getFromStatus()).isNull();
        assertThat(ev.getToStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(ev.getComment()).isNull();
    }

    @Test
    void persistence_loaded_entity_maps_back_to_an_equal_aggregate() {
        PricingExceptionRequest domain = submit("MA-T001", 40);
        requests.saveAndFlush(mapper.toNewEntity(domain));
        em.clear();

        PricingExceptionRequest loaded = mapper.toDomain(requests.findById(domain.id()).orElseThrow());

        assertThat(loaded.id()).isEqualTo(domain.id());
        assertThat(loaded.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(loaded.discount()).isEqualTo(domain.discount());
        assertThat(loaded.reason()).isEqualTo(domain.reason());
        assertThat(loaded.applicationId()).isEqualTo("MA-T001");
        assertThat(loaded.requestedBy()).isEqualTo("rm-anna");
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.events()).hasSize(1);
        assertThat(loaded.events()).isEqualTo(domain.events());
        assertThat(loaded.activeApplicationId()).isEqualTo("MA-T001");
    }

    @Test
    void persistence_supersedesId_is_stored() {
        // The old request must exist first: supersedes_id is a foreign key to pricing_exception_request.id.
        PricingExceptionRequestEntity old = entityIn("MA-T001", RequestStatus.SUPERSEDED);
        RequestEventEntity superseded = new RequestEventEntity();
        superseded.setType(RequestEvent.Type.SUPERSEDED);
        superseded.setActorId("rm-anna");
        superseded.setOccurredAt(NOW);
        superseded.setFromStatus(RequestStatus.PENDING);
        superseded.setToStatus(RequestStatus.SUPERSEDED);
        old.getEvents().add(superseded);
        requests.saveAndFlush(old);
        UUID oldId = old.getId();

        PricingExceptionRequest domain = PricingExceptionRequest.submit(UUID.randomUUID(), "MA-T001", ANNA,
                DiscountBps.of(30, 100), new Reason("Better offer elsewhere"), oldId, CLOCK);
        requests.saveAndFlush(mapper.toNewEntity(domain));
        em.clear();

        assertThat(requests.findById(domain.id()).orElseThrow().getSupersedesId()).isEqualTo(oldId);
        assertThat(requests.findById(oldId).orElseThrow().getStatus()).isEqualTo(RequestStatus.SUPERSEDED);
    }

    @Test
    void INV02_round_trip_approve_writes_exactly_one_new_event_and_bumps_version() {
        PricingExceptionRequest domain = submit("MA-T001", 40);
        PricingExceptionRequestEntity saved = requests.saveAndFlush(mapper.toNewEntity(domain));
        Long firstVersion = saved.getVersion();
        em.clear();

        PricingExceptionRequestEntity loaded = requests.findById(domain.id()).orElseThrow();
        PricingExceptionRequest aggregate = mapper.toDomain(loaded);
        aggregate.approve(JOHAN, "ok", CLOCK);
        mapper.applyTo(aggregate, loaded);
        requests.flush();
        em.clear();

        PricingExceptionRequestEntity reloaded = requests.findById(domain.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(reloaded.getDecidedBy()).isEqualTo("rev-johan");
        assertThat(reloaded.getDecidedAt()).isEqualTo(NOW);
        assertThat(reloaded.getDecisionComment()).isEqualTo("ok");
        assertThat(reloaded.getApprovedDiscountBps()).isEqualTo(40);
        assertThat(reloaded.getActiveApplicationId()).isEqualTo("MA-T001");
        assertThat(reloaded.getEvents()).hasSize(2);
        assertThat(reloaded.getEvents().get(0).getType()).isEqualTo(RequestEvent.Type.SUBMITTED);
        assertThat(reloaded.getEvents().get(1).getType()).isEqualTo(RequestEvent.Type.APPROVED);
        assertThat(reloaded.getEvents().get(1).getActorId()).isEqualTo("rev-johan");
        assertThat(reloaded.getEvents().get(1).getFromStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(reloaded.getEvents().get(1).getToStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(reloaded.getEvents().get(1).getComment()).isEqualTo("ok");
        assertThat(reloaded.getVersion()).isEqualTo(firstVersion + 1);
    }

    @Test
    void INV04_decline_clears_activeApplicationId_through_applyTo() {
        PricingExceptionRequest domain = submit("MA-T001", 40);
        requests.saveAndFlush(mapper.toNewEntity(domain));
        em.clear();

        PricingExceptionRequestEntity loaded = requests.findById(domain.id()).orElseThrow();
        PricingExceptionRequest aggregate = mapper.toDomain(loaded);
        aggregate.decline(JOHAN, "no", CLOCK);
        mapper.applyTo(aggregate, loaded);
        requests.flush();
        em.clear();

        PricingExceptionRequestEntity reloaded = requests.findById(domain.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RequestStatus.DECLINED);
        assertThat(reloaded.getActiveApplicationId()).isNull();
        assertThat(reloaded.getApprovedDiscountBps()).isNull();
        assertThat(reloaded.getEvents()).hasSize(2);
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
    void INV04_active_requests_for_different_applications_coexist() {
        requests.saveAndFlush(entityIn("MA-T001", RequestStatus.PENDING));
        requests.saveAndFlush(entityIn("MA-T002", RequestStatus.PENDING));

        assertThat(requests.findByActiveApplicationId("MA-T001")).isPresent();
        assertThat(requests.findByActiveApplicationId("MA-T002")).isPresent();
    }

    @Test
    void INV04_new_pending_is_allowed_after_the_first_is_declined() {
        requests.saveAndFlush(entityIn("MA-T003", RequestStatus.DECLINED));

        requests.saveAndFlush(entityIn("MA-T003", RequestStatus.PENDING));

        assertThat(requests.findByApplicationId("MA-T003")).hasSize(2);
        assertThat(requests.findByActiveApplicationId("MA-T003")).isPresent();
    }

    @Test
    void INV04_many_non_active_requests_for_one_application_coexist() {
        requests.saveAndFlush(entityIn("MA-T004", RequestStatus.DECLINED));
        requests.saveAndFlush(entityIn("MA-T004", RequestStatus.WITHDRAWN));
        requests.saveAndFlush(entityIn("MA-T004", RequestStatus.SUPERSEDED));
        requests.saveAndFlush(entityIn("MA-T004", RequestStatus.DECLINED));

        assertThat(requests.findByApplicationId("MA-T004")).hasSize(4);
        assertThat(requests.findByActiveApplicationId("MA-T004")).isEmpty();
    }

    @Test
    void INV04_finding_by_active_application_returns_only_the_active_one() {
        requests.saveAndFlush(entityIn("MA-T005", RequestStatus.WITHDRAWN));
        PricingExceptionRequestEntity active = requests.saveAndFlush(entityIn("MA-T005", RequestStatus.PENDING));

        assertThat(requests.findByActiveApplicationId("MA-T005")).get()
                .extracting(PricingExceptionRequestEntity::getId).isEqualTo(active.getId());
    }

    @Test
    void persistence_findByStatus_filters() {
        PricingExceptionRequestEntity w = requests.saveAndFlush(entityIn("MA-T006", RequestStatus.WITHDRAWN));

        assertThat(requests.findByStatus(RequestStatus.WITHDRAWN)).extracting(PricingExceptionRequestEntity::getId)
                .contains(w.getId());
        assertThat(requests.findByStatus(RequestStatus.PENDING)).extracting(PricingExceptionRequestEntity::getId)
                .doesNotContain(w.getId());
    }

    @Test
    void persistence_foreign_key_rejects_unknown_application() {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-DOES-NOT-EXIST", 40));

        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistence_foreign_key_rejects_unknown_requester() {
        PricingExceptionRequestEntity e = mapper.toNewEntity(submit("MA-T001", 40));
        e.setRequestedBy("nobody");

        assertThatThrownBy(() -> requests.saveAndFlush(e)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
