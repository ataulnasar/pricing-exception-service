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

/** Withdrawal, list filters, If-Match edge cases and history, end to end. Own application per test. */
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalIT {

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

    @Test
    void INV03_withdrawal_by_another_rm_is_403_NOT_OWNER_and_by_reviewer_is_403_WRONG_ROLE() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);
        assertProblem(withdraw(id, "rm-erik", q(0)), 403, "NOT_OWNER");
        assertProblem(withdraw(id, "rev-sara", q(0)), 403, "WRONG_ROLE");
        assertThat(snap(id)).isEqualTo(before);
        assertThat(activeSlotOf(app)).isEqualTo(id);
    }

    @Test
    void INV05_withdrawal_without_if_match_is_428_and_stale_is_412_and_request_is_unchanged() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);
        assertProblem(withdraw(id, "rm-anna", null), 428, "IF_MATCH_REQUIRED");
        assertProblem(withdraw(id, "rm-anna", q(7)), 412, "VERSION_MISMATCH");
        assertThat(snap(id)).isEqualTo(before);
        assertThat(withdraw(id, "rm-anna", q(0)).getStatus()).isEqualTo(200);
    }

    @Test
    void list_filters_by_PENDING_and_by_WITHDRAWN_end_to_end() throws Exception {
        String pending = createPending(newApp(), 40);
        String withdrawn = createPending(newApp(), 40);
        assertThat(withdraw(withdrawn, "rm-anna", q(0)).getStatus()).isEqualTo(200);

        List<String> pendingIds = JsonPath.read(text(call(get(BASE + "?status=PENDING"), "rm-anna")), "$[*].id");
        assertThat(pendingIds).contains(pending).doesNotContain(withdrawn);
        List<String> withdrawnIds = JsonPath.read(text(call(get(BASE + "?status=WITHDRAWN"), "rm-anna")), "$[*].id");
        assertThat(withdrawnIds).contains(withdrawn).doesNotContain(pending);
        List<String> pendingStatuses = JsonPath.read(text(call(get(BASE + "?status=PENDING"), "rm-anna")), "$[*].status");
        assertThat(pendingStatuses).containsOnly("PENDING");
        List<String> withdrawnStatuses = JsonPath.read(text(call(get(BASE + "?status=WITHDRAWN"), "rm-anna")), "$[*].status");
        assertThat(withdrawnStatuses).containsOnly("WITHDRAWN");
    }

    @Test
    void INV05_bare_wildcard_if_match_on_decision_is_400_MALFORMED_REQUEST() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        Snap before = snap(id);
        assertProblem(decide(id, "rev-sara", "APPROVED", "*"), 400, "MALFORMED_REQUEST");
        assertThat(snap(id)).isEqualTo(before);
    }

    @Test
    void INV02_get_after_approval_shows_full_history_in_order() throws Exception {
        String app = newApp();
        String id = createPending(app, 40);
        assertThat(decide(id, "rev-sara", "APPROVED", q(0)).getStatus()).isEqualTo(200);
        MockHttpServletResponse r = getRequest(id);
        assertThat(JsonPath.<Integer>read(text(r), "$.approvedDiscountBps")).isEqualTo(40);
        assertThat(json(r, "$.status")).isEqualTo("APPROVED");
        assertThat(JsonPath.<Integer>read(text(r), "$.events.length()")).isEqualTo(2);
        assertThat(json(r, "$.events[0].type")).isEqualTo("SUBMITTED");
        assertThat(JsonPath.<Object>read(text(r), "$.events[0].fromStatus")).isNull();
        assertThat(json(r, "$.events[0].toStatus")).isEqualTo("PENDING");
        assertThat(json(r, "$.events[0].actorId")).isEqualTo("rm-anna");
        assertThat(json(r, "$.events[1].type")).isEqualTo("APPROVED");
        assertThat(json(r, "$.events[1].fromStatus")).isEqualTo("PENDING");
        assertThat(json(r, "$.events[1].toStatus")).isEqualTo("APPROVED");
        assertThat(json(r, "$.events[1].actorId")).isEqualTo("rev-sara");
    }
}
