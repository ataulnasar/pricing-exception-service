package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 5: supersede, the approved-discount endpoint for the mortgage process, and the reference data.
 *
 * <p>Independence: every test creates its OWN OPEN application (unique id "MA-T...", owner rm-anna,
 * inserted with JDBC) and its own Idempotency-Key. Seed rows are only read (reference-data test:
 * 4 seed applications and 5 seed users). Seed users: rm-anna, rm-erik (RM), rev-sara (REVIEWER 50),
 * rev-johan (REVIEWER 150), mortgage-system (MORTGAGE_PROCESS). Tests can run in any order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupersedeIT {

    private static final String BASE = "/api/pricing-exceptions";
    private static final UUID UNKNOWN_ID = UUID.fromString("00000000-0000-0000-0000-00000000dead");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String newApp() {
        String id = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", id, "CUST-" + id);
        return id;
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private static String createBody(String app, int discount) {
        return "{\"applicationId\":\"" + app + "\",\"discountBps\":" + discount
                + ",\"reason\":\"Customer has other offers\"}";
    }

    private static String supBody(int discount, String reason) {
        return "{\"discountBps\":" + discount + ",\"reason\":\"" + reason + "\"}";
    }

    private static String supBody(int discount) {
        return supBody(discount, "Lower discount after review");
    }

    private static String q(long version) {
        return "\"" + version + "\"";
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, String user) throws Exception {
        if (user != null) b.header("X-User-Id", user);
        return mvc.perform(b).andReturn().getResponse();
    }

    private static String text(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private static String json(MockHttpServletResponse r, String path) throws Exception {
        return JsonPath.read(text(r), path);
    }

    private static Map<String, Object> map(MockHttpServletResponse r) throws Exception {
        return JsonPath.read(text(r), "$");
    }

    /** Creates a PENDING request (version 0) as rm-anna and returns its id. */
    private String createPending(String app, int discount) throws Exception {
        return createPendingAs("rm-anna", app, discount);
    }

    private String createPendingAs(String user, String app, int discount) throws Exception {
        MockHttpServletResponse r = call(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(app, discount)), user);
        assertThat(r.getStatus()).as("create: " + text(r)).isEqualTo(201);
        return json(r, "$.id");
    }

    private MockHttpServletResponse supersede(String user, Object id, String ifMatch, String body, String key)
            throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + id + "/supersessions")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (key != null) b.header("Idempotency-Key", key);
        return call(b, user);
    }

    private MockHttpServletResponse decide(String id, String user, String outcome, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"" + outcome + "\",\"comment\":\"c\"}");
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return call(b, user);
    }

    private MockHttpServletResponse withdraw(String id, String user, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + id + "/withdrawal");
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return call(b, user);
    }

    private MockHttpServletResponse getRequest(Object id) throws Exception {
        MockHttpServletResponse r = call(get(BASE + "/" + id), "rm-anna");
        assertThat(r.getStatus()).isEqualTo(200);
        return r;
    }

    private MockHttpServletResponse approvedDiscount(String user, String app) throws Exception {
        return call(get("/api/applications/" + app + "/approved-discount"), user);
    }

    private static void assertProblem(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(text(r)).isEqualTo(status);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(json(r, "$.code")).isEqualTo(code);
    }

    /** Status, ETag and event count of a request, to prove a failed call changed nothing. */
    private record Snap(String status, String etag, int events, String body) {}

    private Snap snap(Object id) throws Exception {
        MockHttpServletResponse r = getRequest(id);
        List<?> events = JsonPath.read(text(r), "$.events");
        return new Snap(json(r, "$.status"), r.getHeader("ETag"), events.size(), text(r));
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

    // ---------- INV-01 ----------

    @Test
    void INV01_only_pending_request_can_be_superseded() throws Exception {
        String app = newApp();
        // PENDING: works
        String pending = createPending(app, 40);
        assertThat(supersede("rm-anna", pending, q(0), supBody(30), null).getStatus()).isEqualTo(201);

        // APPROVED
        String app2 = newApp();
        String approved = createPending(app2, 40);
        assertThat(decide(approved, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        // DECLINED
        String app3 = newApp();
        String declined = createPending(app3, 40);
        assertThat(decide(declined, "rev-sara", "DECLINED", q(0)).getStatus()).isEqualTo(200);
        // WITHDRAWN
        String app4 = newApp();
        String withdrawn = createPending(app4, 40);
        assertThat(withdraw(withdrawn, "rm-anna", q(0)).getStatus()).isEqualTo(200);
        // already SUPERSEDED: the old request of the first supersede (version is now 1)
        for (String finalId : List.of(approved, declined, withdrawn, pending)) {
            Snap before = snap(finalId);
            assertThat(before.etag()).isEqualTo(q(1));
            MockHttpServletResponse r = supersede("rm-anna", finalId, q(1), supBody(20), null);
            assertProblem(r, 409, "INVALID_TRANSITION");
            assertThat(snap(finalId)).as("old request %s unchanged", before.status()).isEqualTo(before);
        }
        assertThat(requestCount(app2)).isEqualTo(1);
        assertThat(requestCount(app3)).isEqualTo(1);
        assertThat(requestCount(app4)).isEqualTo(1);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void INV01_no_endpoint_changes_discount_after_approval() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertThat(decide(id, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        Snap approved = snap(id);
        assertThat(approved.etag()).isEqualTo(q(1));

        assertProblem(decide(id, "rev-johan", "APPROVED", q(1)), 409, "INVALID_TRANSITION");
        assertProblem(decide(id, "rev-johan", "DECLINED", q(1)), 409, "INVALID_TRANSITION");
        assertProblem(withdraw(id, "rm-anna", q(1)), 409, "INVALID_TRANSITION");
        assertProblem(supersede("rm-anna", id, q(1), supBody(10), null), 409, "INVALID_TRANSITION");
        assertProblem(supersede("rm-anna", id, q(1), supBody(10), newKey()), 409, "INVALID_TRANSITION");

        MockHttpServletResponse after = getRequest(id);
        assertThat(text(after)).isEqualTo(approved.body());
        assertThat(JsonPath.<Integer>read(text(after), "$.discountBps")).isEqualTo(40);
        assertThat(JsonPath.<Integer>read(text(after), "$.approvedDiscountBps")).isEqualTo(40);
        assertThat(json(after, "$.status")).isEqualTo("APPROVED");
        assertThat(requestCount(app)).isEqualTo(1);

        MockHttpServletResponse ad = approvedDiscount("mortgage-system", app);
        assertThat(ad.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(text(ad), "$.discountBps")).isEqualTo(40);
        assertThat(json(ad, "$.requestId")).isEqualTo(id);
    }

    @Test
    void INV01_supersede_links_old_and_new_both_ways_and_writes_both_events() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);

        MockHttpServletResponse r = supersede("rm-anna", oldId, q(0), supBody(30, "Revised after talk"), null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        String newId = json(r, "$.id");
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(r.getHeader("Location")).isEqualTo("/api/pricing-exceptions/" + newId);
        assertThat(r.getHeader("ETag")).isEqualTo(q(0));
        assertThat(r.getHeader("Idempotent-Replayed")).isNull();
        assertThat(json(r, "$.status")).isEqualTo("PENDING");
        assertThat(json(r, "$.supersedesId")).isEqualTo(oldId);
        assertThat(map(r)).containsKey("supersededById");
        assertThat(map(r).get("supersededById")).isNull();
        assertThat(json(r, "$.applicationId")).isEqualTo(app);
        assertThat(json(r, "$.requestedBy")).isEqualTo("rm-anna");
        assertThat(JsonPath.<Integer>read(text(r), "$.discountBps")).isEqualTo(30);
        assertThat(json(r, "$.reason")).isEqualTo("Revised after talk");
        assertThat(JsonPath.<Integer>read(text(r), "$.events.length()")).isEqualTo(1);
        assertThat(json(r, "$.events[0].type")).isEqualTo("SUBMITTED");
        assertThat(json(r, "$.events[0].actorId")).isEqualTo("rm-anna");

        // the new request, read back
        MockHttpServletResponse n = getRequest(newId);
        assertThat(json(n, "$.supersedesId")).isEqualTo(oldId);
        assertThat(json(n, "$.status")).isEqualTo("PENDING");
        assertThat(n.getHeader("ETag")).isEqualTo(q(0));

        // the old request
        MockHttpServletResponse o = getRequest(oldId);
        assertThat(o.getHeader("ETag")).isEqualTo(q(1));
        assertThat(json(o, "$.status")).isEqualTo("SUPERSEDED");
        assertThat(json(o, "$.supersededById")).isEqualTo(newId);
        assertThat(map(o).get("decidedBy")).isNull();
        assertThat(map(o).get("decidedAt")).isNull();
        assertThat(map(o)).doesNotContainKey("activeApplicationId");
        assertThat(JsonPath.<Integer>read(text(o), "$.discountBps")).isEqualTo(40);
        assertThat(JsonPath.<Integer>read(text(o), "$.events.length()")).isEqualTo(2);
        assertThat(json(o, "$.events[0].type")).isEqualTo("SUBMITTED");
        assertThat(json(o, "$.events[1].type")).isEqualTo("SUPERSEDED");
        assertThat(json(o, "$.events[1].actorId")).isEqualTo("rm-anna");
        assertThat(json(o, "$.events[1].fromStatus")).isEqualTo("PENDING");
        assertThat(json(o, "$.events[1].toStatus")).isEqualTo("SUPERSEDED");

        // the list shows both, with the link field
        MockHttpServletResponse list = call(get(BASE + "?applicationId=" + app), "rm-erik");
        assertThat(JsonPath.<List<?>>read(text(list), "$")).hasSize(2);
        assertThat(JsonPath.<List<String>>read(text(list), "$[?(@.id=='" + oldId + "')].supersededById"))
                .containsExactly(newId);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void supersededById_is_present_and_null_on_requests_that_were_not_superseded() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        MockHttpServletResponse g = getRequest(id);
        assertThat(map(g)).containsKey("supersededById");
        assertThat(map(g).get("supersededById")).isNull();

        MockHttpServletResponse decided = decide(id, "rev-sara", "APPROVED", q(0));
        assertThat(map(decided)).containsKey("supersededById");
        assertThat(map(decided).get("supersededById")).isNull();

        // seed requests (read only)
        MockHttpServletResponse seed = getRequest("00000000-0000-0000-0000-000000000001");
        assertThat(map(seed)).containsKey("supersededById");
        assertThat(map(seed).get("supersededById")).isNull();
        MockHttpServletResponse list = call(get(BASE), "rm-anna");
        List<Map<String, Object>> all = JsonPath.read(text(list), "$");
        assertThat(all).isNotEmpty().allSatisfy(m -> assertThat(m).containsKey("supersededById"));
    }

    // ---------- INV-04 ----------

    @Test
    void INV04_after_supersede_the_new_request_holds_the_active_slot() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        MockHttpServletResponse r = supersede("rm-anna", oldId, q(0), supBody(30), null);
        assertThat(r.getStatus()).isEqualTo(201);
        String newId = json(r, "$.id");

        assertThat(activeSlotOf(app)).isEqualTo(newId);
        assertProblem(call(post(BASE).contentType(MediaType.APPLICATION_JSON).content(createBody(app, 20)), "rm-erik"),
                409, "ACTIVE_REQUEST_EXISTS");
        assertThat(requestCount(app)).isEqualTo(2);

        // the new request can be decided normally (sara limit 50 >= 30)
        MockHttpServletResponse d = decide(newId, "rev-sara", "APPROVED", q(0));
        assertThat(d.getStatus()).as(text(d)).isEqualTo(200);
        assertThat(json(d, "$.status")).isEqualTo("APPROVED");
        // the old one stays superseded
        assertThat(json(getRequest(oldId), "$.status")).isEqualTo("SUPERSEDED");
        assertThat(activeSlotOf(app)).isEqualTo(newId);
    }

    // ---------- roles ----------

    @Test
    void supersede_by_another_rm_is_403_NOT_OWNER() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);
        assertProblem(supersede("rm-erik", id, q(0), supBody(30), null), 403, "NOT_OWNER");
        assertThat(snap(id)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void supersede_by_reviewer_is_403_WRONG_ROLE() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);
        assertProblem(supersede("rev-sara", id, q(0), supBody(30), null), 403, "WRONG_ROLE");
        assertProblem(supersede("rev-johan", id, q(0), supBody(30), null), 403, "WRONG_ROLE");
        assertProblem(supersede("mortgage-system", id, q(0), supBody(30), null), 403, "WRONG_ROLE");
        assertThat(snap(id)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void supersede_without_known_user_is_401() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertProblem(supersede(null, id, q(0), supBody(30), null), 401, "UNAUTHENTICATED");
        assertProblem(supersede("nobody", id, q(0), supBody(30), null), 401, "UNAUTHENTICATED");
        assertThat(snap(id).status()).isEqualTo("PENDING");
    }

    // ---------- INV-05: If-Match ----------

    @Test
    void INV05_supersede_without_if_match_is_428_and_stale_is_412() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);

        assertProblem(supersede("rm-anna", id, null, supBody(30), null), 428, "IF_MATCH_REQUIRED");
        assertProblem(supersede("rm-anna", id, q(7), supBody(30), null), 412, "VERSION_MISMATCH");
        assertThat(snap(id)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);

        // the correct version still works afterwards
        assertThat(supersede("rm-anna", id, q(0), supBody(30), null).getStatus()).isEqualTo(201);
    }

    @Test
    void INV05_supersede_malformed_if_match_is_400() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        for (String bad : new String[] {"*", "0", "W/\"0\"", "\"00\"", "\"0\", \"1\"", "\"abc\"", "\"-1\""}) {
            assertProblem(supersede("rm-anna", id, bad, supBody(30), null), 400, "MALFORMED_REQUEST");
        }
        assertThat(snap(id).status()).isEqualTo("PENDING");
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void INV05_supersede_stale_version_is_reported_before_not_pending() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertThat(decide(id, "rev-sara", "DECLINED", q(0)).getStatus()).isEqualTo(200);
        // version is 1 now: 0 is stale (412), 1 is current (409)
        assertProblem(supersede("rm-anna", id, q(0), supBody(30), null), 412, "VERSION_MISMATCH");
        assertProblem(supersede("rm-anna", id, q(1), supBody(30), null), 409, "INVALID_TRANSITION");
    }

    @Test
    void supersede_check_order_body_then_if_match_then_existence() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        // invalid body without If-Match: body first (422)
        assertProblem(supersede("rm-anna", id, null, "{\"discountBps\":30,\"reason\":\"  \"}", null),
                422, "VALIDATION_FAILED");
        // missing If-Match on an unknown id: 428 before 404
        assertProblem(supersede("rm-anna", UNKNOWN_ID, null, supBody(30), null), 428, "IF_MATCH_REQUIRED");
        // wrong role is checked after the version: reviewer with stale version gets 412
        assertProblem(supersede("rev-sara", id, q(5), supBody(30), null), 412, "VERSION_MISMATCH");
    }

    // ---------- INV-06 ----------

    @Test
    void INV06_supersede_on_closed_application_is_409_and_old_stays_pending() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        Snap before = snap(id);

        assertProblem(supersede("rm-anna", id, q(0), supBody(30), null), 409, "APPLICATION_NOT_OPEN");

        assertThat(snap(id)).isEqualTo(before);
        assertThat(before.status()).isEqualTo("PENDING");
        assertThat(before.events()).isEqualTo(1);
        assertThat(requestCount(app)).isEqualTo(1);
        assertThat(activeSlotOf(app)).isEqualTo(id);
    }

    // ---------- validation ----------

    @Test
    void supersede_with_invalid_discount_is_422_and_old_stays_pending() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);

        for (int bad : new int[] {0, -1, 101, Integer.MAX_VALUE}) {
            assertProblem(supersede("rm-anna", id, q(0), supBody(bad), null), 422, "INVALID_DISCOUNT");
        }
        assertThat(snap(id)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
        assertThat(activeSlotOf(app)).isEqualTo(id);
    }

    @Test
    void supersede_with_invalid_body_is_422_VALIDATION_FAILED_and_old_stays_pending() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);

        assertProblem(supersede("rm-anna", id, q(0), "{\"reason\":\"x\"}", null), 422, "VALIDATION_FAILED");
        assertProblem(supersede("rm-anna", id, q(0), "{\"discountBps\":30}", null), 422, "VALIDATION_FAILED");
        assertProblem(supersede("rm-anna", id, q(0), supBody(30, ""), null), 422, "VALIDATION_FAILED");
        assertProblem(supersede("rm-anna", id, q(0), supBody(30, "   "), null), 422, "VALIDATION_FAILED");
        assertProblem(supersede("rm-anna", id, q(0), supBody(30, "x".repeat(501)), null), 422, "VALIDATION_FAILED");
        assertProblem(supersede("rm-anna", id, q(0), "not json", null), 400, "MALFORMED_REQUEST");
        assertThat(snap(id)).isEqualTo(before);

        // 500 characters is the maximum and works
        MockHttpServletResponse ok = supersede("rm-anna", id, q(0), supBody(30, "x".repeat(500)), null);
        assertThat(ok.getStatus()).as(text(ok)).isEqualTo(201);
    }

    @Test
    void supersede_unknown_request_is_404() throws Exception {
        assertProblem(supersede("rm-anna", UNKNOWN_ID, q(0), supBody(30), null), 404, "REQUEST_NOT_FOUND");
        assertProblem(supersede("rm-anna", "not-a-uuid", q(0), supBody(30), null), 400, "MALFORMED_REQUEST");
    }

    @Test
    void supersede_ignores_caller_supplied_identity_and_link_fields() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        String body = "{\"discountBps\":30,\"reason\":\"r\",\"requestedBy\":\"rm-erik\",\"applicationId\":\"MA-1003\","
                + "\"supersedesId\":\"" + UNKNOWN_ID + "\",\"status\":\"APPROVED\",\"decidedBy\":\"rev-sara\"}";
        MockHttpServletResponse r = supersede("rm-anna", id, q(0), body, null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        assertThat(json(r, "$.requestedBy")).isEqualTo("rm-anna");
        assertThat(json(r, "$.applicationId")).isEqualTo(app);
        assertThat(json(r, "$.supersedesId")).isEqualTo(id);
        assertThat(json(r, "$.status")).isEqualTo("PENDING");
        assertThat(map(r).get("decidedBy")).isNull();
    }

    // ---------- idempotency ----------

    @Test
    void idempotent_supersede_replays_first_response() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        String key = newKey();

        MockHttpServletResponse first = supersede("rm-anna", oldId, q(0), supBody(30), key);
        assertThat(first.getStatus()).as(text(first)).isEqualTo(201);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();
        assertThat(first.getHeader("ETag")).isEqualTo(q(0));
        String newId = json(first, "$.id");
        assertThat(first.getHeader("Location")).isEqualTo("/api/pricing-exceptions/" + newId);

        MockHttpServletResponse second = supersede("rm-anna", oldId, q(0), supBody(30), key);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(second.getHeader("ETag")).isEqualTo(first.getHeader("ETag"));
        assertThat(second.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());

        // a third time, with different whitespace and field order: still the same body
        MockHttpServletResponse third = supersede("rm-anna", oldId, q(0),
                "{ \"reason\" : \"Lower discount after review\", \"discountBps\" : 30 }", key);
        assertThat(third.getStatus()).isEqualTo(201);
        assertThat(third.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(third.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());

        assertThat(requestCount(app)).isEqualTo(2);
        assertThat(activeSlotOf(app)).isEqualTo(newId);
        MockHttpServletResponse old = getRequest(oldId);
        assertThat(JsonPath.<Integer>read(text(old), "$.events.length()")).isEqualTo(2);
        assertThat(old.getHeader("ETag")).isEqualTo(q(1));
    }

    @Test
    void idempotent_supersede_same_key_different_body_is_422() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", oldId, q(0), supBody(30), key).getStatus()).isEqualTo(201);

        assertProblem(supersede("rm-anna", oldId, q(0), supBody(31), key), 422, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(supersede("rm-anna", oldId, q(0), supBody(30, "Another reason"), key), 422,
                "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_supersede_same_key_different_target_is_422() throws Exception {
        String app1 = newApp();
        String app2 = newApp();
        String id1 = createPending(app1, 40);
        String id2 = createPending(app2, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", id1, q(0), supBody(30), key).getStatus()).isEqualTo(201);

        assertProblem(supersede("rm-anna", id2, q(0), supBody(30), key), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(snap(id2).status()).isEqualTo("PENDING");
        assertThat(snap(id2).etag()).isEqualTo(q(0));
        assertThat(requestCount(app2)).isEqualTo(1);
    }

    @Test
    void idempotent_supersede_same_key_different_if_match_is_422() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", oldId, q(0), supBody(30), key).getStatus()).isEqualTo(201);

        assertProblem(supersede("rm-anna", oldId, q(1), supBody(30), key), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_failed_supersede_stores_nothing() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        String key = newKey();

        assertProblem(supersede("rm-anna", oldId, q(9), supBody(30), key), 412, "VERSION_MISMATCH");
        assertThat(requestCount(app)).isEqualTo(1);

        MockHttpServletResponse ok = supersede("rm-anna", oldId, q(0), supBody(30), key);
        assertThat(ok.getStatus()).as(text(ok)).isEqualTo(201);
        assertThat(ok.getHeader("Idempotent-Replayed")).isNull();
        assertThat(requestCount(app)).isEqualTo(2);

        // a domain failure (invalid discount) stores nothing either
        String app2 = newApp();
        String id2 = createPending(app2, 40);
        String key2 = newKey();
        assertProblem(supersede("rm-anna", id2, q(0), supBody(500), key2), 422, "INVALID_DISCOUNT");
        MockHttpServletResponse ok2 = supersede("rm-anna", id2, q(0), supBody(500), key2);
        assertProblem(ok2, 422, "INVALID_DISCOUNT");
        assertThat(ok2.getHeader("Idempotent-Replayed")).isNull();
    }

    @Test
    void idempotent_supersede_keys_are_per_caller() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", oldId, q(0), supBody(30), key).getStatus()).isEqualTo(201);

        // erik is not the owner; his use of the same key is a fresh call, not a replay of anna's response
        MockHttpServletResponse r = supersede("rm-erik", oldId, q(1), supBody(30), key);
        assertThat(r.getHeader("Idempotent-Replayed")).isNull();
        assertThat(r.getStatus()).isIn(403, 409);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotency_key_length_is_1_to_128_on_supersede() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertProblem(supersede("rm-anna", id, q(0), supBody(30), "k".repeat(129)), 400, "MALFORMED_REQUEST");
        assertProblem(supersede("rm-anna", id, q(0), supBody(30), ""), 400, "MALFORMED_REQUEST");
        assertThat(requestCount(app)).isEqualTo(1);
        MockHttpServletResponse ok = supersede("rm-anna", id, q(0), supBody(30), "k".repeat(128));
        assertThat(ok.getStatus()).as(text(ok)).isEqualTo(201);
    }

    @Test
    void idempotent_key_shared_between_create_and_supersede_is_not_a_replay() throws Exception {
        String app = newApp();
        String key = newKey();
        MockHttpServletResponse created = call(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(app, 40)).header("Idempotency-Key", key), "rm-anna");
        assertThat(created.getStatus()).isEqualTo(201);
        String id = json(created, "$.id");

        MockHttpServletResponse r = supersede("rm-anna", id, q(0), supBody(40), key);
        assertProblem(r, 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app)).isEqualTo(1);
    }

    // ---------- approved discount ----------

    @Test
    void approved_discount_lifecycle_404_before_200_after() throws Exception {
        String app = newApp();
        assertProblem(approvedDiscount("mortgage-system", app), 404, "NO_APPROVED_DISCOUNT");

        String id = createPending(app, 40);
        assertProblem(approvedDiscount("mortgage-system", app), 404, "NO_APPROVED_DISCOUNT");

        assertThat(decide(id, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);

        MockHttpServletResponse r = approvedDiscount("mortgage-system", app);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(200);
        assertThat(r.getContentType()).startsWith("application/json");
        Map<String, Object> m = map(r);
        assertThat(m).containsOnlyKeys("applicationId", "discountBps", "requestId", "decidedBy", "decidedAt");
        assertThat(m.get("applicationId")).isEqualTo(app);
        assertThat(m.get("discountBps")).isEqualTo(40);
        assertThat(m.get("requestId")).isEqualTo(id);
        assertThat(m.get("decidedBy")).isEqualTo("rev-sara");
        assertThat(m.get("decidedAt")).isNotNull();
        assertThat(java.time.Instant.parse((String) m.get("decidedAt"))).isNotNull();
        assertThat(m.get("decidedAt")).isEqualTo(json(getRequest(id), "$.decidedAt"));
    }

    @Test
    void approved_discount_other_roles_are_403_WRONG_ROLE() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertThat(decide(id, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);

        for (String user : new String[] {"rm-anna", "rm-erik", "rev-sara", "rev-johan"}) {
            assertProblem(approvedDiscount(user, app), 403, "WRONG_ROLE");
        }
        assertProblem(approvedDiscount(null, app), 401, "UNAUTHENTICATED");
        assertProblem(approvedDiscount("nobody", app), 401, "UNAUTHENTICATED");
    }

    @Test
    void approved_discount_unknown_application_is_404_APPLICATION_NOT_FOUND() throws Exception {
        assertProblem(approvedDiscount("mortgage-system", "MA-DOES-NOT-EXIST"), 404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void approved_discount_after_supersede_of_pending_is_still_404() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        assertThat(supersede("rm-anna", oldId, q(0), supBody(30), null).getStatus()).isEqualTo(201);
        assertProblem(approvedDiscount("mortgage-system", app), 404, "NO_APPROVED_DISCOUNT");
    }

    @Test
    void approved_discount_after_supersede_and_approval_is_the_new_request() throws Exception {
        String app = newApp();
        String oldId = createPending(app, 40);
        MockHttpServletResponse s = supersede("rm-anna", oldId, q(0), supBody(30), null);
        String newId = json(s, "$.id");
        assertThat(decide(newId, "rev-johan", "APPROVED", q(0)).getStatus()).isEqualTo(200);

        MockHttpServletResponse r = approvedDiscount("mortgage-system", app);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(text(r), "$.discountBps")).isEqualTo(30);
        assertThat(json(r, "$.requestId")).isEqualTo(newId);
        assertThat(json(r, "$.decidedBy")).isEqualTo("rev-johan");
    }

    @Test
    void approved_discount_declined_or_withdrawn_is_404_NO_APPROVED_DISCOUNT() throws Exception {
        String app1 = newApp();
        String declined = createPending(app1, 40);
        assertThat(decide(declined, "rev-sara", "DECLINED", q(0)).getStatus()).isEqualTo(200);
        assertProblem(approvedDiscount("mortgage-system", app1), 404, "NO_APPROVED_DISCOUNT");

        String app2 = newApp();
        String withdrawn = createPending(app2, 40);
        assertThat(withdraw(withdrawn, "rm-anna", q(0)).getStatus()).isEqualTo(200);
        assertProblem(approvedDiscount("mortgage-system", app2), 404, "NO_APPROVED_DISCOUNT");
    }

    @Test
    void approved_discount_is_not_available_for_a_closed_application_without_approval() throws Exception {
        String app = newApp();
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        assertProblem(approvedDiscount("mortgage-system", app), 404, "NO_APPROVED_DISCOUNT");
    }

    // ---------- reference data ----------

    @Test
    void reference_data_lists_applications_and_users() throws Exception {
        String app = newApp();

        MockHttpServletResponse apps = call(get("/api/applications"), "rm-erik");
        assertThat(apps.getStatus()).isEqualTo(200);
        List<Map<String, Object>> appList = JsonPath.read(text(apps), "$");
        assertThat(appList).extracting(m -> m.get("id")).contains("MA-1001", "MA-1002", "MA-1003", "MA-1004", app);
        for (Map<String, Object> m : appList) {
            assertThat(m).containsOnlyKeys("id", "customerRef", "standardRateBps", "status", "ownerRmId");
        }
        Map<String, Object> ma1001 = appList.stream().filter(m -> "MA-1001".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(ma1001).containsEntry("customerRef", "CUST-0001").containsEntry("standardRateBps", 425)
                .containsEntry("status", "OPEN").containsEntry("ownerRmId", "rm-anna");
        Map<String, Object> ma1004 = appList.stream().filter(m -> "MA-1004".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(ma1004).containsEntry("status", "CLOSED").containsEntry("ownerRmId", "rm-erik")
                .containsEntry("standardRateBps", 395);
        Map<String, Object> mine = appList.stream().filter(m -> app.equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(mine).containsEntry("customerRef", "CUST-" + app).containsEntry("standardRateBps", 450)
                .containsEntry("status", "OPEN").containsEntry("ownerRmId", "rm-anna");

        for (String user : new String[] {"rm-anna", "rev-sara", "mortgage-system"}) {
            assertThat(call(get("/api/applications"), user).getStatus()).isEqualTo(200);
            assertThat(call(get("/api/users"), user).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse users = call(get("/api/users"), "rm-anna");
        assertThat(users.getStatus()).isEqualTo(200);
        List<Map<String, Object>> userList = JsonPath.read(text(users), "$");
        assertThat(userList).extracting(m -> m.get("id"))
                .contains("rm-anna", "rm-erik", "rev-sara", "rev-johan", "mortgage-system");
        for (Map<String, Object> m : userList) {
            assertThat(m).containsOnlyKeys("id", "name", "role", "approvalLimitBps");
        }
        assertThat(userById(userList, "rm-anna")).containsEntry("name", "Anna Lind").containsEntry("role", "RM");
        assertThat(userById(userList, "rm-anna").get("approvalLimitBps")).isNull();
        assertThat(userById(userList, "rm-erik").get("approvalLimitBps")).isNull();
        assertThat(userById(userList, "mortgage-system")).containsEntry("role", "MORTGAGE_PROCESS");
        assertThat(userById(userList, "mortgage-system").get("approvalLimitBps")).isNull();
        assertThat(userById(userList, "rev-sara")).containsEntry("role", "REVIEWER").containsEntry("approvalLimitBps", 50);
        assertThat(userById(userList, "rev-johan")).containsEntry("approvalLimitBps", 150);
    }

    private static Map<String, Object> userById(List<Map<String, Object>> users, String id) {
        return users.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void reference_data_requires_a_known_user() throws Exception {
        assertProblem(call(get("/api/applications"), null), 401, "UNAUTHENTICATED");
        assertProblem(call(get("/api/users"), null), 401, "UNAUTHENTICATED");
        assertProblem(call(get("/api/applications"), "nobody"), 401, "UNAUTHENTICATED");
        assertProblem(call(get("/api/users"), "nobody"), 401, "UNAUTHENTICATED");
    }
}
