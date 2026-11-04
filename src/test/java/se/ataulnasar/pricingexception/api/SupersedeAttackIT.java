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

/** Phase 5 attack: chains, boundaries, idempotency edges, roles and the approved-discount read. Own app per test. */
@SpringBootTest
@AutoConfigureMockMvc
class SupersedeAttackIT {

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


    private String sup(String from, long version, int discount) throws Exception {
        MockHttpServletResponse r = supersede("rm-anna", from, q(version), supBody(discount), null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        return json(r, "$.id");
    }

    private static int events(MockHttpServletResponse r) throws Exception {
        return JsonPath.<Integer>read(text(r), "$.events.length()");
    }

    // ---------- chains ----------

    @Test
    void INV01_chain_A_B_C_links_active_slot_and_history() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        String c = sup(b, 0, 30);

        MockHttpServletResponse ra = getRequest(a), rb = getRequest(b), rc = getRequest(c);
        assertThat(json(ra, "$.supersededById")).isEqualTo(b);
        assertThat(json(rb, "$.supersededById")).isEqualTo(c);
        assertThat(JsonPath.<Object>read(text(rc), "$.supersededById")).isNull();
        assertThat(JsonPath.<Object>read(text(ra), "$.supersedesId")).isNull();
        assertThat(json(rb, "$.supersedesId")).isEqualTo(a);
        assertThat(json(rc, "$.supersedesId")).isEqualTo(b);
        assertThat(json(ra, "$.status")).isEqualTo("SUPERSEDED");
        assertThat(json(rb, "$.status")).isEqualTo("SUPERSEDED");
        assertThat(json(rc, "$.status")).isEqualTo("PENDING");
        assertThat(events(ra)).isEqualTo(2);
        assertThat(events(rb)).isEqualTo(2); // submitted + superseded
        assertThat(events(rc)).isEqualTo(1);
        assertThat(rb.getHeader("ETag")).isEqualTo(q(1));
        assertThat(activeSlotOf(app)).isEqualTo(c);
        assertThat(requestCount(app)).isEqualTo(3);
    }

    @Test
    void INV01_chain_approving_the_last_replacement_gives_its_discount_to_the_mortgage_process() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        String c = sup(b, 0, 30);
        assertThat(decide(c, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        MockHttpServletResponse r = approvedDiscount("mortgage-system", app);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(text(r), "$.discountBps")).isEqualTo(30);
        assertThat(json(r, "$.requestId")).isEqualTo(c);
        assertProblem(supersede("rm-anna", c, q(1), supBody(10), null), 409, "INVALID_TRANSITION");
        assertThat(json(getRequest(a), "$.status")).isEqualTo("SUPERSEDED");
    }

