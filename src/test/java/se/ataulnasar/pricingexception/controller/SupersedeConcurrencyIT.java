package se.ataulnasar.pricingexception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.controller.Api.*;

import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import se.ataulnasar.pricingexception.controller.Api.Resp;

/**
 * Races on supersede over real HTTP (random port, JDK HttpClient via {@link Api#http}, real H2).
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna)
 * and its own Idempotency-Key. Seed users used: rm-anna (RM), rev-sara (REVIEWER), mortgage-system.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupersedeConcurrencyIT {

    private static final int PARALLEL = 10;
    private static final String SUP = supBody(30);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private Api api;

    @BeforeEach
    void init() {
        api = Api.http(port);
    }

    private Resp supersede(String location, String key) throws Exception {
        return api.supersede("rm-anna", location, q(0), SUP, key);
    }

    /** Creates a PENDING request on a fresh application and returns {application id, request id}. */
    private String[] newPending() throws Exception {
        String app = Api.newApp(jdbc);
        return new String[] {app, api.create("rm-anna", createBody(app, 40), null).status(201).id()};
    }

    private List<String> statusesOf(String app) throws Exception {
        return api.list("rm-anna", "applicationId=" + app).strings("$[*].status");
    }

    private static boolean ok(Resp r) {
        return r.status() == 200 || r.status() == 201;
    }

    /** Asserts exactly one response succeeded (200 or 201) and the other lost with 409 or 412; returns the loser. */
    private static Resp oneWinner(List<Resp> results) {
        assertThat(results.stream().filter(SupersedeConcurrencyIT::ok)).as(results.toString()).hasSize(1);
        Resp loser = results.stream().filter(r -> !ok(r)).findFirst().orElseThrow();
        assertThat(loser.status()).as(loser.body()).isIn(409, 412);
        return loser;
    }

    @Test
    void INV05_two_parallel_supersedes_with_same_if_match_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String[] p = newPending();
            String app = p[0], location = BASE + "/" + p[1];

            List<Resp> results = parallel(2, () -> supersede(location, null));

            Resp loser = oneWinner(results);
            assertThat(loser.str("$.code")).isIn("CONCURRENT_MODIFICATION", "INVALID_TRANSITION", "VERSION_MISMATCH");
            assertThat(statusesOf(app)).as("old + exactly one new").hasSize(2);
            Resp old = api.get("rm-anna", location).has("$.status", "SUPERSEDED", "$.events.length()", 2);
            assertThat(old.etag()).isEqualTo("\"1\"");
            String winnerId = results.stream().filter(SupersedeConcurrencyIT::ok).findFirst().orElseThrow().id();
            old.has("$.supersededById", winnerId);
            api.get("rm-anna", BASE + "/" + winnerId).has("$.status", "PENDING", "$.events.length()", 1);
        }
    }

    @Test
    void INV05_parallel_supersede_and_decision_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String[] p = newPending();
            String location = BASE + "/" + p[1];
            List<Callable<Resp>> tasks = List.of(() -> supersede(location, null), () -> api.decide(location, "rev-sara", "APPROVED", q(0)));

            oneWinner(parallel(tasks));

            Resp old = api.get("rm-anna", location);
            assertThat(old.str("$.status")).isIn("SUPERSEDED", "APPROVED");
            assertThat(old.events()).isEqualTo(2);
            // never two active requests
            assertThat(statusesOf(p[0]).stream().filter(s -> s.equals("PENDING") || s.equals("APPROVED"))).hasSize(1);
        }
    }

    @Test
    void idempotent_parallel_supersedes_with_same_key_create_one_request() throws Exception {
        String[] p = newPending();
        String location = BASE + "/" + p[1];
        String key = newKey();

        List<Resp> results = parallel(PARALLEL, () -> supersede(location, key));

        for (Resp r : results) r.status(201);
        assertThat(results.stream().map(Resp::id).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(Resp::replayed)).hasSize(PARALLEL - 1);
        assertThat(results.stream().map(Resp::body).distinct()).hasSize(1);
        assertThat(statusesOf(p[0])).hasSize(2);
        api.get("rm-anna", location).has("$.status", "SUPERSEDED", "$.events.length()", 2);
    }

    @Test
    void INV05_supersede_racing_with_withdraw_only_one_wins() throws Exception {
        for (int round = 0; round < 8; round++) {
            String[] p = newPending();
            String location = BASE + "/" + p[1];
            List<Resp> rs = parallel(List.<Callable<Resp>>of(() -> supersede(location, null),
                    () -> api.withdraw(location, "rm-anna", q(0))));

            oneWinner(rs);
            List<String> st = statusesOf(p[0]);
            if (rs.get(0).status() == 201) {
                assertThat(st).containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
            } else {
                assertThat(st).containsExactly("WITHDRAWN"); // no active request left
            }
            assertThat(api.get("rm-anna", location).events()).isEqualTo(2);
        }
    }

    @Test
    void INV04_create_racing_with_supersede_is_always_409_and_never_a_second_active_request() throws Exception {
        for (int round = 0; round < 10; round++) {
            String[] p = newPending();
            List<Resp> rs = parallel(List.<Callable<Resp>>of(() -> supersede(BASE + "/" + p[1], null),
                    () -> api.create("rm-anna", p[0], 20)));

            rs.get(0).status(201);
            rs.get(1).problem(409, "ACTIVE_REQUEST_EXISTS");
            assertThat(statusesOf(p[0])).containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
        }
    }

    @Test
    void approved_discount_reads_during_a_supersede_are_404_NO_APPROVED_DISCOUNT() throws Exception {
        for (int round = 0; round < 5; round++) {
            String[] p = newPending();
            List<Resp> rs = parallel(List.<Callable<Resp>>of(() -> supersede(BASE + "/" + p[1], null),
                    () -> api.approvedDiscount("mortgage-system", p[0]), () -> api.approvedDiscount("mortgage-system", p[0])));

            rs.get(0).status(201);
            for (Resp r : rs.subList(1, 3)) r.problem(404, "NO_APPROVED_DISCOUNT");
        }
    }
}
