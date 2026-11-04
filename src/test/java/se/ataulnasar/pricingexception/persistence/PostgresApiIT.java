package se.ataulnasar.pricingexception.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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

    private static final String BASE = "/api/pricing-exceptions";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String newApp() {
        String id = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", id, "CUST-" + id);
        return id;
    }

    private static String body(String app, int discount) {
        return "{\"applicationId\":\"" + app + "\",\"discountBps\":" + discount + ",\"reason\":\"Customer has other offers\"}";
    }

    @Test
    void INV04_second_active_request_is_409_ACTIVE_REQUEST_EXISTS_on_postgres() throws Exception {
        String app = newApp();
        mvc.perform(post(BASE).header("X-User-Id", "rm-anna").contentType(MediaType.APPLICATION_JSON).content(body(app, 40)))
                .andExpect(status().isCreated());

        mvc.perform(post(BASE).header("X-User-Id", "rm-erik").contentType(MediaType.APPLICATION_JSON).content(body(app, 30)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_REQUEST_EXISTS"));
    }

    @Test
    void idempotent_replay_works_on_postgres() throws Exception {
        String app = newApp();
        String key = "key-" + UUID.randomUUID();
        MvcResult first = mvc.perform(post(BASE).header("X-User-Id", "rm-anna").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(app, 40)))
                .andExpect(status().isCreated()).andReturn();

        MvcResult second = mvc.perform(post(BASE).header("X-User-Id", "rm-anna").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(app, 40)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(JsonPath.<String>read(second.getResponse().getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.id"));
        assertThat(second.getResponse().getContentAsByteArray()).isEqualTo(first.getResponse().getContentAsByteArray());
    }

    @Test
    void idempotent_parallel_creates_with_same_key_on_postgres_create_one_request_and_all_return_same_id() throws Exception {
        String app = newApp();
        String key = "key-" + UUID.randomUUID();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<MvcResult> results = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post(BASE).header("X-User-Id", "rm-anna").header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body(app, 40))).andReturn();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<MvcResult> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).allMatch(r -> r.getResponse().getStatus() == 201);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MvcResult r : results) ids.add(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.id"));
        assertThat(ids).hasSize(1);
        assertThat(results.stream().filter(r -> r.getResponse().getHeader("Idempotent-Replayed") == null)).hasSize(1);
        assertThat(results.stream().filter(r -> "true".equals(r.getResponse().getHeader("Idempotent-Replayed"))))
                .hasSize(n - 1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_exception_request WHERE application_id = ?",
                Integer.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_event WHERE request_id IN"
                + " (SELECT id FROM pricing_exception_request WHERE application_id = ?)", Integer.class, app)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE caller_id = 'rm-anna' AND idem_key = ?",
                Integer.class, key)).isEqualTo(1);
    }
}
