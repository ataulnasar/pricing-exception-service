package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
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
        assertThat(byId.get("rm-anna").getRole()).isEqualTo(Role.RM);
        assertThat(byId.get("rm-erik").getRole()).isEqualTo(Role.RM);
        assertThat(byId.get("rev-sara").getRole()).isEqualTo(Role.REVIEWER);
        assertThat(byId.get("rev-johan").getRole()).isEqualTo(Role.REVIEWER);
        assertThat(byId.get("mortgage-system").getRole()).isEqualTo(Role.MORTGAGE_PROCESS);
        assertThat(byId.get("rev-sara").getApprovalLimitBps()).isEqualTo(50);
        assertThat(byId.get("rev-johan").getApprovalLimitBps()).isEqualTo(150);
        assertThat(byId.get("rm-anna").getApprovalLimitBps()).isNull();
        assertThat(byId.get("rm-erik").getApprovalLimitBps()).isNull();
        assertThat(byId.get("mortgage-system").getApprovalLimitBps()).isNull();
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
        List<PricingExceptionRequestEntity> all = requests.findAll();

        assertThat(all).hasSize(3);
        assertThat(requests.findByStatus(RequestStatus.APPROVED)).hasSize(1);
        assertThat(requests.findByStatus(RequestStatus.PENDING)).hasSize(1);
        assertThat(requests.findByStatus(RequestStatus.DECLINED)).hasSize(1);
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

    @Test
    void INV01_seed_approved_request_stores_approved_discount_equal_to_discount() {
        PricingExceptionRequestEntity r = requests.findByStatus(RequestStatus.APPROVED).get(0);

        assertThat(r.getApprovedDiscountBps()).isEqualTo(r.getDiscountBps());
        assertThat(r.getDecidedBy()).isNotBlank();
        assertThat(r.getDecidedAt()).isNotNull();
        assertThat(r.getEvents()).hasSize(2);
        assertThat(r.getEvents().get(1).getType()).isEqualTo(RequestEvent.Type.APPROVED);
    }

    @Test
    void seed_declined_request_has_decision_fields_and_no_approved_discount() {
        PricingExceptionRequestEntity r = requests.findByStatus(RequestStatus.DECLINED).get(0);

        assertThat(r.getDecidedBy()).isNotBlank();
        assertThat(r.getDecidedAt()).isNotNull();
        assertThat(r.getApprovedDiscountBps()).isNull();
        assertThat(r.getEvents()).hasSize(2);
        assertThat(r.getEvents().get(1).getType()).isEqualTo(RequestEvent.Type.DECLINED);
    }

    @Test
    void seed_pending_request_has_no_decision_fields() {
        PricingExceptionRequestEntity r = requests.findByStatus(RequestStatus.PENDING).get(0);

        assertThat(r.getDecidedBy()).isNull();
        assertThat(r.getDecidedAt()).isNull();
        assertThat(r.getApprovedDiscountBps()).isNull();
        assertThat(r.getEvents()).hasSize(1);
    }

    @Test
    void INV04_seed_active_requests_have_activeApplicationId_and_declined_has_none() {
        for (PricingExceptionRequestEntity r : requests.findAll()) {
            if (r.getStatus().isActive()) {
                assertThat(r.getActiveApplicationId()).isEqualTo(r.getApplicationId());
            } else {
                assertThat(r.getActiveApplicationId()).isNull();
            }
        }
    }

    @Test
    void INV04_seed_has_at_most_one_active_request_per_application() {
        Map<String, Long> active = requests.findAll().stream()
                .filter(r -> r.getStatus().isActive())
                .collect(Collectors.groupingBy(PricingExceptionRequestEntity::getApplicationId,
                        Collectors.counting()));

        assertThat(active.values()).allMatch(c -> c == 1L);
    }
}
