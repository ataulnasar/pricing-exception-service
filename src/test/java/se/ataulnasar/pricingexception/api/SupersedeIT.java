package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static se.ataulnasar.pricingexception.api.Api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import se.ataulnasar.pricingexception.api.Api.Resp;

/**
 * Supersede, the approved-discount endpoint for the mortgage process, and the reference data.
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna,
 * inserted with JDBC) and its own Idempotency-Key. Seed rows are only read (reference-data test:
 * 4 seed applications and 5 seed users). Seed users: rm-anna, rm-erik (RM), rev-sara (REVIEWER 50),
 * rev-johan (REVIEWER 150), mortgage-system (MORTGAGE_PROCESS). Tests can run in any order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupersedeIT {

    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-00000000dead";
    private static final String SUP30 = supBody(30);
    private static final String MALFORMED = "MALFORMED_REQUEST";
    private static final String NOT_PENDING = "INVALID_TRANSITION";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Api api;

    @BeforeEach
    void init() {
        api = Api.mock(mvc);
    }

    private String newApp() {
        return Api.newApp(jdbc);
    }

    /** Creates a PENDING request (version 0) as rm-anna on the given application and returns its id. */
    private String pending(String app, int discount) throws Exception {
        return api.createPending("rm-anna", app, discount);
    }

    private String sup(String from, long version, int discount) throws Exception {
        return api.supersedeOk(from, version, discount);
    }

    private Resp decide(String id, String user, String outcome, long version) throws Exception {
        return api.decide(id, user, outcome, q(version));
    }

    private Resp approvedDiscount(String user, String app) throws Exception {
        return api.approvedDiscount(user, app);
    }

    /** Status, ETag, event count and body of a request, to prove a failed call changed nothing. */
    private record Snap(String status, String etag, int events, String body) {}

    private Snap snap(String id) throws Exception {
        Resp r = api.fetch(id);
        return new Snap(r.str("$.status"), r.etag(), r.events(), r.body());
    }

    private int requestCount(String app) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pricing_exception_request WHERE application_id = ?",
                Integer.class, app);
    }

    private String activeSlotOf(String app) {
        List<String> ids = jdbc.queryForList(
                "SELECT CAST(id AS VARCHAR) FROM pricing_exception_request WHERE active_application_id = ?",
                String.class, app);
        assertThat(ids).as("active requests of " + app).hasSize(1);
        return ids.get(0);
    }

    // ---------- table: rejected supersedes ----------

    /** display, caller, target (%ID% = the PENDING request), If-Match, body, Idempotency-Key, status, code. */
    static Stream<Arguments> rejectedSupersedes() {
        String id = "%ID%";
        Stream<Arguments> malformedIfMatch = Stream.of("*", "0", "W/\"0\"", "\"00\"", "\"0\", \"1\"", "\"abc\"", "\"-1\"")
                .map(v -> Arguments.of("INV05 malformed If-Match " + v + " is 400", "rm-anna", id, v, SUP30, null, 400, MALFORMED));
        Stream<Arguments> badDiscounts = Stream.of(0, -1, 101, Integer.MAX_VALUE)
                .map(v -> Arguments.of("discount " + v + " is 422 INVALID_DISCOUNT", "rm-anna", id, q(0), supBody(v), null,
                        422, "INVALID_DISCOUNT"));
        Stream<Arguments> badBodies = Stream.of("{\"reason\":\"x\"}", "{\"discountBps\":30}", supBody(30, ""),
                        supBody(30, "   "), supBody(30, "x".repeat(501)))
                .map(b -> Arguments.of("invalid body " + (b.length() > 40 ? b.substring(0, 40) : b) + " is 422", "rm-anna", id,
                        q(0), b, null, 422, "VALIDATION_FAILED"));
        return Stream.of(
                Stream.of(
                        Arguments.of("INV03 another RM is 403 NOT_OWNER", "rm-erik", id, q(0), SUP30, null, 403, "NOT_OWNER"),
                        Arguments.of("reviewer sara is 403 WRONG_ROLE", "rev-sara", id, q(0), SUP30, null, 403, "WRONG_ROLE"),
                        Arguments.of("reviewer johan is 403 WRONG_ROLE", "rev-johan", id, q(0), SUP30, null, 403, "WRONG_ROLE"),
                        Arguments.of("mortgage process is 403 WRONG_ROLE", "mortgage-system", id, q(0), SUP30, null, 403, "WRONG_ROLE"),
                        Arguments.of("no user header is 401", null, id, q(0), SUP30, null, 401, "UNAUTHENTICATED"),
                        Arguments.of("unknown user is 401", "nobody", id, q(0), SUP30, null, 401, "UNAUTHENTICATED"),
                        Arguments.of("INV05 no If-Match is 428", "rm-anna", id, null, SUP30, null, 428, "IF_MATCH_REQUIRED"),
                        Arguments.of("INV05 stale If-Match is 412", "rm-anna", id, q(7), SUP30, null, 412, "VERSION_MISMATCH")),
                malformedIfMatch, badDiscounts, badBodies,
                Stream.of(
                        Arguments.of("reason null is 422", "rm-anna", id, q(0), "{\"discountBps\":30,\"reason\":null}", null, 422, null),
                        Arguments.of("not JSON is 400", "rm-anna", id, q(0), "not json", null, 400, MALFORMED),
                        // check order: body, then If-Match, then existence, then role
                        Arguments.of("order: invalid body without If-Match is 422 first", "rm-anna", id, null,
                                "{\"discountBps\":30,\"reason\":\"  \"}", null, 422, "VALIDATION_FAILED"),
                        Arguments.of("order: missing If-Match on an unknown id is 428 before 404", "rm-anna", UNKNOWN_ID, null,
                                SUP30, null, 428, "IF_MATCH_REQUIRED"),
                        Arguments.of("order: reviewer with stale version gets 412 before the role check", "rev-sara", id, q(5),
                                SUP30, null, 412, "VERSION_MISMATCH"),
                        Arguments.of("unknown request is 404", "rm-anna", UNKNOWN_ID, q(0), SUP30, null, 404, "REQUEST_NOT_FOUND"),
                        Arguments.of("unknown request with a key is 404", "rm-anna", UNKNOWN_ID, q(0), SUP30, "key-unknown-twice",
                                404, "REQUEST_NOT_FOUND"),
                        Arguments.of("same key again on the unknown request is 404 again", "rm-anna", UNKNOWN_ID, q(0), SUP30,
                                "key-unknown-twice", 404, "REQUEST_NOT_FOUND"),
                        Arguments.of("non-UUID id is 400", "rm-anna", "not-a-uuid", q(0), SUP30, null, 400, MALFORMED),
                        Arguments.of("Idempotency-Key of 129 chars is 400", "rm-anna", id, q(0), SUP30, "k".repeat(129), 400, MALFORMED),
                        Arguments.of("empty Idempotency-Key is 400", "rm-anna", id, q(0), SUP30, "", 400, MALFORMED)))
                .flatMap(s -> s);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedSupersedes")
    void rejected_supersedes_change_nothing(String display, String user, String target, String ifMatch, String body,
                                            String key, int status, String code) throws Exception {
        String app = newApp();
        String id = pending(app, 40);
        Snap before = snap(id);

        api.supersede(user, target.replace("%ID%", id), ifMatch, body, key).is(status, code);

        assertThat(snap(id)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
        assertThat(activeSlotOf(app)).isEqualTo(id);
    }

    @Test
    void supersede_with_wrong_type_discount_is_a_client_error() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        Snap before = snap(a);
        assertThat(api.supersede("rm-anna", a, q(0), "{\"discountBps\":\"abc\",\"reason\":\"r\"}", null).status()).isIn(400, 422);
        assertThat(snap(a)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void INV01_only_pending_request_can_be_superseded() throws Exception {
        String app = newApp();
        String pending = pending(app, 40);
        String replacement = api.supersede("rm-anna", pending, q(0), supBody(30), null).status(201).id();
        Snap replacementBefore = snap(replacement);

        String app2 = newApp(), app3 = newApp(), app4 = newApp();
        String approved = pending(app2, 40);
        decide(approved, "rev-sara", "APPROVED", 0).status(200);
        String declined = pending(app3, 40);
        decide(declined, "rev-sara", "DECLINED", 0).status(200);
        String withdrawn = pending(app4, 40);
        api.withdraw(withdrawn, "rm-anna", q(0)).status(200);

        // APPROVED, DECLINED, WITHDRAWN, and the already SUPERSEDED old request (version is 1 for all four)
        for (String finalId : List.of(approved, declined, withdrawn, pending)) {
            Snap before = snap(finalId);
            assertThat(before.etag()).isEqualTo(q(1));
            api.supersede("rm-anna", finalId, q(1), supBody(20), null).problem(409, NOT_PENDING);
            assertThat(snap(finalId)).as("old request %s unchanged", before.status()).isEqualTo(before);
        }
        assertThat(requestCount(app2)).isEqualTo(1);
        assertThat(requestCount(app3)).isEqualTo(1);
        assertThat(requestCount(app4)).isEqualTo(1);
        assertThat(requestCount(app)).isEqualTo(2);
        // the replacement (B) was not touched by the rejected calls and still holds the slot
        assertThat(snap(replacement)).isEqualTo(replacementBefore);
        assertThat(activeSlotOf(app)).isEqualTo(replacement);
    }

    @Test
    void INV01_no_endpoint_changes_discount_after_approval() throws Exception {
        String app = newApp();
        String id = pending(app, 40);
        decide(id, "rev-sara", "APPROVED", 0).status(200);
        Snap approved = snap(id);
        assertThat(approved.etag()).isEqualTo(q(1));

        decide(id, "rev-johan", "APPROVED", 1).problem(409, NOT_PENDING);
        decide(id, "rev-johan", "DECLINED", 1).problem(409, NOT_PENDING);
        api.withdraw(id, "rm-anna", q(1)).problem(409, NOT_PENDING);
        api.supersede("rm-anna", id, q(1), supBody(10), null).problem(409, NOT_PENDING);
        api.supersede("rm-anna", id, q(1), supBody(10), newKey()).problem(409, NOT_PENDING);

        Resp after = api.fetch(id).has("$.discountBps", 40, "$.approvedDiscountBps", 40, "$.status", "APPROVED");
        assertThat(after.body()).isEqualTo(approved.body());
        assertThat(requestCount(app)).isEqualTo(1);
        approvedDiscount("mortgage-system", app).status(200).has("$.discountBps", 40, "$.requestId", id);
    }

    @Test
    void INV01_supersede_links_old_and_new_both_ways_and_writes_both_events() throws Exception {
        String app = newApp();
        String oldId = pending(app, 40);

        Resp r = api.supersede("rm-anna", oldId, q(0), supBody(30, "Revised after talk"), null).status(201);
        String newId = r.id();
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(r.location()).isEqualTo(BASE + "/" + newId);
        assertThat(r.etag()).isEqualTo(q(0));
        assertThat(r.header("Idempotent-Replayed")).isNull();
        assertThat(r.map()).containsKey("supersededById");
        r.has("$.status", "PENDING", "$.supersedesId", oldId, "$.supersededById", null, "$.applicationId", app,
                "$.requestedBy", "rm-anna", "$.discountBps", 30, "$.reason", "Revised after talk", "$.events.length()", 1,
                "$.events[0].type", "SUBMITTED", "$.events[0].actorId", "rm-anna");

        Resp n = api.fetch(newId).has("$.supersedesId", oldId, "$.status", "PENDING");
        assertThat(n.etag()).isEqualTo(q(0));

        Resp o = api.fetch(oldId).has("$.status", "SUPERSEDED", "$.supersededById", newId, "$.decidedBy", null,
                "$.decidedAt", null, "$.discountBps", 40, "$.events.length()", 2, "$.events[0].type", "SUBMITTED",
                "$.events[1].type", "SUPERSEDED", "$.events[1].actorId", "rm-anna", "$.events[1].fromStatus", "PENDING",
                "$.events[1].toStatus", "SUPERSEDED");
        assertThat(o.etag()).isEqualTo(q(1));
        assertThat(o.map()).doesNotContainKey("activeApplicationId");

        Resp list = api.list("rm-erik", "applicationId=" + app);
        assertThat(list.body()).isNotEmpty();
        assertThat(list.strings("$[*].id")).hasSize(2);
        assertThat(list.strings("$[?(@.id=='" + oldId + "')].supersededById")).containsExactly(newId);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void supersededById_is_present_and_null_on_requests_that_were_not_superseded() throws Exception {
        String id = pending(newApp(), 40);
        assertThat(api.fetch(id).map()).containsKey("supersededById").containsEntry("supersededById", null);
        assertThat(decide(id, "rev-sara", "APPROVED", 0).map()).containsEntry("supersededById", null);
        assertThat(api.fetch("00000000-0000-0000-0000-000000000001").map()).containsEntry("supersededById", null); // seed
        List<Map<String, Object>> all = api.list("rm-anna", "").json();
        assertThat(all).isNotEmpty().allSatisfy(m -> assertThat(m).containsKey("supersededById"));
    }

    @Test
    void INV04_after_supersede_the_new_request_holds_the_active_slot() throws Exception {
        String app = newApp();
        String oldId = pending(app, 40);
        String newId = sup(oldId, 0, 30);

        assertThat(activeSlotOf(app)).isEqualTo(newId);
        api.create("rm-erik", app, 20).problem(409, "ACTIVE_REQUEST_EXISTS");
        assertThat(requestCount(app)).isEqualTo(2);

        decide(newId, "rev-sara", "APPROVED", 0).status(200).has("$.status", "APPROVED"); // sara limit 50 >= 30
        api.fetch(oldId).has("$.status", "SUPERSEDED");
        assertThat(activeSlotOf(app)).isEqualTo(newId);
    }

    @Test
    void INV05_supersede_stale_version_is_reported_before_not_pending() throws Exception {
        String id = pending(newApp(), 40);
        decide(id, "rev-sara", "DECLINED", 0).status(200);
        // version is 1 now: 0 is stale (412), 1 is current (409)
        api.supersede("rm-anna", id, q(0), SUP30, null).problem(412, "VERSION_MISMATCH");
        api.supersede("rm-anna", id, q(1), SUP30, null).problem(409, NOT_PENDING);
    }

    @Test
    void INV06_supersede_on_closed_application_is_409_and_old_stays_pending() throws Exception {
        String app = newApp();
        String id = pending(app, 40);
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        Snap before = snap(id);

        api.supersede("rm-anna", id, q(0), SUP30, null).problem(409, "APPLICATION_NOT_OPEN");

        assertThat(snap(id)).isEqualTo(before);
        assertThat(before.status()).isEqualTo("PENDING");
        assertThat(before.events()).isEqualTo(1);
        assertThat(requestCount(app)).isEqualTo(1);
        assertThat(activeSlotOf(app)).isEqualTo(id);
    }

    @Test
    void supersede_ignores_caller_supplied_identity_and_link_fields() throws Exception {
        String app = newApp();
        String id = pending(app, 40);
        String body = "{\"discountBps\":30,\"reason\":\"r\",\"requestedBy\":\"rm-erik\",\"applicationId\":\"MA-1003\","
                + "\"supersedesId\":\"" + UNKNOWN_ID + "\",\"status\":\"APPROVED\",\"decidedBy\":\"rev-sara\"}";
        api.supersede("rm-anna", id, q(0), body, null).status(201).has("$.requestedBy", "rm-anna", "$.applicationId", app,
                "$.supersedesId", id, "$.status", "PENDING", "$.decidedBy", null);
    }

    @Test
    void supersede_accepts_discount_1_and_100_and_reason_of_500_chars() throws Exception {
        String a = pending(newApp(), 40);
        String b = sup(a, 0, 100);
        Resp r = api.supersede("rm-anna", b, q(0), supBody(1, "x".repeat(500)), null).status(201);
        r.has("$.discountBps", 1);
        assertThat(r.str("$.reason")).hasSize(500);
    }

    @Test
    void supersede_with_identical_terms_is_accepted() throws Exception {
        // JUDGEMENT: the design does not forbid identical terms; pinned as allowed.
        String app = newApp();
        String a = pending(app, 40);
        api.supersede("rm-anna", a, q(0), supBody(40, "Customer has other offers"), null).status(201).has("$.discountBps", 40);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void supersede_reason_is_stored_and_returned_as_plain_text() throws Exception {
        String a = pending(newApp(), 40);
        String evil = "<script>alert(1)</script> åäö";
        Resp r = api.supersede("rm-anna", a, q(0), supBody(30, evil), null).status(201);
        assertThat(r.header("Content-Type")).startsWith("application/json");
        assertThat(api.fetch(r.id()).str("$.reason")).isEqualTo(evil);
    }

    @Test
    void idempotent_supersede_replays_first_response() throws Exception {
        String app = newApp();
        String oldId = pending(app, 40);
        String key = newKey();

        Resp first = api.supersede("rm-anna", oldId, q(0), SUP30, key).status(201);
        assertThat(first.header("Idempotent-Replayed")).isNull();
        assertThat(first.etag()).isEqualTo(q(0));
        String newId = first.id();
        assertThat(first.location()).isEqualTo(BASE + "/" + newId);

        Resp second = api.supersede("rm-anna", oldId, q(0), SUP30, key).status(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.location()).isEqualTo(first.location());
        assertThat(second.etag()).isEqualTo(first.etag());
        assertThat(second.bytes()).isEqualTo(first.bytes());

        // a third time, with different whitespace and field order: still the same body
        Resp third = api.supersede("rm-anna", oldId, q(0),
                "{ \"reason\" : \"Lower discount after review\", \"discountBps\" : 30 }", key).status(201);
        assertThat(third.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(third.bytes()).isEqualTo(first.bytes());

        assertThat(requestCount(app)).isEqualTo(2);
        assertThat(activeSlotOf(app)).isEqualTo(newId);
        Resp old = api.fetch(oldId);
        assertThat(old.events()).isEqualTo(2);
        assertThat(old.etag()).isEqualTo(q(1));
    }

    /** display, first call uses the second request too, If-Match, body of the reused-key call. */
    static Stream<Arguments> reusedKeyVariants() {
        return Stream.of(
                Arguments.of("different discount", false, q(0), supBody(31)),
                Arguments.of("different reason", false, q(0), supBody(30, "Another reason")),
                Arguments.of("different If-Match", false, q(1), SUP30),
                Arguments.of("different target", true, q(0), SUP30));
    }

    @ParameterizedTest(name = "idempotent_supersede_same_key_{0}_is_422")
    @MethodSource("reusedKeyVariants")
    void idempotent_supersede_same_key_with_a_different_request_is_422(String display, boolean otherTarget, String ifMatch,
                                                                       String body) throws Exception {
        String app1 = newApp(), app2 = newApp();
        String id1 = pending(app1, 40), id2 = pending(app2, 40);
        String key = newKey();
        api.supersede("rm-anna", id1, q(0), SUP30, key).status(201);
        Snap other = snap(id2);

        api.supersede("rm-anna", otherTarget ? id2 : id1, ifMatch, body, key).problem(422, "IDEMPOTENCY_KEY_REUSED");

        assertThat(requestCount(app1)).isEqualTo(2);
        assertThat(requestCount(app2)).isEqualTo(1);
        assertThat(snap(id2)).isEqualTo(other);
    }

    @Test
    void idempotent_failed_supersede_stores_nothing() throws Exception {
        String app = newApp();
        String oldId = pending(app, 40);
        String key = newKey();

        api.supersede("rm-anna", oldId, q(9), SUP30, key).problem(412, "VERSION_MISMATCH");
        assertThat(requestCount(app)).isEqualTo(1);
        // the same key with the correct If-Match is a fresh 201, not a replay and not IDEMPOTENCY_KEY_REUSED
        Resp ok = api.supersede("rm-anna", oldId, q(0), SUP30, key).status(201);
        assertThat(ok.header("Idempotent-Replayed")).isNull();
        assertThat(requestCount(app)).isEqualTo(2);

        // a domain failure (invalid discount) stores nothing either
        String id2 = pending(newApp(), 40);
        String key2 = newKey();
        api.supersede("rm-anna", id2, q(0), supBody(500), key2).problem(422, "INVALID_DISCOUNT");
        Resp again = api.supersede("rm-anna", id2, q(0), supBody(500), key2).problem(422, "INVALID_DISCOUNT");
        assertThat(again.header("Idempotent-Replayed")).isNull();
    }

    @Test
    void idempotent_supersede_keys_are_per_caller() throws Exception {
        String app = newApp();
        String oldId = pending(app, 40);
        String key = newKey();
        api.supersede("rm-anna", oldId, q(0), SUP30, key).status(201);

        // erik is not the owner; his use of the same key is a fresh call, not a replay of anna's response
        Resp r = api.supersede("rm-erik", oldId, q(1), SUP30, key);
        assertThat(r.header("Idempotent-Replayed")).isNull();
        assertThat(r.status()).isIn(403, 409);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotency_key_of_128_chars_is_accepted_on_supersede() throws Exception {
        String id = pending(newApp(), 40);
        api.supersede("rm-anna", id, q(0), SUP30, "k".repeat(128)).status(201);
    }

    @Test
    void idempotent_key_shared_between_create_and_supersede_is_not_a_replay() throws Exception {
        String app = newApp(), app2 = newApp();
        String key = newKey();
        String id = api.create("rm-anna", createBody(app, 40), key).status(201).id();
        api.supersede("rm-anna", id, q(0), supBody(40), key).problem(422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app)).isEqualTo(1);

        // and the other way round: a key used by a supersede cannot create
        String a = pending(app2, 40);
        String key2 = newKey();
        api.supersede("rm-anna", a, q(0), SUP30, key2).status(201);
        String app3 = newApp();
        api.create("rm-anna", createBody(app3, 40), key2).problem(422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app3)).isZero();
    }

    @Test
    void idempotent_supersede_key_reused_by_another_caller_on_pending_target_is_403_NOT_OWNER() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String key = newKey();
        api.supersede("rm-erik", a, q(0), SUP30, key).problem(403, "NOT_OWNER");
        api.supersede("rm-anna", a, q(0), SUP30, key).status(201);
        // erik's key is still not a replay of anna's result; A is at version 1 now, so the stale If-Match wins
        Resp again = api.supersede("rm-erik", a, q(0), SUP30, key).problem(412, "VERSION_MISMATCH");
        assertThat(again.header("Idempotent-Replayed")).isNull();
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_replay_does_not_supersede_twice_and_new_keys_are_rejected_after_the_first_success() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String key = newKey();
        api.supersede("rm-anna", a, q(0), SUP30, key).status(201);
        api.supersede("rm-anna", a, q(0), SUP30, newKey()).problem(412, "VERSION_MISMATCH");
        api.supersede("rm-anna", a, q(1), SUP30, newKey()).problem(409, NOT_PENDING);
        assertThat(api.supersede("rm-anna", a, q(0), SUP30, key).replayed()).isTrue();
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_replay_of_first_supersede_key_after_replacement_was_superseded_is_still_the_snapshot() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String key = newKey();
        Resp first = api.supersede("rm-anna", a, q(0), supBody(35), key).status(201);
        String c = sup(first.id(), 0, 30);

        Resp replay = api.supersede("rm-anna", a, q(0), supBody(35), key).status(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bytes()).isEqualTo(first.bytes());
        replay.has("$.status", "PENDING"); // snapshot; B is SUPERSEDED by now
        assertThat(requestCount(app)).isEqualTo(3);
        assertThat(activeSlotOf(app)).isEqualTo(c);
    }

    @Test
    void approved_discount_lifecycle_404_before_200_after() throws Exception {
        String app = newApp();
        approvedDiscount("mortgage-system", app).problem(404, "NO_APPROVED_DISCOUNT");
        String id = pending(app, 40);
        approvedDiscount("mortgage-system", app).problem(404, "NO_APPROVED_DISCOUNT");

        decide(id, "rev-sara", "APPROVED", 0).status(200);

        Resp r = approvedDiscount("mortgage-system", app).status(200);
        assertThat(r.header("Content-Type")).startsWith("application/json");
        assertThat(r.map()).containsOnlyKeys("applicationId", "discountBps", "requestId", "decidedBy", "decidedAt");
        r.has("$.applicationId", app, "$.discountBps", 40, "$.requestId", id, "$.decidedBy", "rev-sara");
        assertThat(r.str("$.decidedAt")).isEqualTo(api.fetch(id).str("$.decidedAt"));
    }

    @Test
    void approved_discount_other_roles_are_403_WRONG_ROLE() throws Exception {
        String app = newApp();
        decide(pending(app, 40), "rev-sara", "APPROVED", 0).status(200);

        for (String user : new String[] {"rm-anna", "rm-erik", "rev-sara", "rev-johan"}) {
            approvedDiscount(user, app).problem(403, "WRONG_ROLE");
        }
        approvedDiscount("rm-anna", "MA-DOES-NOT-EXIST").problem(403, "WRONG_ROLE");
        approvedDiscount(null, app).problem(401, "UNAUTHENTICATED");
        approvedDiscount("nobody", app).problem(401, "UNAUTHENTICATED");
    }

    /** display, how the application is prepared, expected code. */
    static Stream<Arguments> noApprovedDiscount() {
        return Stream.of(
                Arguments.of("unknown application is APPLICATION_NOT_FOUND", "unknown", "APPLICATION_NOT_FOUND"),
                Arguments.of("pending only", "pending", "NO_APPROVED_DISCOUNT"),
                Arguments.of("declined", "declined", "NO_APPROVED_DISCOUNT"),
                Arguments.of("withdrawn", "withdrawn", "NO_APPROVED_DISCOUNT"),
                Arguments.of("pending request was superseded (replacement still pending)", "superseded", "NO_APPROVED_DISCOUNT"),
                Arguments.of("closed application without approval", "closed", "NO_APPROVED_DISCOUNT"));
    }

    @ParameterizedTest(name = "approved_discount_is_404_{0}")
    @MethodSource("noApprovedDiscount")
    void approved_discount_is_404_unless_a_request_is_approved(String display, String state, String code) throws Exception {
        String app = state.equals("unknown") ? "MA-DOES-NOT-EXIST" : newApp();
        switch (state) {
            case "declined" -> decide(pending(app, 40), "rev-sara", "DECLINED", 0).status(200);
            case "withdrawn" -> api.withdraw(pending(app, 40), "rm-anna", q(0)).status(200);
            case "superseded" -> sup(pending(app, 40), 0, 30);
            case "closed" -> jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
            default -> { }
        }
        approvedDiscount("mortgage-system", app).problem(404, code);
    }

    @Test
    void approved_discount_still_served_after_application_is_closed() throws Exception {
        // JUDGEMENT: INV-06 limits create/approve only; the mortgage process may still read an existing approval.
        String app = newApp();
        decide(pending(app, 40), "rev-sara", "APPROVED", 0).status(200);
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        approvedDiscount("mortgage-system", app).status(200);
        Resp apps = api.get("rm-anna", "/api/applications");
        assertThat(apps.strings("$[?(@.id=='" + app + "')].status")).containsExactly("CLOSED");
    }

    @Test
    void reference_data_lists_applications_and_users() throws Exception {
        String app = newApp();

        Resp apps = api.get("rm-erik", "/api/applications").status(200);
        List<Map<String, Object>> appList = apps.json();
        assertThat(appList).extracting(m -> m.get("id")).contains("MA-1001", "MA-1002", "MA-1003", "MA-1004", app);
        for (Map<String, Object> m : appList) {
            assertThat(m).containsOnlyKeys("id", "customerRef", "standardRateBps", "status", "ownerRmId");
        }
        assertThat(byId(appList, "MA-1001")).containsEntry("customerRef", "CUST-0001").containsEntry("standardRateBps", 425)
                .containsEntry("status", "OPEN").containsEntry("ownerRmId", "rm-anna");
        assertThat(byId(appList, "MA-1004")).containsEntry("status", "CLOSED").containsEntry("ownerRmId", "rm-erik")
                .containsEntry("standardRateBps", 395);
        assertThat(byId(appList, app)).containsEntry("customerRef", "CUST-" + app).containsEntry("standardRateBps", 450)
                .containsEntry("status", "OPEN").containsEntry("ownerRmId", "rm-anna");

        for (String user : new String[] {"rm-anna", "rev-sara", "mortgage-system"}) {
            api.get(user, "/api/applications").status(200);
            api.get(user, "/api/users").status(200);
        }

        List<Map<String, Object>> userList = api.get("rm-anna", "/api/users").status(200).json();
        for (Map<String, Object> m : userList) {
            assertThat(m).containsOnlyKeys("id", "name", "role", "approvalLimitBps");
        }
        assertThat(userList).extracting(m -> m.get("id"), m -> m.get("role"), m -> m.get("approvalLimitBps")).contains(
                tuple("rm-anna", "RM", null), tuple("rm-erik", "RM", null), tuple("mortgage-system", "MORTGAGE_PROCESS", null),
                tuple("rev-sara", "REVIEWER", 50), tuple("rev-johan", "REVIEWER", 150));
        assertThat(byId(userList, "rm-anna")).containsEntry("name", "Anna Lind");
    }

    private static Map<String, Object> byId(List<Map<String, Object>> rows, String id) {
        return rows.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    /** path, user header (null = none). */
    static Stream<Arguments> unauthenticatedReferenceReads() {
        return Stream.of("/api/applications", "/api/users").flatMap(p ->
                Stream.of(Arguments.of(p, null), Arguments.of(p, "nobody")));
    }

    @ParameterizedTest(name = "reference_data_requires_a_known_user: {0} as {1}")
    @MethodSource("unauthenticatedReferenceReads")
    void reference_data_requires_a_known_user(String path, String user) throws Exception {
        api.get(user, path).problem(401, "UNAUTHENTICATED");
    }

    @Test
    void INV01_chain_A_B_C_links_active_slot_and_history() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String b = sup(a, 0, 35);
        String c = sup(b, 0, 30);

        Resp ra = api.fetch(a).has("$.supersededById", b, "$.supersedesId", null, "$.status", "SUPERSEDED");
        Resp rb = api.fetch(b).has("$.supersededById", c, "$.supersedesId", a, "$.status", "SUPERSEDED");
        Resp rc = api.fetch(c).has("$.supersededById", null, "$.supersedesId", b, "$.status", "PENDING");
        assertThat(ra.events()).isEqualTo(2);
        assertThat(rb.events()).isEqualTo(2); // submitted + superseded
        assertThat(rc.events()).isEqualTo(1);
        assertThat(rb.etag()).isEqualTo(q(1));
        assertThat(activeSlotOf(app)).isEqualTo(c);
        assertThat(requestCount(app)).isEqualTo(3);
    }

    @Test
    void INV01_chain_approving_the_last_replacement_gives_its_discount_to_the_mortgage_process() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String c = sup(sup(a, 0, 35), 0, 30);
        decide(c, "rev-johan", "APPROVED", 0).status(200);
        approvedDiscount("mortgage-system", app).status(200).has("$.discountBps", 30, "$.requestId", c, "$.decidedBy", "rev-johan");
        api.supersede("rm-anna", c, q(1), supBody(10), null).problem(409, NOT_PENDING);
        api.fetch(a).has("$.status", "SUPERSEDED");
    }

    @Test
    void list_filtered_by_SUPERSEDED_exposes_supersededById() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String b = sup(a, 0, 35);
        sup(b, 0, 30);
        Resp r = api.list("rm-erik", "status=SUPERSEDED&applicationId=" + app).status(200);
        assertThat(r.strings("$[*].status")).containsOnly("SUPERSEDED");
        assertThat(r.strings("$[*].id")).containsExactlyInAnyOrder(a, b);
        assertThat(r.strings("$[*].supersededById")).hasSize(2).doesNotContainNull();
    }

    @Test
    void mortgage_process_can_read_requests() throws Exception {
        // create, decide, withdraw and supersede are 403 for this role: see WorkflowIT tables and rejected_supersedes
        String a = pending(newApp(), 40);
        api.list("mortgage-system", "").status(200);
        api.get("mortgage-system", BASE + "/" + a).status(200);
    }

    @Test
    void INV02_a_chain_writes_exactly_one_event_per_status_change() throws Exception {
        String app = newApp();
        String a = pending(app, 40);
        String b = sup(a, 0, 35);
        sup(b, 0, 30);
        Integer eventRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_event e JOIN pricing_exception_request r ON e.request_id = r.id"
                        + " WHERE r.application_id = ?", Integer.class, app);
        assertThat(eventRows).isEqualTo(5); // A submit+supersede, B submit+supersede, C submit
    }
}