    @Test
    void INV01_supersede_of_already_superseded_original_is_409_and_creates_nothing() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        Snap beforeA = snap(a), beforeB = snap(b);
        assertProblem(supersede("rm-anna", a, q(1), supBody(20), null), 409, "INVALID_TRANSITION");
        assertThat(snap(a)).isEqualTo(beforeA);
        assertThat(snap(b)).isEqualTo(beforeB);
        assertThat(requestCount(app)).isEqualTo(2);
        assertThat(activeSlotOf(app)).isEqualTo(b);
    }

    @Test
    void idempotent_replay_of_first_supersede_key_after_replacement_was_superseded_is_still_the_snapshot() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String key = newKey();
        MockHttpServletResponse first = supersede("rm-anna", a, q(0), supBody(35), key);
        assertThat(first.getStatus()).isEqualTo(201);
        String b = json(first, "$.id");
        String c = sup(b, 0, 30);

        MockHttpServletResponse replay = supersede("rm-anna", a, q(0), supBody(35), key);
        assertThat(replay.getStatus()).as(text(replay)).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
        assertThat(json(replay, "$.status")).isEqualTo("PENDING"); // snapshot; B is SUPERSEDED by now
        assertThat(requestCount(app)).isEqualTo(3);
        assertThat(activeSlotOf(app)).isEqualTo(c);
    }

    @Test
    void replacement_keeps_the_requester_and_starts_fresh() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        MockHttpServletResponse rb = getRequest(b);
        assertThat(json(rb, "$.requestedBy")).isEqualTo("rm-anna");
        assertThat(json(rb, "$.applicationId")).isEqualTo(app);
        assertThat(json(rb, "$.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<Object>read(text(rb), "$.decidedBy")).isNull();
        assertThat(rb.getHeader("ETag")).isEqualTo(q(0));
    }

    @Test
    void supersede_with_identical_terms_is_accepted() throws Exception {
        // JUDGEMENT: the design says supersede is for changing the discount but does not forbid identical
        // terms; pinned as allowed (a new request is created, the old one becomes SUPERSEDED).
        String app = newApp();
        String a = createPending(app, 40);
        MockHttpServletResponse r = supersede("rm-anna", a, q(0), supBody(40, "Customer has other offers"), null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(text(r), "$.discountBps")).isEqualTo(40);
        assertThat(requestCount(app)).isEqualTo(2);
    }

    // ---------- boundaries ----------

    @Test
    void supersede_accepts_discount_1_and_100_and_reason_of_500_chars() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 100);
        MockHttpServletResponse r = supersede("rm-anna", b, q(0), supBody(1, "x".repeat(500)), null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(text(r), "$.discountBps")).isEqualTo(1);
        assertThat(json(r, "$.reason")).hasSize(500);
    }

    @Test
    void supersede_rejects_reason_of_501_chars_blank_null_and_wrong_type_and_changes_nothing() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        Snap before = snap(a);
        assertThat(supersede("rm-anna", a, q(0), supBody(30, "x".repeat(501)), null).getStatus()).isEqualTo(422);
        assertThat(supersede("rm-anna", a, q(0), supBody(30, "   "), null).getStatus()).isEqualTo(422);
        assertThat(supersede("rm-anna", a, q(0), "{\"discountBps\":30,\"reason\":null}", null).getStatus())
                .isEqualTo(422);
        assertThat(supersede("rm-anna", a, q(0), "{\"discountBps\":\"abc\",\"reason\":\"r\"}", null).getStatus())
                .isIn(400, 422);
        assertThat(snap(a)).isEqualTo(before);
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void supersede_reason_is_stored_and_returned_as_plain_text() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String evil = "<script>alert(1)</script> \\u00e5\\u00e4\\u00f6";
        MockHttpServletResponse r = supersede("rm-anna", a, q(0), supBody(30, evil), null);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        assertThat(r.getContentType()).startsWith("application/json");
        assertThat(json(getRequest(json(r, "$.id")), "$.reason")).isEqualTo("<script>alert(1)</script> åäö");
    }

    // ---------- idempotency edges ----------

    @Test
    void idempotent_key_after_a_stale_412_with_correct_if_match_is_a_fresh_201() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String key = newKey();
        MockHttpServletResponse stale = supersede("rm-anna", a, q(5), supBody(30), key);
        assertThat(stale.getStatus()).isEqualTo(412);
        MockHttpServletResponse r = supersede("rm-anna", a, q(0), supBody(30), key);
        assertThat(r.getStatus()).as(text(r)).isEqualTo(201);
        assertThat(r.getHeader("Idempotent-Replayed")).isNull();
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_supersede_key_reused_for_create_is_422_and_creates_nothing() throws Exception {
        String app = newApp(), app2 = newApp();
        String a = createPending(app, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", a, q(0), supBody(30), key).getStatus()).isEqualTo(201);
        MockHttpServletResponse r = call(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(app2, 40)).header("Idempotency-Key", key), "rm-anna");
        assertProblem(r, 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(requestCount(app2)).isZero();
    }

    @Test
    void idempotent_supersede_key_reused_by_another_caller_on_pending_target_is_403_NOT_OWNER() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String key = newKey();
        MockHttpServletResponse r = supersede("rm-erik", a, q(0), supBody(30), key);
        assertProblem(r, 403, "NOT_OWNER");
        assertThat(supersede("rm-anna", a, q(0), supBody(30), key).getStatus()).isEqualTo(201);
        // erik's key is still not a replay of anna's result; A is at version 1 now, so the stale If-Match wins
        MockHttpServletResponse again = supersede("rm-erik", a, q(0), supBody(30), key);
        assertProblem(again, 412, "VERSION_MISMATCH");
        assertThat(again.getHeader("Idempotent-Replayed")).isNull();
        assertThat(requestCount(app)).isEqualTo(2);
    }

    @Test
    void idempotent_replay_does_not_supersede_twice_and_new_keys_are_rejected_after_the_first_success() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String key = newKey();
        assertThat(supersede("rm-anna", a, q(0), supBody(30), key).getStatus()).isEqualTo(201);
        assertProblem(supersede("rm-anna", a, q(0), supBody(30), newKey()), 412, "VERSION_MISMATCH");
        assertProblem(supersede("rm-anna", a, q(1), supBody(30), newKey()), 409, "INVALID_TRANSITION");
        assertThat(supersede("rm-anna", a, q(0), supBody(30), key).getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(requestCount(app)).isEqualTo(2);
    }

    // ---------- list and reads ----------

    @Test
    void list_filtered_by_SUPERSEDED_exposes_supersededById() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        sup(b, 0, 30);
        MockHttpServletResponse r = call(get(BASE + "?status=SUPERSEDED&applicationId=" + app), "rm-erik");
        assertThat(r.getStatus()).isEqualTo(200);
        List<Map<String, Object>> list = JsonPath.read(text(r), "$");
        assertThat(list).hasSize(2);
        for (Map<String, Object> m : list) {
            assertThat(m.get("status")).isEqualTo("SUPERSEDED");
            assertThat(m.get("supersededById")).isNotNull();
        }
        assertThat(list).extracting(m -> m.get("id")).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void approved_discount_body_has_exactly_five_fields() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        assertThat(decide(b, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        MockHttpServletResponse r = approvedDiscount("mortgage-system", app);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(map(r)).containsOnlyKeys("applicationId", "discountBps", "requestId", "decidedBy", "decidedAt");
        assertThat(json(r, "$.applicationId")).isEqualTo(app);
    }

    @Test
    void approved_discount_is_403_for_every_role_except_mortgage_process() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        assertThat(decide(a, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        for (String user : List.of("rm-anna", "rm-erik", "rev-sara", "rev-johan")) {
            assertProblem(approvedDiscount(user, app), 403, "WRONG_ROLE");
        }
        assertProblem(approvedDiscount("rm-anna", "MA-DOES-NOT-EXIST"), 403, "WRONG_ROLE");
    }

    @Test
    void approved_discount_still_served_after_application_is_closed() throws Exception {
        // JUDGEMENT: INV-06 limits create/approve only; the mortgage process may still read an existing approval.
        String app = newApp();
        String a = createPending(app, 40);
        assertThat(decide(a, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        assertThat(approvedDiscount("mortgage-system", app).getStatus()).isEqualTo(200);
        MockHttpServletResponse apps = call(get("/api/applications"), "rm-anna");
        List<Map<String, Object>> list = JsonPath.read(text(apps), "$");
        assertThat(list.stream().filter(m -> app.equals(m.get("id"))).findFirst().orElseThrow())
                .containsEntry("status", "CLOSED");
    }

    // ---------- roles ----------

    @Test
    void mortgage_process_can_read_but_cannot_create_decide_withdraw_or_supersede() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        assertThat(call(get(BASE), "mortgage-system").getStatus()).isEqualTo(200);
        assertThat(call(get(BASE + "/" + a), "mortgage-system").getStatus()).isEqualTo(200);
        assertThat(call(post(BASE).contentType(MediaType.APPLICATION_JSON).content(createBody(newApp(), 40)),
                "mortgage-system").getStatus()).isEqualTo(403);
        assertThat(decide(a, "mortgage-system", "APPROVED", q(0)).getStatus()).isEqualTo(403);
        assertThat(withdraw(a, "mortgage-system", q(0)).getStatus()).isEqualTo(403);
        assertThat(supersede("mortgage-system", a, q(0), supBody(30), null).getStatus()).isEqualTo(403);
        assertThat(snap(a).status()).isEqualTo("PENDING");
        assertThat(requestCount(app)).isEqualTo(1);
    }

    @Test
    void supersede_of_unknown_id_twice_with_same_key_is_404_both_times() throws Exception {
        String key = newKey();
        assertProblem(supersede("rm-anna", UNKNOWN_ID, q(0), supBody(30), key), 404, "REQUEST_NOT_FOUND");
        assertProblem(supersede("rm-anna", UNKNOWN_ID, q(0), supBody(30), key), 404, "REQUEST_NOT_FOUND");
    }

    @Test
    void INV02_a_chain_writes_exactly_one_event_per_status_change() throws Exception {
        String app = newApp();
        String a = createPending(app, 40);
        String b = sup(a, 0, 35);
        sup(b, 0, 30);
        Integer eventRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_event e JOIN pricing_exception_request r ON e.request_id = r.id"
                        + " WHERE r.application_id = ?", Integer.class, app);
        assertThat(eventRows).isEqualTo(5); // A submit+supersede, B submit+supersede, C submit
    }
}
