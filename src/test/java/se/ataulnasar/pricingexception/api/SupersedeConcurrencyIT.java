package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 5: races on supersede over real HTTP (random port, JDK HttpClient, real H2).
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna)
 * and its own Idempotency-Key. Seed users used: rm-anna (RM).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupersedeConcurrencyIT {

    private static final int PARALLEL = 10;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

    // ---------- helpers ----------

    record Resp(int status, HttpHeaders headers, byte[] bytes) {
        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String json(String path) {
            return JsonPath.read(body(), path);
        }

        boolean replayed() {
            return "true".equals(header("Idempotent-Replayed"));
        }
    }

    private String newApp() {
        String id = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", id, "CUST-" + id);
        return id;
    }

    private static String createBody(String app, int discount) {
        return "{\"applicationId\":\"" + app + "\",\"discountBps\":" + discount + ",\"reason\":\"Customer has other offers\"}";
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private Resp send(String method, String path, String user, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", user)
                .timeout(java.time.Duration.ofSeconds(30));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(r.statusCode(), r.headers(), r.body());
    }

    private Resp create(String user, String body, String key) throws Exception {
        return key == null
                ? send("POST", "/api/pricing-exceptions", user, body)
                : send("POST", "/api/pricing-exceptions", user, body, "Idempotency-Key", key);
    }

    private Resp get(String path) throws Exception {
        return send("GET", path, "rm-anna", null);
    }

    private Resp decide(String location, String user, String outcome, String ifMatch) throws Exception {
        return send("POST", location + "/decision", user, "{\"outcome\":\"" + outcome + "\"}", "If-Match", ifMatch);
    }

    private int requestCountFor(String app) throws Exception {
        Resp list = get("/api/pricing-exceptions?applicationId=" + app);
        assertThat(list.status()).isEqualTo(200);
        return JsonPath.<List<?>>read(list.body(), "$").size();
    }

    /** Runs the task n times at the same moment (latch) and returns all results in submit order. */
    private static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        return parallel(Collections.nCopies(n, task));
    }

    /** Task i runs as tasks.get(i); results come back in the same order. */
    private static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> task : tasks) {
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String supBody(int discount) {
        return "{\"discountBps\":" + discount + ",\"reason\":\"Lower discount after review\"}";
    }

    private Resp supersede(String location, String body, String key) throws Exception {
        return key == null
                ? send("POST", location + "/supersessions", "rm-anna", body, "If-Match", "\"0\"")
                : send("POST", location + "/supersessions", "rm-anna", body, "If-Match", "\"0\"", "Idempotency-Key", key);
    }

    @Test
    void INV05_two_parallel_supersedes_with_same_if_match_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            Resp created = create("rm-anna", createBody(app, 40), null);
            assertThat(created.status()).isEqualTo(201);
            String location = created.header("Location");

            List<Resp> results = parallel(2, () -> supersede(location, supBody(30), null));

            assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
            Resp loser = results.stream().filter(r -> r.status() != 201).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 412);
            assertThat(loser.json("$.code")).isIn("CONCURRENT_MODIFICATION", "INVALID_TRANSITION", "VERSION_MISMATCH");

            assertThat(requestCountFor(app)).as("old + exactly one new").isEqualTo(2);
            Resp old = get(location);
            assertThat(old.json("$.status")).isEqualTo("SUPERSEDED");
            assertThat(old.header("ETag")).isEqualTo("\"1\"");
            assertThat(JsonPath.<Integer>read(old.body(), "$.events.length()")).isEqualTo(2);
            String winnerId = results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().json("$.id");
            assertThat(old.json("$.supersededById")).isEqualTo(winnerId);
            Resp winner = get("/api/pricing-exceptions/" + winnerId);
            assertThat(winner.json("$.status")).isEqualTo("PENDING");
            assertThat(JsonPath.<Integer>read(winner.body(), "$.events.length()")).isEqualTo(1);
        }
    }

    @Test
    void INV05_parallel_supersede_and_decision_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            Resp created = create("rm-anna", createBody(app, 40), null);
            String location = created.header("Location");
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            List<Resp> results = parallel(2, () -> n.getAndIncrement() == 0
                    ? supersede(location, supBody(30), null)
                    : decide(location, "rev-sara", "APPROVED", "\"0\""));

            assertThat(results.stream().filter(r -> r.status() == 200 || r.status() == 201)).hasSize(1);
            Resp loser = results.stream().filter(r -> r.status() != 200 && r.status() != 201).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 412);
            Resp old = get(location);
            assertThat(old.json("$.status")).isIn("SUPERSEDED", "APPROVED");
            assertThat(JsonPath.<Integer>read(old.body(), "$.events.length()")).isEqualTo(2);
            // never two active requests
            Resp list = get("/api/pricing-exceptions?applicationId=" + app);
            List<String> statuses = JsonPath.read(list.body(), "$[*].status");
            assertThat(statuses.stream().filter(s -> s.equals("PENDING") || s.equals("APPROVED"))).hasSize(1);
        }
    }

    @Test
    void idempotent_parallel_supersedes_with_same_key_create_one_request() throws Exception {
        String app = newApp();
        Resp created = create("rm-anna", createBody(app, 40), null);
        String location = created.header("Location");
        String key = newKey();

        List<Resp> results = parallel(PARALLEL, () -> supersede(location, supBody(30), key));

        for (Resp r : results) {
            assertThat(r.status()).as(r.body()).isEqualTo(201);
        }
        assertThat(results.stream().map(r -> r.json("$.id")).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(Resp::replayed)).hasSize(PARALLEL - 1);
        assertThat(results.stream().map(Resp::body).distinct()).hasSize(1);
        assertThat(requestCountFor(app)).isEqualTo(2);
        Resp old = get(location);
        assertThat(old.json("$.status")).isEqualTo("SUPERSEDED");
        assertThat(JsonPath.<Integer>read(old.body(), "$.events.length()")).isEqualTo(2);
    }

    // ---------- supersede racing with withdraw, create and reads (merged from SupersedeRaceAttackIT) ----------

    private static final String SUP = "{\"discountBps\":30,\"reason\":\"Lower discount after review\"}";

    private List<String> statusesOf(String app) throws Exception {
        Resp list = send("GET", "/api/pricing-exceptions?applicationId=" + app, "rm-anna", null);
        return JsonPath.read(list.body(), "$[*].status");
    }

    @Test
    void INV05_supersede_racing_with_withdraw_only_one_wins() throws Exception {
        for (int round = 0; round < 8; round++) {
            String app = newApp();
            Resp created = send("POST", "/api/pricing-exceptions", "rm-anna", createBody(app, 40));
            String id = created.json("$.id");
            List<Resp> rs = parallel(List.<Callable<Resp>>of(
                    () -> send("POST", "/api/pricing-exceptions/" + id + "/supersessions", "rm-anna", SUP, "If-Match", "\"0\""),
                    () -> send("POST", "/api/pricing-exceptions/" + id + "/withdrawal", "rm-anna", null, "If-Match", "\"0\"")));
            assertThat(rs.stream().filter(r -> r.status() == 200 || r.status() == 201)).as(rs.toString()).hasSize(1);
            Resp loser = rs.stream().filter(r -> r.status() != 200 && r.status() != 201).findFirst().orElseThrow();
            assertThat(loser.status()).as(loser.body()).isIn(409, 412);
            List<String> st = statusesOf(app);
            long active = st.stream().filter(s -> s.equals("PENDING") || s.equals("APPROVED")).count();
            if (rs.get(0).status() == 201) {
                assertThat(st).containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
            } else {
                assertThat(st).containsExactly("WITHDRAWN");
                assertThat(active).isZero();
            }
            Resp old = send("GET", "/api/pricing-exceptions/" + id, "rm-anna", null);
            assertThat(JsonPath.<Integer>read(old.body(), "$.events.length()")).isEqualTo(2);
        }
    }

    @Test
    void INV04_create_racing_with_supersede_is_always_409_and_never_a_second_active_request() throws Exception {
        for (int round = 0; round < 10; round++) {
            String app = newApp();
            String id = send("POST", "/api/pricing-exceptions", "rm-anna", createBody(app, 40)).json("$.id");
            List<Resp> rs = parallel(List.<Callable<Resp>>of(
                    () -> send("POST", "/api/pricing-exceptions/" + id + "/supersessions", "rm-anna", SUP, "If-Match", "\"0\""),
                    () -> send("POST", "/api/pricing-exceptions", "rm-anna", createBody(app, 20))));
            Resp sup = rs.get(0), create = rs.get(1);
            assertThat(sup.status()).as(sup.body()).isEqualTo(201);
            assertThat(create.status()).as(create.body()).isEqualTo(409);
            assertThat(create.json("$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
            assertThat(statusesOf(app)).containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
        }
    }

    @Test
    void approved_discount_reads_during_a_supersede_are_404_NO_APPROVED_DISCOUNT() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            String id = send("POST", "/api/pricing-exceptions", "rm-anna", createBody(app, 40)).json("$.id");
            List<Resp> rs = parallel(List.<Callable<Resp>>of(
                    () -> send("POST", "/api/pricing-exceptions/" + id + "/supersessions", "rm-anna", SUP, "If-Match", "\"0\""),
                    () -> send("GET", "/api/applications/" + app + "/approved-discount", "mortgage-system", null),
                    () -> send("GET", "/api/applications/" + app + "/approved-discount", "mortgage-system", null)));
            assertThat(rs.get(0).status()).isEqualTo(201);
            for (Resp r : rs.subList(1, 3)) {
                assertThat(r.status()).as(r.body()).isEqualTo(404);
                assertThat(r.json("$.code")).isEqualTo("NO_APPROVED_DISCOUNT");
            }
        }
    }
}
