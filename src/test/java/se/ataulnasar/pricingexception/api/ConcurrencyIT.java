package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import se.ataulnasar.pricingexception.application.IdempotencyPurge;
import se.ataulnasar.pricingexception.application.PricingProperties;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordRepository;

/**
 * Phase 4: races and idempotency over real HTTP (random port, JDK HttpClient, real H2).
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna,
 * inserted with JDBC) and its own Idempotency-Key. Nothing depends on a seed application's slot.
 * Seed users used (read only): rm-anna, rm-erik (RM), rev-sara (REVIEWER 50), rev-johan (REVIEWER 150).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyIT {

    private static final int PARALLEL = 10;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @Autowired IdempotencyRecordRepository records;
    @Autowired PricingProperties props;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    @Autowired IdempotencyPurge purgeBean;

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
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- INV-04 ----------

    @Test
    void INV04_parallel_creates_without_key_create_one_request() throws Exception {
        String app = newApp();
        List<Resp> results = parallel(PARALLEL, () -> create("rm-erik", createBody(app, 40), null));

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        List<Resp> losers = results.stream().filter(r -> r.status() != 201).toList();
        assertThat(losers).hasSize(PARALLEL - 1);
        for (Resp r : losers) {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.json("$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
        }
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    // ---------- INV-05 / INV-02 ----------

    /** Creates a 40 bps request (within both reviewers' limits) and decides it twice at once with If-Match "0". */
    private record Race(String location, List<Resp> results) {}

    private Race raceTwoDecisions() throws Exception {
        String app = newApp();
        Resp created = create("rm-anna", createBody(app, 40), null);
        assertThat(created.status()).isEqualTo(201);
        String location = created.header("Location");
        List<Resp> results = parallel(2, new Callable<Resp>() {
            private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Resp call() throws Exception {
                return n.getAndIncrement() == 0
                        ? decide(location, "rev-johan", "APPROVED", "\"0\"")
                        : decide(location, "rev-sara", "DECLINED", "\"0\"");
            }
        });
        return new Race(location, results);
    }

    private static void assertExactlyOneWinner(List<Resp> results) {
        assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
        Resp loser = results.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
        assertThat(loser.status()).isIn(409, 412);
        assertThat(loser.json("$.code")).isIn("CONCURRENT_MODIFICATION", "INVALID_TRANSITION", "VERSION_MISMATCH");
    }

    @Test
    void INV05_two_parallel_decisions_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            Race race = raceTwoDecisions();
            assertExactlyOneWinner(race.results());
            Resp after = get(race.location());
            assertThat(after.json("$.status")).isIn("APPROVED", "DECLINED");
            assertThat(after.header("ETag")).isEqualTo("\"1\"");
        }
    }

    @Test
    void INV02_losing_decision_writes_no_event() throws Exception {
        for (int round = 0; round < 5; round++) {
            Race race = raceTwoDecisions();
            assertExactlyOneWinner(race.results());
            Resp after = get(race.location());
            assertThat(JsonPath.<Integer>read(after.body(), "$.version")).isEqualTo(1);
            // SUBMITTED + the winner's decision, nothing from the loser
            assertThat(JsonPath.<List<?>>read(after.body(), "$.events")).hasSize(2);
            Resp winner = race.results().stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
            assertThat(JsonPath.<String>read(after.body(), "$.events[1].type")).isEqualTo(winner.json("$.status"));
            assertThat(JsonPath.<String>read(after.body(), "$.events[1].actorId"))
                    .isEqualTo(winner.json("$.decidedBy"));
        }
    }

    // ---------- idempotency ----------

    @Test
    void idempotent_parallel_creates_with_same_key_create_one_request_and_all_return_same_id() throws Exception {
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", body, key));

        assertThat(results).allMatch(r -> r.status() == 201);
        assertThat(results.stream().map(r -> r.json("$.id")).distinct()).hasSize(1);
        assertThat(results.stream().map(r -> r.header("Location")).distinct()).hasSize(1);
        // exactly one request did the work; every other call is a replay
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(Resp::replayed)).hasSize(PARALLEL - 1);
        assertThat(requestCountFor(app)).isEqualTo(1);
        Resp got = get(results.get(0).header("Location"));
        assertThat(JsonPath.<List<?>>read(got.body(), "$.events")).hasSize(1);
    }

    @Test
    void idempotent_replay_returns_same_status_location_etag_and_body() throws Exception {
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);

        Resp first = create("rm-anna", body, key);
        Resp second = create("rm-anna", body, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.replayed()).isFalse();
        assertThat(first.header("ETag")).isEqualTo("\"0\"");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.header("Location")).isEqualTo(first.header("Location")).isNotBlank();
        assertThat(second.header("ETag")).isEqualTo(first.header("ETag"));
        assertThat(second.bytes()).isEqualTo(first.bytes());

        assertThat(requestCountFor(app)).isEqualTo(1);
        Resp got = get(first.header("Location"));
        assertThat(JsonPath.<List<?>>read(got.body(), "$.events")).hasSize(1);
    }

    @Test
    void idempotent_replay_still_returns_the_first_response_after_the_request_was_decided() throws Exception {
        // The stored response is a snapshot: version 0 / PENDING, even though the request moved on.
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        Resp first = create("rm-anna", body, key);
        assertThat(decide(first.header("Location"), "rev-johan", "APPROVED", "\"0\"").status()).isEqualTo(200);

        Resp replay = create("rm-anna", body, key);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bytes()).isEqualTo(first.bytes());
        assertThat(replay.header("ETag")).isEqualTo("\"0\"");
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_same_key_different_body_is_422_IDEMPOTENCY_KEY_REUSED() throws Exception {
        String app = newApp();
        String key = newKey();
        assertThat(create("rm-anna", createBody(app, 40), key).status()).isEqualTo(201);

        Resp reused = create("rm-anna", createBody(app, 41), key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.header("Content-Type")).startsWith("application/problem+json");
        assertThat(reused.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reused.header("Idempotent-Replayed")).isNull();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_hash_ignores_unknown_fields_and_whitespace() throws Exception {
        String app = newApp();
        String key = newKey();
        Resp first = create("rm-anna", createBody(app, 40), key);
        String noisy = "  {\n  \"reason\" :  \"Customer has other offers\" ,\n \"extra\": {\"a\": [1, 2]},\n"
                + "  \"discountBps\" : 40,\n \"applicationId\"  :  \"" + app + "\"\n}  ";

        Resp second = create("rm-anna", noisy, key);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_keys_are_per_caller() throws Exception {
        String app1 = newApp();
        String app2 = newApp();
        String key = newKey();

        Resp anna = create("rm-anna", createBody(app1, 40), key);
        Resp erik = create("rm-erik", createBody(app2, 40), key);

        assertThat(anna.status()).isEqualTo(201);
        assertThat(erik.status()).isEqualTo(201);
        assertThat(erik.replayed()).isFalse();
        assertThat(erik.json("$.id")).isNotEqualTo(anna.json("$.id"));
        assertThat(anna.json("$.requestedBy")).isEqualTo("rm-anna");
        assertThat(erik.json("$.requestedBy")).isEqualTo("rm-erik");
        assertThat(requestCountFor(app1)).isEqualTo(1);
        assertThat(requestCountFor(app2)).isEqualTo(1);
    }

    @Test
    void idempotent_same_key_by_another_caller_with_same_body_never_replays_the_first_callers_response() throws Exception {
        // Same application, same body: erik's call must NOT be a replay of anna's; the slot is taken, so 409.
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        assertThat(create("rm-anna", body, key).status()).isEqualTo(201);

        Resp erik = create("rm-erik", body, key);

        assertThat(erik.status()).isEqualTo(409);
        assertThat(erik.json("$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
        assertThat(erik.replayed()).isFalse();
    }

    @Test
    void idempotent_failed_create_stores_nothing() throws Exception {
        String app = newApp();
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        String key = newKey();
        String body = createBody(app, 40);

        Resp failed = create("rm-anna", body, key);
        assertThat(failed.status()).isEqualTo(409);
        assertThat(failed.json("$.code")).isEqualTo("APPLICATION_NOT_OPEN");

        jdbc.update("UPDATE mortgage_application SET status = 'OPEN' WHERE id = ?", app);
        Resp retry = create("rm-anna", body, key);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.replayed()).isFalse();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_failed_validation_stores_nothing_so_the_key_can_be_used_with_a_corrected_body() throws Exception {
        String app = newApp();
        String key = newKey();
        Resp invalid = create("rm-anna", createBody(app, 0), key);
        assertThat(invalid.status()).isEqualTo(422);

        Resp corrected = create("rm-anna", createBody(app, 40), key);

        assertThat(corrected.status()).isEqualTo(201); // not IDEMPOTENCY_KEY_REUSED
        assertThat(corrected.replayed()).isFalse();
    }

    @Test
    void idempotency_key_longer_than_128_chars_is_400() throws Exception {
        String app = newApp();
        Resp r = create("rm-anna", createBody(app, 40), "k".repeat(129));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json("$.code")).isEqualTo("MALFORMED_REQUEST");
        assertThat(requestCountFor(app)).isZero();
    }

    @Test
    void idempotency_key_of_exactly_128_chars_is_accepted() throws Exception {
        String app = newApp();
        String key = "k".repeat(127) + "x";
        Resp first = create("rm-anna", createBody(app, 40), key);
        Resp second = create("rm-anna", createBody(app, 40), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
    }

    @Test
    void idempotency_blank_key_is_400() throws Exception {
        String app = newApp();
        Resp r = create("rm-anna", createBody(app, 40), "   ");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json("$.code")).isEqualTo("MALFORMED_REQUEST");
        assertThat(requestCountFor(app)).isZero();
    }

    // ---------- idempotency edge cases, purge and more races (merged from IdempotencyAttackIT) ----------

    private String createBody(String app, int discount, String reason) {
        return "{\"applicationId\":" + json.writeValueAsString(app)
                + ",\"discountBps\":" + discount + ",\"reason\":" + json.writeValueAsString(reason) + "}";
    }

    private int idemRows(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE idem_key = ?", Integer.class, key);
    }

    private int eventCount(String location) throws Exception {
        return JsonPath.<List<?>>read(get(location).body(), "$.events").size();
    }

    // ---------- body hash edge cases ----------

    @Test
    void idempotent_same_key_different_applicationId_is_422_and_creates_nothing_on_the_other_app() throws Exception {
        String app1 = newApp();
        String app2 = newApp();
        String key = newKey();
        assertThat(create("rm-anna", createBody(app1, 40), key).status()).isEqualTo(201);

        Resp r = create("rm-anna", createBody(app2, 40), key);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCountFor(app2)).isZero();
    }

    @Test
    void idempotent_applicationId_with_trailing_space_is_not_a_replay_and_creates_no_second_request() throws Exception {
        String app = newApp();
        String key = newKey();
        assertThat(create("rm-anna", createBody(app, 40), key).status()).isEqualTo(201);

        Resp r = create("rm-anna", createBody(app + " ", 40), key);

        assertThat(r.status()).isNotEqualTo(201);
        assertThat(r.replayed()).isFalse();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_applicationId_differing_in_case_does_not_create_a_second_active_request() throws Exception {
        // Different key, so no idempotency involved: INV-04 must still hold for a case variant of the id.
        String app = newApp();
        assertThat(create("rm-anna", createBody(app, 40), newKey()).status()).isEqualTo(201);

        Resp r = create("rm-anna", createBody(app.toLowerCase(), 40), newKey());

        assertThat(r.status()).isIn(404, 409); // not found (case-sensitive ids) or active exists
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_reason_in_nfc_vs_nfd_is_a_different_body_422() throws Exception {
        // JUDGEMENT: the hash is over code points, so "é" (U+00E9) and "e"+U+0301 are different bodies.
        // That is the conservative choice (the stored reason would differ), so we accept 422 and pin it.
        String app = newApp();
        String key = newKey();
        assertThat(create("rm-anna", createBody(app, 40, "café customer"), key).status()).isEqualTo(201);

        Resp r = create("rm-anna", createBody(app, 40, "café customer"), key);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void idempotent_discount_40_point_0_is_rejected_400_and_stores_nothing() throws Exception {
        String app = newApp();
        String key = newKey();
        String body = "{\"applicationId\":\"" + app + "\",\"discountBps\":40.0,\"reason\":\"x\"}";

        Resp r = create("rm-anna", body, key);

        assertThat(r.status()).isEqualTo(400);
        assertThat(idemRows(key)).isZero();
    }

    // ---------- key handling ----------

    @Test
    void idempotent_key_is_trimmed_so_padded_and_plain_key_are_the_same_key() throws Exception {
        // JUDGEMENT: the controller trims the key, so " k " and "k" are the same key.
        String app = newApp();
        String key = newKey();
        Resp first = create("rm-anna", createBody(app, 40), key);

        Resp second = create("rm-anna", createBody(app, 40), "  " + key + "  ");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_key_of_128_non_ascii_characters_works_and_replays() throws Exception {
        String app = newApp();
        String key = "é".repeat(128);
        Resp first = create("rm-anna", createBody(app, 40), key);
        Resp second = create("rm-anna", createBody(app, 40), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    // ---------- failures store nothing ----------

    @Test
    void idempotent_create_for_unknown_application_is_404_stores_nothing_and_key_stays_reusable() throws Exception {
        String app = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String key = newKey();

        Resp missing = create("rm-anna", createBody(app, 40), key);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(idemRows(key)).isZero();

        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", app, "CUST-" + app);
        Resp retry = create("rm-anna", createBody(app, 40), key);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void INV03_idempotent_create_by_reviewer_is_403_and_stores_nothing() throws Exception {
        String app = newApp();
        String key = newKey();

        Resp r = create("rev-sara", createBody(app, 40), key);

        assertThat(r.status()).isEqualTo(403);
        assertThat(idemRows(key)).isZero();
        assertThat(requestCountFor(app)).isZero();
    }

    @Test
    void idempotent_parallel_creates_with_invalid_body_are_all_422_and_store_nothing() throws Exception {
        String app = newApp();
        String key = newKey();
        List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", createBody(app, 0), key));

        assertThat(results).allMatch(r -> r.status() == 422);
        assertThat(idemRows(key)).isZero();
        assertThat(requestCountFor(app)).isZero();
    }

    @Test
    void idempotent_parallel_creates_for_unknown_application_are_all_404_and_store_nothing() throws Exception {
        String app = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String key = newKey();
        List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", createBody(app, 40), key));

        assertThat(results).allMatch(r -> r.status() == 404);
        assertThat(idemRows(key)).isZero();
    }

    // ---------- replay vs later state ----------

    @Test
    void idempotent_replay_after_withdrawal_returns_old_snapshot_and_deciding_with_it_is_refused() throws Exception {
        // JUDGEMENT: the replay is a snapshot (PENDING, ETag "0"). A client that acts on it gets a
        // refusal (409/412), never a decision on a withdrawn request. Consistent, but worth documenting.
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        Resp first = create("rm-anna", body, key);
        String location = first.header("Location");
        assertThat(send("POST", location + "/withdrawal", "rm-anna", null, "If-Match", "\"0\"").status()).isEqualTo(200);

        Resp replay = create("rm-anna", body, key);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.json("$.status")).isEqualTo("PENDING");
        assertThat(replay.header("ETag")).isEqualTo("\"0\"");
        Resp decision = decide(location, "rev-johan", "APPROVED", replay.header("ETag"));
        assertThat(decision.status()).isIn(409, 412);
        assertThat(get(location).json("$.status")).isEqualTo("WITHDRAWN");
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void INV04_keyed_create_then_unkeyed_create_on_same_application_is_409() throws Exception {
        String app = newApp();
        assertThat(create("rm-anna", createBody(app, 40), newKey()).status()).isEqualTo(201);

        Resp r = create("rm-anna", createBody(app, 40), null);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json("$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
    }

    // ---------- size of the stored body ----------

    @Test
    void idempotent_stored_body_fits_the_column_even_for_a_500_char_reason_of_control_characters() throws Exception {
        // Worst case for JSON size: every reason char escaped as \\u00XX (6 chars) = 3000 chars,
        // plus roughly 600 for the other fields and one event. Limit is VARCHAR(16000).
        String app = newApp();
        String key = newKey();
        String reason = "\u0001".repeat(500);

        Resp r = create("rm-anna", createBody(app, 40, reason), key);

        // JUDGEMENT: if the domain rejects control characters that is fine too (422); then nothing is stored.
        if (r.status() == 201) {
            Integer len = jdbc.queryForObject("SELECT LENGTH(response_body) FROM idempotency_record WHERE idem_key = ?",
                    Integer.class, key);
            assertThat(len).isLessThan(16000);
            assertThat(len).isGreaterThan(3000);
        } else {
            assertThat(r.status()).isEqualTo(422);
            assertThat(idemRows(key)).isZero();
        }
    }

    @Test
    void idempotent_500_emoji_reason_is_stored_and_replayed_byte_for_byte() throws Exception {
        String app = newApp();
        String key = newKey();
        String reason = "😀".repeat(250); // 500 UTF-16 chars

        Resp first = create("rm-anna", createBody(app, 40, reason), key);
        Resp second = create("rm-anna", createBody(app, 40, reason), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.json("$.reason")).isEqualTo(reason);
    }

    // ---------- purge ----------

    private void insertRecord(String caller, String key, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (caller_id, idem_key, request_hash, response_status, response_body, created_at)"
                + " VALUES (?, ?, 'h', 201, '{}', ?)", caller, key, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    @Test
    void purge_keeps_a_record_created_exactly_ttl_ago_and_removes_one_a_microsecond_older() throws Exception {
        // JUDGEMENT: the query uses created_at < now - ttl, so exactly TTL old is still replayable.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(props.idempotency().ttl());
        String keep = newKey();
        String drop = newKey();
        insertRecord("purge-test", keep, cutoff);
        insertRecord("purge-test", drop, cutoff.minus(1, ChronoUnit.MICROS));

        new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(
                s -> new IdempotencyPurge(records, props, Clock.fixed(now, ZoneOffset.UTC)).purgeExpired());

        assertThat(idemRows(keep)).isEqualTo(1);
        assertThat(idemRows(drop)).isZero();
    }

    @Test
    void purge_never_removes_a_fresh_record_of_a_running_application() throws Exception {
        String app = newApp();
        String key = newKey();
        assertThat(create("rm-anna", createBody(app, 40), key).status()).isEqualTo(201);

        purgeBean.purgeExpired();

        assertThat(create("rm-anna", createBody(app, 40), key).replayed()).isTrue();
    }

    @Test
    void purge_is_scheduled_with_the_configured_interval() {
        var tasks = scheduler.getScheduledTasks().stream().map(t -> t.getTask()).toList();
        var purge = tasks.stream()
                .filter(t -> t.getRunnable().toString().contains("IdempotencyPurge.purgeExpired"))
                .findFirst();

        assertThat(purge).as("purge task registered (@EnableScheduling on)").isPresent();
        assertThat(purge.get()).isInstanceOf(FixedDelayTask.class);
        assertThat(((FixedDelayTask) purge.get()).getIntervalDuration()).isEqualTo(props.idempotency().purgeInterval());
    }

    // ---------- more concurrency ----------

    @Test
    void idempotent_parallel_same_key_with_two_different_bodies_creates_exactly_one_request() throws Exception {
        for (int round = 0; round < 3; round++) {
            String app = newApp();
            String key = newKey();
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            List<Resp> results = parallel(PARALLEL,
                    () -> create("rm-anna", createBody(app, n.getAndIncrement() % 2 == 0 ? 40 : 41), key));

            assertThat(results.stream().filter(r -> r.status() == 201 && !r.replayed())).hasSize(1);
            for (Resp r : results) {
                if (r.status() == 201) continue;
                assertThat(r.status()).isEqualTo(422);
                assertThat(r.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            }
            assertThat(results.stream().filter(r -> r.status() == 201).map(r -> r.json("$.id")).distinct()).hasSize(1);
            assertThat(requestCountFor(app)).isEqualTo(1);
            assertThat(idemRows(key)).isEqualTo(1);
        }
    }

    @Test
    void INV04_parallel_same_key_by_two_callers_on_same_application_gives_one_201_and_one_409() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            String key = newKey();
            String body = createBody(app, 40);
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            List<Resp> results = parallel(2,
                    () -> create(n.getAndIncrement() == 0 ? "rm-anna" : "rm-erik", body, key));

            assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
            Resp loser = results.stream().filter(r -> r.status() != 201).findFirst().orElseThrow();
            assertThat(loser.status()).isEqualTo(409);
            assertThat(loser.json("$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
            assertThat(loser.replayed()).isFalse();
            assertThat(requestCountFor(app)).isEqualTo(1);
        }
    }

    @Test
    void INV05_parallel_decision_and_withdrawal_with_same_version_only_one_wins_and_one_event_is_added() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            Resp created = create("rm-anna", createBody(app, 40), null);
            String location = created.header("Location");
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            List<Resp> results = parallel(2, () -> n.getAndIncrement() == 0
                    ? decide(location, "rev-johan", "APPROVED", "\"0\"")
                    : send("POST", location + "/withdrawal", "rm-anna", null, "If-Match", "\"0\""));

            assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
            Resp loser = results.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 412);
            assertThat(eventCount(location)).isEqualTo(2);
            assertThat(get(location).json("$.status")).isIn("APPROVED", "WITHDRAWN");
        }
    }

    @Test
    void INV05_parallel_double_withdrawal_only_one_wins_and_one_event_is_added() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            String location = create("rm-anna", createBody(app, 40), null).header("Location");
            List<Resp> results = parallel(2,
                    () -> send("POST", location + "/withdrawal", "rm-anna", null, "If-Match", "\"0\""));

            assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
            Resp loser = results.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 412);
            assertThat(eventCount(location)).isEqualTo(2);
            assertThat(get(location).header("ETag")).isEqualTo("\"1\"");
        }
    }
}
