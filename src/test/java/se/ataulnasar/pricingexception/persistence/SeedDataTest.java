package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import se.ataulnasar.pricingexception.domain.RequestEvent;
import se.ataulnasar.pricingexception.domain.RequestStatus;
import se.ataulnasar.pricingexception.domain.Role;

/** Seed data from docs/design.md, loaded by Flyway. */
@DataJpaTest
class SeedDataTest {

    @Autowired AppUserRepository users;
    @Autowired MortgageApplicationRepository applications;
    @Autowired PricingExceptionRequestRepository requests;

    @Test
    void design_seed_has_five_users_with_roles_and_limits() {
        Map<String, AppUserEntity> byId = users.findAll().stream()
                .collect(Collectors.toMap(AppUserEntity::getId, u -> u));

        assertThat(byId).containsOnlyKeys("rm-anna", "rm-erik", "rev-sara", "rev-johan", "mortgage-system");
        assertThat(byId.values()).extracting(AppUserEntity::getId, AppUserEntity::getRole, AppUserEntity::getApprovalLimitBps)
                .containsExactlyInAnyOrder(tuple("rm-anna", Role.RM, null), tuple("rm-erik", Role.RM, null),
                        tuple("rev-sara", Role.REVIEWER, 50), tuple("rev-johan", Role.REVIEWER, 150),
                        tuple("mortgage-system", Role.MORTGAGE_PROCESS, null));
    }

    @Test
    void design_seed_has_four_applications_exactly_one_closed() {
        List<MortgageApplicationEntity> all = applications.findAll();

        assertThat(all).hasSize(4);
        assertThat(all).extracting(MortgageApplicationEntity::getId)
                .containsExactlyInAnyOrder("MA-1001", "MA-1002", "MA-1003", "MA-1004");
        assertThat(all).filteredOn(a -> a.getStatus() == ApplicationStatus.CLOSED)
                .extracting(MortgageApplicationEntity::getId).containsExactly("MA-1004");
        assertThat(all).allSatisfy(a -> {
            assertThat(a.getStandardRateBps()).isPositive();
            assertThat(a.getCustomerRef()).isNotBlank();
            assertThat(a.getOwnerRmId()).isIn("rm-anna", "rm-erik");
        });
    }

    @Test
    void design_seed_has_one_approved_one_pending_one_declined_request() {
        assertThat(requests.findAll()).hasSize(3);
        for (RequestStatus s : List.of(RequestStatus.APPROVED, RequestStatus.PENDING, RequestStatus.DECLINED)) {
            assertThat(requests.findByStatus(s)).as(s.name()).hasSize(1);
        }
    }

    @Test
    void INV02_every_seed_request_has_at_least_the_SUBMITTED_event_first() {
        for (PricingExceptionRequestEntity r : requests.findAll()) {
            assertThat(r.getEvents()).isNotEmpty();
            assertThat(r.getEvents().get(0).getType()).isEqualTo(RequestEvent.Type.SUBMITTED);
            assertThat(r.getEvents().get(0).getToStatus()).isEqualTo(RequestStatus.PENDING);
            assertThat(r.getEvents().get(0).getFromStatus()).isNull();
        }
    }

    /** status, decided (decidedBy and decidedAt set), event count, type of the last event. */
    @ParameterizedTest(name = "seed {0} request has the decision fields of its status")
    @CsvSource({"APPROVED,true,2,APPROVED", "DECLINED,true,2,DECLINED", "PENDING,false,1,SUBMITTED"})
    void seed_request_has_the_decision_fields_of_its_status(RequestStatus status, boolean decided, int events,
                                                           RequestEvent.Type lastEvent) {
        PricingExceptionRequestEntity r = requests.findByStatus(status).get(0);

        assertThat(r.getDecidedBy() != null).isEqualTo(decided);
        assertThat(r.getDecidedAt() != null).isEqualTo(decided);
        assertThat(r.getEvents()).hasSize(events);
        assertThat(r.getEvents().get(events - 1).getType()).isEqualTo(lastEvent);
        // INV-01: an approval stores the discount it saw; other statuses store none
        assertThat(r.getApprovedDiscountBps()).isEqualTo(status == RequestStatus.APPROVED ? r.getDiscountBps() : null);
    }

    @Test
    void INV04_seed_active_requests_have_activeApplicationId_and_at_most_one_per_application() {
        for (PricingExceptionRequestEntity r : requests.findAll()) {
            assertThat(r.getActiveApplicationId()).isEqualTo(r.getStatus().isActive() ? r.getApplicationId() : null);
        }
        Map<String, Long> active = requests.findAll().stream().filter(r -> r.getStatus().isActive())
                .collect(Collectors.groupingBy(PricingExceptionRequestEntity::getApplicationId, Collectors.counting()));
        assertThat(active.values()).allMatch(c -> c == 1L);
    }
}
