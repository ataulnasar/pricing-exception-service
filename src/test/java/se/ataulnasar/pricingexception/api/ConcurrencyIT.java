package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.api.Api.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.ataulnasar.pricingexception.api.Api.Resp;
import se.ataulnasar.pricingexception.application.IdempotencyPurge;
import se.ataulnasar.pricingexception.application.PricingProperties;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordRepository;

/**
 * Races and idempotency over real HTTP (random port, JDK HttpClient via {@link Api#http}, real H2).
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna,
 * inserted with JDBC) and its own Idempotency-Key. Nothing depends on a seed application's slot.
 * Seed users used (read only): rm-anna, rm-erik (RM), rev-sara (REVIEWER 50), rev-johan (REVIEWER 150).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyIT {

    private static final int PARALLEL = 10;
    /** Contenders in the decision race: the same count the original attack test used. */
    private static final int DECISION_CONTENDERS = 8;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @Autowired IdempotencyRecordRepository records;
    @Autowired PricingProperties props;
    @Autowired PlatformTransactionManager txm;
    @Autowired IdempotencyPurge purgeBean;
    private Api api;

    @BeforeEach
    void init() {
        api = Api.http(port);
    }

    private String newApp() {
        return Api.newApp(jdbc);
    }

    private Resp create(String user, String body, String key) throws Exception {
        return api.create(user, body, key);
    }

    private int requestCountFor(String app) throws Exception {
        return api.list("rm-anna", "applicationId=" + app).status(200).num("$.length()");
    }

    private int idemRows(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE idem_key = ?", Integer.class, key);
    }

    private int eventCount(String location) throws Exception {
        return api.get("rm-anna", location).events();
    }

    private Resp withdrawAsAnna(String location) throws Exception {
        return api.withdraw(location, "rm-anna", q(0));
    }

    /** Asserts exactly one response has the OK status and returns the others. */
    private static List<Resp> losers(List<Resp> results, int okStatus) {
        assertThat(results.stream().filter(r -> r.status() == okStatus)).hasSize(1);
        return results.stream().filter(r -> r.status() != okStatus).toList();
    }

    private static void assertRaceLoser(Resp loser) {
        assertThat(loser.status()).isIn(409, 412);
    }

    @Test
    void INV04_parallel_creates_without_key_create_one_request() throws Exception {
        String app = newApp();
        List<Resp> results = parallel(PARALLEL, () -> create("rm-erik", createBody(app, 40), null));

        List<Resp> losers = losers(results, 201);
        assertThat(losers).hasSize(PARALLEL - 1);
        for (Resp r : losers) r.problem(409, "ACTIVE_REQUEST_EXISTS");
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void INV04_keyed_create_then_unkeyed_create_on_same_application_is_409() throws Exception {
        String app = newApp();
        create("rm-anna", createBody(app, 40), newKey()).status(201);
        create("rm-anna", createBody(app, 40), null).problem(409, "ACTIVE_REQUEST_EXISTS");
    }

    @Test
    void INV04_parallel_same_key_by_two_callers_on_same_application_gives_one_201_and_one_409() throws Exception {
        for (int round = 0; round < 5; round++) {
            String app = newApp();
            String key = newKey();
            String body = createBody(app, 40);
            AtomicInteger n = new AtomicInteger();
            List<Resp> results = parallel(2, () -> create(n.getAndIncrement() == 0 ? "rm-anna" : "rm-erik", body, key));

            Resp loser = losers(results, 201).get(0);
            loser.problem(409, "ACTIVE_REQUEST_EXISTS");
            assertThat(loser.replayed()).isFalse();
            assertThat(requestCountFor(app)).isEqualTo(1);
        }
    }

    /**
     * Creates a 40 bps request (within both reviewers' limits) and decides it {@link #DECISION_CONTENDERS}
     * times at once with If-Match "0": approvals by rev-johan and declines by rev-sara, alternating.
     */
    private record Race(String location, List<Resp> results) {}

    private Race raceDecisions() throws Exception {
        String location = create("rm-anna", createBody(newApp(), 40), null).status(201).location();
        AtomicInteger n = new AtomicInteger();
        List<Resp> results = parallel(DECISION_CONTENDERS, () -> n.getAndIncrement() % 2 == 0
                ? api.decide(location, "rev-johan", "APPROVED", q(0))
                : api.decide(location, "rev-sara", "DECLINED", q(0)));
        return new Race(location, results);
    }

    /** Exactly one 200; every other contender lost with 409 or 412 and a stable code. */
    private static void assertExactlyOneWinner(List<Resp> results) {
        List<Resp> losers = losers(results, 200);
        assertThat(losers).hasSize(DECISION_CONTENDERS - 1);
        for (Resp loser : losers) {
            assertRaceLoser(loser);
            assertThat(loser.str("$.code")).isIn("CONCURRENT_MODIFICATION", "INVALID_TRANSITION", "VERSION_MISMATCH");
        }
    }

    @Test
    void INV05_two_parallel_decisions_only_one_wins() throws Exception {
        for (int round = 0; round < 5; round++) {
            Race race = raceDecisions();
            assertExactlyOneWinner(race.results());
            Resp after = api.get("rm-anna", race.location());
            assertThat(after.str("$.status")).isIn("APPROVED", "DECLINED");
            assertThat(after.etag()).isEqualTo("\"1\"");
        }
    }

    @Test
    void INV02_losing_decision_writes_no_event() throws Exception {
        for (int round = 0; round < 5; round++) {
            Race race = raceDecisions();
            assertExactlyOneWinner(race.results());
            Resp after = api.get("rm-anna", race.location());
            after.has("$.version", 1);
            assertThat(after.events()).isEqualTo(2); // SUBMITTED + the winner's decision, nothing from the loser
            Resp winner = race.results().stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
            assertThat(after.str("$.events[1].type")).isEqualTo(winner.str("$.status"));
            assertThat(after.str("$.events[1].actorId")).isEqualTo(winner.str("$.decidedBy"));
        }
    }

    @Test
    void INV05_parallel_decision_and_withdrawal_with_same_version_only_one_wins_and_one_event_is_added() throws Exception {
        for (int round = 0; round < 5; round++) {
            String location = create("rm-anna", createBody(newApp(), 40), null).location();
            AtomicInteger n = new AtomicInteger();
            List<Resp> results = parallel(2, () -> n.getAndIncrement() == 0
                    ? api.decide(location, "rev-johan", "APPROVED", q(0))
                    : withdrawAsAnna(location));

            assertRaceLoser(losers(results, 200).get(0));
            assertThat(eventCount(location)).isEqualTo(2);
            assertThat(api.get("rm-anna", location).str("$.status")).isIn("APPROVED", "WITHDRAWN");
        }
    }

    @Test
    void INV05_parallel_double_withdrawal_only_one_wins_and_one_event_is_added() throws Exception {
        for (int round = 0; round < 5; round++) {
            String location = create("rm-anna", createBody(newApp(), 40), null).location();
            List<Resp> results = parallel(2, () -> withdrawAsAnna(location));

            assertRaceLoser(losers(results, 200).get(0));
            assertThat(eventCount(location)).isEqualTo(2);
            assertThat(api.get("rm-anna", location).etag()).isEqualTo("\"1\"");
        }
    }

    @Test
    void idempotent_parallel_creates_with_same_key_create_one_request_and_all_return_same_id() throws Exception {
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", body, key));

        assertThat(results).allMatch(r -> r.status() == 201);
        assertThat(results.stream().map(Resp::id).distinct()).hasSize(1);
        assertThat(results.stream().map(Resp::location).distinct()).hasSize(1);
        // exactly one request did the work; every other call is a replay
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().filter(Resp::replayed)).hasSize(PARALLEL - 1);
        assertThat(requestCountFor(app)).isEqualTo(1);
        assertThat(eventCount(results.get(0).location())).isEqualTo(1);
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
        assertThat(first.etag()).isEqualTo("\"0\"");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.location()).isEqualTo(first.location()).isNotBlank();
        assertThat(second.etag()).isEqualTo(first.etag());
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(requestCountFor(app)).isEqualTo(1);
        assertThat(eventCount(first.location())).isEqualTo(1);
    }

    @ParameterizedTest(name = "idempotent_replay_after_the_request_was_{0}_returns_the_first_snapshot")
    @ValueSource(strings = {"APPROVED", "WITHDRAWN"})
    void idempotent_replay_after_the_request_moved_on_returns_the_first_snapshot(String moveOn) throws Exception {
        // The stored response is a snapshot: version 0 / PENDING, even though the request moved on.
        // JUDGEMENT: a client that acts on the snapshot gets a refusal (409/412), never a second decision.
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        Resp first = create("rm-anna", body, key);
        String location = first.location();
        if (moveOn.equals("APPROVED")) api.decide(location, "rev-johan", "APPROVED", q(0)).status(200);
        else withdrawAsAnna(location).status(200);

        Resp replay = create("rm-anna", body, key).status(201);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bytes()).isEqualTo(first.bytes());
        replay.has("$.status", "PENDING");
        assertThat(replay.etag()).isEqualTo("\"0\"");
        assertThat(api.decide(location, "rev-johan", "DECLINED", replay.etag()).status()).isIn(409, 412);
        assertThat(api.get("rm-anna", location).str("$.status")).isEqualTo(moveOn);
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @ParameterizedTest(name = "idempotent_same_key_different_{0}_is_422_IDEMPOTENCY_KEY_REUSED")
    @ValueSource(strings = {"body", "applicationId"})
    void idempotent_same_key_with_a_different_request_is_422(String difference) throws Exception {
        String app1 = newApp(), app2 = newApp();
        String key = newKey();
        create("rm-anna", createBody(app1, 40), key).status(201);

        Resp reused = create("rm-anna", difference.equals("body") ? createBody(app1, 41) : createBody(app2, 40), key)
                .problem(422, "IDEMPOTENCY_KEY_REUSED");

        assertThat(reused.header("Idempotent-Replayed")).isNull();
        assertThat(requestCountFor(app1)).isEqualTo(1);
        assertThat(requestCountFor(app2)).isZero();
    }

    @Test
    void idempotent_hash_ignores_unknown_fields_and_whitespace() throws Exception {
        String app = newApp();
        String key = newKey();
        Resp first = create("rm-anna", createBody(app, 40), key);
        String noisy = "  {\n  \"reason\" :  \"Customer has other offers\" ,\n \"extra\": {\"a\": [1, 2]},\n"
                + "  \"discountBps\" : 40,\n \"applicationId\"  :  \"" + app + "\"\n}  ";

        Resp second = create("rm-anna", noisy, key).status(201);

        assertThat(second.replayed()).isTrue();
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_reason_in_nfc_vs_nfd_is_a_different_body_422() throws Exception {
        // JUDGEMENT: the hash is over code points, so "é" and "e"+U+0301 are different bodies.
        // That is the conservative choice (the stored reason would differ), so we accept 422 and pin it.
        String app = newApp();
        String key = newKey();
        create("rm-anna", createBody(app, 40, "café customer"), key).status(201);

        create("rm-anna", createBody(app, 40, "café customer"), key).problem(422, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void idempotent_applicationId_with_trailing_space_is_not_a_replay_and_creates_no_second_request() throws Exception {
        String app = newApp();
        String key = newKey();
        create("rm-anna", createBody(app, 40), key).status(201);

        Resp r = create("rm-anna", createBody(app + " ", 40), key);

        assertThat(r.status()).isNotEqualTo(201);
        assertThat(r.replayed()).isFalse();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_applicationId_differing_in_case_does_not_create_a_second_active_request() throws Exception {
        // Different key, so no idempotency involved: INV-04 must still hold for a case variant of the id.
        String app = newApp();
        create("rm-anna", createBody(app, 40), newKey()).status(201);

        Resp r = create("rm-anna", createBody(app.toLowerCase(), 40), newKey());

        assertThat(r.status()).isIn(404, 409); // not found (case-sensitive ids) or active exists
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void idempotent_discount_40_point_0_is_rejected_400_and_stores_nothing() throws Exception {
        String key = newKey();
        String body = "{\"applicationId\":\"" + newApp() + "\",\"discountBps\":40.0,\"reason\":\"x\"}";

        create("rm-anna", body, key).status(400);

        assertThat(idemRows(key)).isZero();
    }

    @Test
    void idempotent_keys_are_per_caller() throws Exception {
        String app1 = newApp(), app2 = newApp();
        String key = newKey();

        Resp anna = create("rm-anna", createBody(app1, 40), key).status(201);
        Resp erik = create("rm-erik", createBody(app2, 40), key).status(201);

        assertThat(erik.replayed()).isFalse();
        assertThat(erik.id()).isNotEqualTo(anna.id());
        anna.has("$.requestedBy", "rm-anna");
        erik.has("$.requestedBy", "rm-erik");
        assertThat(requestCountFor(app1)).isEqualTo(1);
        assertThat(requestCountFor(app2)).isEqualTo(1);
    }

    @Test
    void idempotent_same_key_by_another_caller_with_same_body_never_replays_the_first_callers_response() throws Exception {
        // Same application, same body: erik's call must NOT be a replay of anna's; the slot is taken, so 409.
        String app = newApp();
        String key = newKey();
        String body = createBody(app, 40);
        create("rm-anna", body, key).status(201);

        Resp erik = create("rm-erik", body, key).problem(409, "ACTIVE_REQUEST_EXISTS");

        assertThat(erik.replayed()).isFalse();
    }

    @Test
    void idempotent_key_is_trimmed_so_padded_and_plain_key_are_the_same_key() throws Exception {
        // JUDGEMENT: the controller trims the key, so " k " and "k" are the same key.
        String app = newApp();
        String key = newKey();
        create("rm-anna", createBody(app, 40), key).status(201);

        assertThat(create("rm-anna", createBody(app, 40), "  " + key + "  ").status(201).replayed()).isTrue();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @ParameterizedTest(name = "idempotency_key_of_{0}_chars_is_accepted_and_replays")
    @ValueSource(strings = {"128 ASCII", "128 non-ASCII"})
    void idempotency_key_of_exactly_128_chars_is_accepted(String kind) throws Exception {
        String app = newApp();
        String key = kind.contains("non-ASCII") ? "é".repeat(128) : "k".repeat(127) + "x";

        create("rm-anna", createBody(app, 40), key).status(201);
        assertThat(create("rm-anna", createBody(app, 40), key).status(201).replayed()).isTrue();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @ParameterizedTest(name = "idempotency_key_{0}_is_400")
    @ValueSource(strings = {"longer than 128 chars", "blank"})
    void idempotency_key_of_bad_length_is_400(String kind) throws Exception {
        String app = newApp();

        create("rm-anna", createBody(app, 40), kind.startsWith("blank") ? "   " : "k".repeat(129)).problem(400, "MALFORMED_REQUEST");

        assertThat(requestCountFor(app)).isZero();
    }

    @ParameterizedTest(name = "idempotent_failed_create_stores_nothing_when_the_{0}_and_the_key_works_after_the_fix")
    @ValueSource(strings = {"application is closed", "discount is invalid", "application is unknown"})
    void idempotent_failed_create_stores_nothing_and_the_key_works_after_the_cause_is_fixed(String cause) throws Exception {
        boolean closed = cause.contains("closed"), invalid = cause.contains("invalid");
        String app = invalid || closed ? newApp() : Api.newAppId();
        if (closed) jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        String key = newKey();

        create("rm-anna", createBody(app, invalid ? 0 : 40), key).is(closed ? 409 : invalid ? 422 : 404, closed ? "APPLICATION_NOT_OPEN" : null);
        assertThat(idemRows(key)).isZero();

        if (closed) jdbc.update("UPDATE mortgage_application SET status = 'OPEN' WHERE id = ?", app);
        if (!invalid && !closed) {
            jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                    + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", app, "CUST-" + app);
        }
        // the corrected call is a fresh 201, not a replay and not IDEMPOTENCY_KEY_REUSED
        assertThat(create("rm-anna", createBody(app, 40), key).status(201).replayed()).isFalse();
        assertThat(requestCountFor(app)).isEqualTo(1);
    }

    @Test
    void INV03_idempotent_create_by_reviewer_is_403_and_stores_nothing() throws Exception {
        String app = newApp();
        String key = newKey();

        create("rev-sara", createBody(app, 40), key).status(403);

        assertThat(idemRows(key)).isZero();
        assertThat(requestCountFor(app)).isZero();
    }

    @ParameterizedTest(name = "idempotent_parallel_creates_that_fail_with_{0}_all_fail_and_store_nothing")
    @ValueSource(ints = {422, 404})
    void idempotent_parallel_failing_creates_store_nothing(int expected) throws Exception {
        String app = expected == 422 ? newApp() : Api.newAppId(); // 422: invalid discount on a real application; 404: unknown application
        String key = newKey();
        List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", createBody(app, expected == 422 ? 0 : 40), key));

        assertThat(results).allMatch(r -> r.status() == expected);
        assertThat(idemRows(key)).isZero();
        if (expected == 422) assertThat(requestCountFor(app)).isZero();
    }

    @Test
    void idempotent_parallel_same_key_with_two_different_bodies_creates_exactly_one_request() throws Exception {
        for (int round = 0; round < 3; round++) {
            String app = newApp();
            String key = newKey();
            AtomicInteger n = new AtomicInteger();
            List<Resp> results = parallel(PARALLEL, () -> create("rm-anna", createBody(app, n.getAndIncrement() % 2 == 0 ? 40 : 41), key));

            assertThat(results.stream().filter(r -> r.status() == 201 && !r.replayed())).hasSize(1);
            for (Resp r : results) {
                if (r.status() != 201) r.problem(422, "IDEMPOTENCY_KEY_REUSED");
            }
            assertThat(results.stream().filter(r -> r.status() == 201).map(Resp::id).distinct()).hasSize(1);
            assertThat(requestCountFor(app)).isEqualTo(1);
            assertThat(idemRows(key)).isEqualTo(1);
        }
    }

    @Test
    void idempotent_stored_body_fits_the_column_even_for_a_500_char_reason_of_control_characters() throws Exception {
        // Worst case for JSON size: every reason char escaped as a 6-char unicode escape = 3000 chars,
        // plus roughly 600 for the other fields and one event. Limit is VARCHAR(16000).
        String key = newKey();

        Resp r = create("rm-anna", createBody(newApp(), 40, "\u0001".repeat(500)), key);

        // JUDGEMENT: if the domain rejects control characters that is fine too (422); then nothing is stored.
        if (r.status() == 201) {
            Integer len = jdbc.queryForObject("SELECT LENGTH(response_body) FROM idempotency_record WHERE idem_key = ?",
                    Integer.class, key);
            assertThat(len).isLessThan(16000).isGreaterThan(3000);
        } else {
            assertThat(r.status()).isEqualTo(422);
            assertThat(idemRows(key)).isZero();
        }
    }

    @Test
    void idempotent_500_emoji_reason_is_stored_and_replayed_byte_for_byte() throws Exception {
        String key = newKey();
        String reason = "😀".repeat(250); // 500 UTF-16 chars
        String body = createBody(newApp(), 40, reason);

        Resp first = create("rm-anna", body, key);
        Resp second = create("rm-anna", body, key).status(201);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.replayed()).isTrue();
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.str("$.reason")).isEqualTo(reason);
    }

    private void insertRecord(String caller, String key, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (caller_id, idem_key, request_hash, response_status, response_body, created_at)"
                + " VALUES (?, ?, 'h', 201, '{}', ?)", caller, key, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    @Test
    void purge_keeps_a_record_created_exactly_ttl_ago_and_removes_one_a_microsecond_older() {
        // JUDGEMENT: the query uses created_at < now - ttl, so exactly TTL old is still replayable.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(props.idempotency().ttl());
        String keep = newKey();
        String drop = newKey();
        insertRecord("purge-test", keep, cutoff);
        insertRecord("purge-test", drop, cutoff.minus(1, ChronoUnit.MICROS));

        new TransactionTemplate(txm).executeWithoutResult(
                s -> new IdempotencyPurge(records, props, Clock.fixed(now, ZoneOffset.UTC)).purgeExpired());

        assertThat(idemRows(keep)).isEqualTo(1);
        assertThat(idemRows(drop)).isZero();
    }

    @Test
    void purge_never_removes_a_fresh_record_of_a_running_application() throws Exception {
        String app = newApp();
        String key = newKey();
        create("rm-anna", createBody(app, 40), key).status(201);

        purgeBean.purgeExpired();

        assertThat(create("rm-anna", createBody(app, 40), key).replayed()).isTrue();
    }

    @Test
    void purge_is_scheduled_with_the_configured_interval() {
        var purge = scheduler.getScheduledTasks().stream().map(t -> t.getTask())
                .filter(t -> t.getRunnable().toString().contains("IdempotencyPurge.purgeExpired"))
                .findFirst();

        assertThat(purge).as("purge task registered (@EnableScheduling on)").isPresent();
        assertThat(purge.get()).isInstanceOf(FixedDelayTask.class);
        assertThat(((FixedDelayTask) purge.get()).getIntervalDuration()).isEqualTo(props.idempotency().purgeInterval());
    }
}
