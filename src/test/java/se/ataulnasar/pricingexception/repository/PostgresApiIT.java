package se.ataulnasar.pricingexception.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.controller.Api.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import se.ataulnasar.pricingexception.controller.Api;
import se.ataulnasar.pricingexception.controller.Api.Resp;

/**
 * The API against a real PostgreSQL (constraint names are lower case there, so the database error
 * mapping is exercised for real). Skipped automatically when Docker is not available.
 * Each test creates its own OPEN application.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PostgresApiIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Api api;

    @BeforeEach
    void init() {
        api = Api.mock(mvc);
    }

    @Test
    void INV04_second_active_request_is_409_ACTIVE_REQUEST_EXISTS_on_postgres() throws Exception {
        String app = Api.newApp(jdbc);
        api.create("rm-anna", app, 40).status(201);

        api.create("rm-erik", app, 30).problem(409, "ACTIVE_REQUEST_EXISTS");
    }

    @Test
    void idempotent_replay_works_on_postgres() throws Exception {
        String app = Api.newApp(jdbc);
        String key = newKey();
        Resp first = api.create("rm-anna", createBody(app, 40), key).status(201);

        Resp second = api.create("rm-anna", createBody(app, 40), key).status(201);

        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.bytes()).isEqualTo(first.bytes());
    }

    @Test
    void idempotent_parallel_creates_with_same_key_on_postgres_create_one_request_and_all_return_same_id() throws Exception {
        String app = Api.newApp(jdbc);
        String key = newKey();
        int n = 10;
        List<Resp> results = parallel(n, () -> api.create("rm-anna", createBody(app, 40), key));

        assertThat(results).allMatch(r -> r.status() == 201);
        assertThat(results.stream().map(Resp::id).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> r.header("Idempotent-Replayed") == null)).hasSize(1);
        assertThat(results.stream().filter(Resp::replayed)).hasSize(n - 1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_exception_request WHERE application_id = ?",
                Integer.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_event WHERE request_id IN"
                + " (SELECT id FROM pricing_exception_request WHERE application_id = ?)", Integer.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE caller_id = 'rm-anna' AND idem_key = ?",
                Integer.class, key)).isEqualTo(1);
    }
}
