package se.ataulnasar.pricingexception.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.RequestStatus;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestEventEntity;

/**
 * Design decision 6: Flyway owns the schema and V1__init.sql must be portable. This runs the same
 * migrations and seed data against a real PostgreSQL, with Hibernate ddl-auto=validate. Skipped
 * automatically when Docker is not available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PricingExceptionRequestRepository requests;
    @Autowired MortgageApplicationRepository applications;
    @Autowired AppUserRepository users;

    @Test
    void migrations_and_seed_apply_on_postgres_and_schema_validates() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isEqualTo(2); // V1 schema + V1000 local seed
        assertThat(users.count()).isEqualTo(5);
        assertThat(applications.count()).isEqualTo(4);
        assertThat(requests.count()).isEqualTo(3);
    }

    @Test
    void INV04_unique_active_application_index_works_on_postgres() {
        requests.saveAndFlush(pendingFor("MA-1003"));
        assertThatThrownBy(() -> requests.saveAndFlush(pendingFor("MA-1003")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static PricingExceptionRequestEntity pendingFor(String applicationId) {
        PricingExceptionRequestEntity e = new PricingExceptionRequestEntity();
        e.setId(UUID.randomUUID());
        e.setApplicationId(applicationId);
        e.setRequestedBy("rm-erik");
        e.setDiscountBps(10);
        e.setReason("portability check");
        e.setStatus(RequestStatus.PENDING);
        e.setCreatedAt(Instant.parse("2026-02-01T10:00:00Z"));
        e.setActiveApplicationId(applicationId);
        e.getEvents().add(new RequestEventEntity(EventType.SUBMITTED, "rm-erik",
                Instant.parse("2026-02-01T10:00:00Z"), null, RequestStatus.PENDING, null));
        return e;
    }
}
