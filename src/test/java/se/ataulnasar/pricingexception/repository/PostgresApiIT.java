package se.ataulnasar.pricingexception.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.controller.Api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
    @Autowired DataSource dataSource;
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

    /**
     * INV-06 on the real database (PostgreSQL read committed). An external writer closes the application and
     * holds the row lock; the approval must wait (SELECT ... FOR UPDATE) and then see CLOSED. Without the lock
     * the stale OPEN read wins and this gets 200.
     */
    @Test
    void INV06_approval_waits_for_a_concurrent_close_and_is_rejected_on_postgres() throws Exception {
        String app = Api.newApp(jdbc);
        String location = api.create("rm-anna", createBody(app, 40), null).status(201).location();
        String etag = api.get("rm-anna", location).etag();

        Connection closer = dataSource.getConnection();
        CompletableFuture<Resp> approval;
        try {
            closer.setAutoCommit(false);
            try (PreparedStatement ps = closer.prepareStatement("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?")) {
                ps.setString(1, app);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            approval = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.decide(location, "rev-sara", "APPROVED", etag);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            // wait until the database reports the approval blocked on our row lock (no fixed sleep: a slow box
            // must not let the close commit before the approval has reached its SELECT)
            boolean blocked = false;
            for (int i = 0; i < 250 && !blocked; i++) {
                try (PreparedStatement ps = closer.prepareStatement("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    blocked = rs.getInt(1) > 0;
                }
                if (!blocked) Thread.sleep(20);
            }
            assertThat(blocked).as("the approval was observed blocked on the row lock").isTrue();
            assertThat(approval).as("the approval is blocked on the row lock").isNotDone();
            closer.commit();
        } finally {
            closer.close();
        }

        approval.get(20, TimeUnit.SECONDS).problem(409, "APPLICATION_NOT_OPEN");
        Resp after = api.get("rm-anna", location);
        after.has("$.status", "PENDING");
        assertThat(after.events()).isEqualTo(1); // INV-02: no event for the rejected approval
        assertThat(jdbc.queryForObject("SELECT approved_discount_bps FROM pricing_exception_request WHERE id = ?",
                Integer.class, UUID.fromString(after.id()))).isNull();
    }
}
