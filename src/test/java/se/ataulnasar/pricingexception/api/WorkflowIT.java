package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.api.Api.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static se.ataulnasar.pricingexception.api.Api.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import se.ataulnasar.pricingexception.api.Api.Resp;

/**
 * End-to-end workflow against real H2 + seed data, no mocks.
 *
 * <p>Independence: every test that writes creates its OWN OPEN application (unique id, owner rm-anna)
 * and never relies on a seed application's active slot. Seed rows are only read or used in calls that
 * fail before any write: MA-1002 / request ...0002 (PENDING, 80 bps), MA-1001 / request ...0001
 * (APPROVED), MA-1004 (CLOSED). Tests can run in any order. The tables at the top list one row per
 * rejected call (path, caller, headers, body, expected status, expected code); a null code checks the
 * status only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIT {

    private static final String PENDING_ID = "00000000-0000-0000-0000-000000000002";
    private static final String APPROVED_ID = "00000000-0000-0000-0000-000000000001";
    private static final String APPROVE = "{\"outcome\":\"APPROVED\"}";
    private static final String MALFORMED = "MALFORMED_REQUEST";

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

    private String createOn(String app, int discount, String reason) throws Exception {
        return api.create("rm-erik", createBody(app, discount, reason), null).status(201).location();
    }

    private void withdraw(String location) throws Exception {
        api.withdraw(location, "rm-erik", q(0)).status(200);
    }

    // ---------- tables: rejected calls ----------

    /** display, path, caller, If-Match, body, status, code. Seed request ...0002 is PENDING, 80 bps; sara's limit is 50. */
    static Stream<Arguments> rejectedDecisionsOnSeed() {
        String p = BASE + "/" + PENDING_ID;
        String d = p + "/decision";
        return Stream.of(
                Arguments.of("INV05 stale If-Match 99 is 412", d, "rev-sara", "\"99\"", APPROVE, 412, "VERSION_MISMATCH"),
                Arguments.of("INV05 no If-Match is 428", d, "rev-sara", null, APPROVE, 428, "IF_MATCH_REQUIRED"),
                Arguments.of("INV05 weak If-Match is 400", d, "rev-sara", "W/\"0\"", APPROVE, 400, MALFORMED),
                Arguments.of("INV05 unquoted If-Match is 400", d, "rev-sara", "0", APPROVE, 400, MALFORMED),
                Arguments.of("INV05 If-Match with leading zero is 400", d, "rev-sara", "\"00\"", APPROVE, 400, null),
                Arguments.of("INV05 If-Match with plus sign is 400", d, "rev-sara", "\"+0\"", APPROVE, 400, null),
                Arguments.of("INV05 If-Match list is 400", d, "rev-sara", "\"0\", \"1\"", APPROVE, 400, MALFORMED),
                Arguments.of("INV05 If-Match overflowing long is 400", d, "rev-sara", "\"9223372036854775808\"", APPROVE, 400, null),
                Arguments.of("INV05 negative If-Match is 400 not 412", d, "rev-sara", "\"-1\"", APPROVE, 400, MALFORMED),
                Arguments.of("INV05 If-Match surrounded by spaces is still version 0 (limit rule fires)", d, "rev-sara",
                        " \"0\" ", APPROVE, 403, "OVER_APPROVAL_LIMIT"),
                Arguments.of("INV05 bare wildcard If-Match is 400", d, "rev-sara", "*", APPROVE, 400, MALFORMED),
                Arguments.of("INV03 RM cannot decide", d, "rm-anna", "\"0\"", APPROVE, 403, "WRONG_ROLE"),
                Arguments.of("INV03 mortgage process cannot decide", d, "mortgage-system", "\"0\"",
                        "{\"outcome\":\"DECLINED\"}", 403, null),
                Arguments.of("INV03 mortgage process cannot withdraw", p + "/withdrawal", "mortgage-system", "\"0\"",
                        null, 403, null),
                Arguments.of("unknown user wins over missing body", d, "nobody", null, "{}", 401, null),
                Arguments.of("decision outcome null is 422", d, "rev-sara", "\"0\"", "{\"outcome\":null}", 422, null),
                Arguments.of("decision empty object is 422", d, "rev-sara", "\"0\"", "{}", 422, null),
                Arguments.of("decision JSON null body is 400", d, "rev-sara", "\"0\"", "null", 400, null),
                Arguments.of("decision outcome is case sensitive", d, "rev-sara", "\"0\"", "{\"outcome\":\"approved\"}", 400, null),
                Arguments.of("decision comment over 500 chars is 422 and changes nothing", d, "rev-sara", "\"0\"",
                        "{\"outcome\":\"DECLINED\",\"comment\":" + json("c".repeat(501)) + "}", 422, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedDecisionsOnSeed")
    void INV05_rejected_calls_on_the_seed_pending_request(String display, String path, String user, String ifMatch,
                                                         String body, int status, String code) throws Exception {
        String[] headers = ifMatch == null ? new String[0] : new String[] {"If-Match", ifMatch};
        api.send("POST", path, user, body, headers).is(status, code);
        api.get("rm-anna", BASE + "/" + PENDING_ID).status(200).has("$.status", "PENDING", "$.version", 0);
    }

    /** display, caller, body with %APP% for a fresh application, status, code. Nothing may be stored. */
    static Stream<Arguments> rejectedCreates() {
        String ok = createBody("%APP%", 10, "r");
        return Stream.of(
                Arguments.of("INV03 reviewer cannot create", "rev-sara", createBody("%APP%", 40), 403, "WRONG_ROLE"),
                Arguments.of("INV03 mortgage process cannot create", "mortgage-system", ok, 403, "WRONG_ROLE"),
                Arguments.of("unknown user wins over invalid body", "nobody", "{\"reason\":\"\"}", 401, null),
                Arguments.of("unknown user wins over broken JSON", "nobody", "not json", 401, null),
                Arguments.of("discount as JSON string is not coerced", "rm-erik", createBody("%APP%", "\"101\"", "r"), 400, null),
                Arguments.of("discount in exponent notation is 400", "rm-erik", createBody("%APP%", "1e2", "r"), 400, null),
                Arguments.of("discount above int range is 400 not 500", "rm-erik", createBody("%APP%", "2147483648", "r"), 400, null),
                Arguments.of("discount far above int range is 400 not 500", "rm-erik",
                        createBody("%APP%", "99999999999999999999", "r"), 400, null),
                Arguments.of("INV01 discount 0 is 422", "rm-erik", createBody("%APP%", 0, "r"), 422, "INVALID_DISCOUNT"),
                Arguments.of("INV01 discount 101 is 422", "rm-erik", createBody("%APP%", 101, "r"), 422, "INVALID_DISCOUNT"),
                Arguments.of("INV01 discount -1 is 422", "rm-erik", createBody("%APP%", -1, "r"), 422, "INVALID_DISCOUNT"),
                Arguments.of("INV01 discount Integer.MIN_VALUE is 422", "rm-erik", createBody("%APP%", Integer.MIN_VALUE, "r"),
                        422, "INVALID_DISCOUNT"),
                Arguments.of("reason of only NBSP is 422 INVALID_REASON", "rm-erik", createBody("%APP%", 10, "  "),
                        422, "INVALID_REASON"),
                Arguments.of("reason of only zero-width space is 422", "rm-erik", createBody("%APP%", 10, "​"), 422, null),
                Arguments.of("empty body is 400", "rm-erik", "", 400, null),
                Arguments.of("JSON null body is 400", "rm-erik", "null", 400, MALFORMED),
                Arguments.of("broken JSON is 400", "rm-erik", "{", 400, MALFORMED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCreates")
    void rejected_creates_store_nothing(String display, String user, String body, int status, String code) throws Exception {
        String app = newApp();
        api.create(user, body.replace("%APP%", app), null).is(status, code);
        api.list("rm-erik", "applicationId=" + app).status(200).has("$.length()", 0);
    }

    /** display, method, path, caller header (null = none), status, code. Every row must be problem+json when it has a code. */
    static Stream<Arguments> rejectedReads() {
        String seed = BASE + "/" + PENDING_ID;
        return Stream.of(
                Arguments.of("missing X-User-Id is 401", "GET", seed, null, 401, "UNAUTHENTICATED"),
                Arguments.of("unknown user is 401", "GET", seed, "ghost", 401, null),
                Arguments.of("user id with different case is 401", "GET", BASE, "RM-ANNA", 401, "UNAUTHENTICATED"),
                Arguments.of("very long user id is 401 not 500", "GET", BASE, "x".repeat(20_000), 401, "UNAUTHENTICATED"),
                // JUDGEMENT: the resolver does not trim, so " rm-anna " is an unknown user.
                Arguments.of("user id with surrounding whitespace is unknown", "GET", BASE, " rm-anna ", 401, null),
                Arguments.of("unknown request id is 404", "GET", BASE + "/00000000-0000-0000-0000-0000000000ff", "rm-anna",
                        404, "REQUEST_NOT_FOUND"),
                // JUDGEMENT: status is an enum; lower case is rejected like the decision outcome.
                Arguments.of("lower case status filter is 400", "GET", BASE + "?status=pending", "rm-anna", 400, MALFORMED),
                Arguments.of("unknown route is 404", "GET", "/api/nothing", "rm-anna", 404, "HTTP_404"),
                Arguments.of("wrong method is 405", "DELETE", seed, "rm-anna", 405, "HTTP_405"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedReads")
    void api_rejected_reads_are_problem_json_with_a_stable_code(String display, String method, String path, String user,
                                                               int status, String code) throws Exception {
        api.send(method, path, user, null).is(status, code);
    }

    // ---------- the happy path and rule tests with their own application ----------

    @Test
    void INV02_each_transition_writes_one_event() throws Exception {
        String app = newApp();
        Resp created = api.create("rm-erik", app, 40).status(201);
        String location = created.location();
        assertThat(location).startsWith("/api/pricing-exceptions/");
        assertThat(created.etag()).isEqualTo("\"0\"");

        api.get("rm-erik", location).status(200).has("$.status", "PENDING", "$.requestedBy", "rm-erik",
                "$.applicationId", app, "$.discountBps", 40, "$.decidedBy", null, "$.events.length()", 1,
                "$.events[0].type", "SUBMITTED", "$.events[0].actorId", "rm-erik");

        // approve (sara limit 50 >= 40); decidedBy / decidedAt in the body must be ignored
        Resp approved = api.decision(location, "rev-sara", q(0), "{\"outcome\":\"APPROVED\",\"comment\":\"ok\","
                + "\"decidedBy\":\"rev-johan\",\"decidedAt\":\"2000-01-01T00:00:00Z\"}").status(200)
                .has("$.status", "APPROVED", "$.decidedBy", "rev-sara", "$.approvedDiscountBps", 40);
        assertThat(approved.etag()).isEqualTo("\"1\"");
        assertThat(Instant.parse(approved.str("$.decidedAt"))).isAfter(Instant.parse("2020-01-01T00:00:00Z"));

        Resp after = api.get("mortgage-system", location).status(200).has("$.status", "APPROVED",
                "$.decidedBy", "rev-sara", "$.decisionComment", "ok", "$.events.length()", 2,
                "$.events[0].type", "SUBMITTED", "$.events[0].toStatus", "PENDING",
                "$.events[1].type", "APPROVED", "$.events[1].actorId", "rev-sara",
                "$.events[1].fromStatus", "PENDING", "$.events[1].toStatus", "APPROVED");
        assertThat(after.etag()).isEqualTo("\"1\"");
        assertThat(after.str("$.decidedAt")).isNotEmpty();

        // INV-01 / INV-05: approving again with the new ETag is 409 and writes no event
        api.decision(location, "rev-sara", q(1), APPROVE).problem(409, "INVALID_TRANSITION");
        assertThat(api.get("rm-erik", location).events()).isEqualTo(2);

        // INV-04: the approved request still occupies its application
        api.create("rm-anna", app, 30).problem(409, "ACTIVE_REQUEST_EXISTS");
    }

    @Test
    void INV01_final_seed_requests_reject_a_second_decision() throws Exception {
        // Uses the seed APPROVED request (MA-1001), so it needs no free slot. A stale If-Match on a final
        // request is JUDGEMENT: a clean 409 or 412 with a code (design only fixes the status-first order).
        Resp got = api.get("rm-anna", BASE + "/" + APPROVED_ID).status(200).has("$.status", "APPROVED");
        api.decision(APPROVED_ID, "rev-johan", got.etag(), APPROVE).problem(409, "INVALID_TRANSITION");
        Resp stale = api.decision(APPROVED_ID, "rev-sara", q(99), "{\"outcome\":\"DECLINED\"}");
        assertThat(stale.status()).isIn(409, 412);
        assertThat(stale.str("$.code")).isIn("INVALID_TRANSITION", "VERSION_MISMATCH");
    }

    @Test
    void INV06_cannot_create_for_closed_application() throws Exception {
        api.create("rm-anna", "MA-1004", 40).problem(409, "APPLICATION_NOT_OPEN");
    }

    @Test
    void INV04_second_request_for_application_with_pending_request_is_409() throws Exception {
        api.create("rm-anna", "MA-1002", 40).problem(409, "ACTIVE_REQUEST_EXISTS");
    }

    @Test
    void INV05_decision_without_if_match_is_428_end_to_end() throws Exception {
        api.decision(PENDING_ID, "rev-sara", null, APPROVE).problem(428, "IF_MATCH_REQUIRED");
    }

    @Test
    void INV03_over_approval_limit_is_403_end_to_end() throws Exception {
        // seed request 0002 asks for 80 bps (seed data), sara's limit is 50
        Resp got = api.get("rm-anna", BASE + "/" + PENDING_ID).has("$.discountBps", 80);
        api.decision(PENDING_ID, "rev-sara", got.etag(), APPROVE).problem(403, "OVER_APPROVAL_LIMIT");
    }

    @Test
    void INV06_cannot_approve_for_closed_application() throws Exception {
        // No endpoint closes an application, so the test closes its own application with JDBC.
        String app = newApp();
        String url = api.create("rm-anna", app, 40).status(201).location();
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        api.decision(url, "rev-johan", q(0), APPROVE).problem(409, "APPLICATION_NOT_OPEN");
        api.get("rm-anna", url).status(200).has("$.status", "PENDING", "$.version", 0, "$.events.length()", 1);
    }

    @Test
    void INV06_decline_still_works_on_closed_application() throws Exception {
        // INV-06: the application must be OPEN to create or approve only; decline is allowed.
        String app = newApp();
        String loc = createOn(app, 10, "r");
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        api.decision(loc, "rev-sara", q(0), "{\"outcome\":\"DECLINED\"}").status(200).has("$.status", "DECLINED");
    }

    @Test
    void INV02_decline_gives_etag_1_two_events_microsecond_decidedAt_and_final_state_rejects_everything() throws Exception {
        String app = newApp();
        String loc = createOn(app, 30, "r");
        Resp r = api.decision(loc, "rev-sara", q(0), "{\"outcome\":\"DECLINED\",\"comment\":\"no\"}").status(200);
        assertThat(r.etag()).isEqualTo("\"1\"");
        r.has("$.version", 1, "$.decidedBy", "rev-sara", "$.approvedDiscountBps", null);
        assertThat(r.events()).isEqualTo(2);
        assertThat(Instant.parse(r.str("$.decidedAt")).getNano() % 1000).as("no sub-microsecond digits").isZero();

        // GET after the decision equals the POST response (precision survives the DB round trip); ETag is not weak
        Resp g = api.get("rm-anna", loc);
        assertThat(g.etag()).isEqualTo("\"1\"");
        assertThat(g.str("$.decidedAt")).isEqualTo(r.str("$.decidedAt"));

        // retry of the same decision (stale version), a withdraw of a final request, a decision by another reviewer
        api.decision(loc, "rev-sara", q(0), "{\"outcome\":\"DECLINED\"}").status(412);
        api.withdraw(loc, "rm-erik", q(1)).problem(409, "INVALID_TRANSITION");
        api.decision(loc, "rev-johan", q(1), APPROVE).status(409);
        assertThat(api.get("rm-anna", loc).events()).isEqualTo(2);
    }

    @Test
    void INV02_withdraw_gives_etag_1_and_frees_the_slot_for_a_new_request() throws Exception {
        String app = newApp();
        String loc = createOn(app, 30, "r");
        Resp w = api.withdraw(loc, "rm-erik", q(0)).status(200);
        assertThat(w.etag()).isEqualTo("\"1\"");
        assertThat(w.str("$.events[1].type")).isEqualTo("WITHDRAWN");
        withdraw(createOn(app, 31, "again"));
    }

    // ---------- request bodies and values ----------

    @Test
    void INV01_discount_1_and_100_are_accepted_by_the_real_service() throws Exception {
        String app = newApp();
        for (int ok : new int[] {1, 100}) {
            String loc = createOn(app, ok, "boundary " + ok);
            api.get("rm-erik", loc).has("$.discountBps", ok);
            withdraw(loc);
        }
    }

    @Test
    void api_create_ignores_unknown_fields_and_client_supplied_status_and_requestedBy() throws Exception {
        String app = newApp();
        String content = "{\"applicationId\":\"" + app + "\",\"discountBps\":10,\"reason\":\"x\",\"status\":\"APPROVED\","
                + "\"requestedBy\":\"rm-anna\",\"version\":7,\"decidedBy\":\"rev-sara\",\"id\":\"00000000-0000-0000-0000-000000000099\"}";
        Resp r = api.create("rm-erik", content, null).status(201).has("$.status", "PENDING", "$.requestedBy", "rm-erik", "$.version", 0);
        assertThat(r.id()).isNotEqualTo("00000000-0000-0000-0000-000000000099");
        withdraw(r.location());
    }

    @Test
    void api_create_null_and_missing_fields_are_422_with_all_field_names() throws Exception {
        Resp r = api.create("rm-erik", "{\"applicationId\":null,\"discountBps\":null}", null).problem(422, "VALIDATION_FAILED");
        assertThat(r.strings("$.errors[*].field")).containsExactlyInAnyOrder("applicationId", "discountBps", "reason");
    }

    @Test
    void api_reason_round_trips_unchanged_with_whitespace_and_500_multibyte_chars() throws Exception {
        String app = newApp();
        String reason = "  漢" + "é".repeat(495) + " \t";
        assertThat(reason.length()).isEqualTo(500);
        String loc = createOn(app, 10, reason);
        assertThat(api.get("rm-erik", loc).str("$.reason")).isEqualTo(reason);
        withdraw(loc);
    }

    @Test
    void api_reason_with_nul_character_never_causes_500() throws Exception {
        Resp r = api.create("rm-erik", createBody(newApp(), 10, "a\u0000b"), null);
        assertThat(r.status()).isLessThan(500);
        if (r.status() == 201) withdraw(r.location());
    }

    @Test
    void api_create_with_10000_char_application_id_is_404_not_500() throws Exception {
        api.create("rm-erik", "M".repeat(10_000), 10).problem(404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void api_decision_comment_null_empty_500_accepted_501_rejected_without_changing_state() throws Exception {
        String app = newApp();
        for (String comment : new String[] {"null", "\"\"", json("c".repeat(500))}) {
            String loc = createOn(app, 10, "r");
            Resp r = api.decision(loc, "rev-sara", q(0), "{\"outcome\":\"DECLINED\",\"comment\":" + comment + "}").status(200);
            assertThat(api.get("rev-sara", loc).any("$.decisionComment")).isEqualTo(r.any("$.decisionComment"));
        }
        String loc = createOn(app, 10, "r");
        api.decision(loc, "rev-sara", q(0), "{\"outcome\":\"DECLINED\",\"comment\":" + json("c".repeat(501)) + "}").status(422);
        api.get("rev-sara", loc).has("$.status", "PENDING", "$.version", 0);
        withdraw(loc);
    }

    // ---------- list ----------

    @Test
    void api_list_unknown_application_is_empty_array_and_both_filters_combine() throws Exception {
        api.list("rm-anna", "applicationId=MA-9999").status(200).has("$.length()", 0);
        api.list("rm-anna", "applicationId=MA-1002&status=PENDING").has("$.length()", 1,
                "$[0].applicationId", "MA-1002", "$[0].id", PENDING_ID);
        api.list("rm-anna", "applicationId=MA-1002&status=APPROVED").has("$.length()", 0);
    }

    @Test
    void api_list_is_newest_first() throws Exception {
        String loc = createOn(newApp(), 10, "newest");
        Resp list = api.list("rm-anna", "");
        List<Instant> times = list.strings("$[*].createdAt").stream().map(Instant::parse).toList();
        assertThat(times).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(list.str("$[0].id")).isEqualTo(loc.substring(loc.lastIndexOf('/') + 1));
        withdraw(loc);
    }

    // ---------- shape of responses ----------

    @Test
    void api_problem_has_type_title_status_instance_code_and_problem_json() throws Exception {
        String path = BASE + "/00000000-0000-0000-0000-0000000000ff";
        Resp r = api.get("rm-anna", path).problem(404, "REQUEST_NOT_FOUND");
        // RFC 9457: an absent "type" means about:blank, so only a present non-blank value is checked
        assertThat(String.valueOf(r.map().getOrDefault("type", "about:blank"))).isNotBlank();
        assertThat(r.str("$.title")).isNotBlank();
        r.has("$.status", 404, "$.instance", path);
    }

    @Test
    void api_error_with_accept_text_html_never_returns_html() throws Exception {
        MvcResult r = mvc.perform(get(BASE + "/not-a-uuid<script>").header("X-User-Id", "rm-anna").accept(MediaType.TEXT_HTML)).andReturn();
        assertThat(String.valueOf(r.getResponse().getContentType())).doesNotContain("html");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("<script>");
    }

    @Test
    void api_wrong_content_type_is_415() throws Exception {
        int status = mvc.perform(post(BASE).header("X-User-Id", "rm-erik").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(415);
    }

    @Test
    void api_validation_detail_does_not_echo_user_text() throws Exception {
        String evil = "<img src=x onerror=alert(1)>";
        Resp r = api.decision(PENDING_ID, "rev-sara", q(0),
                "{\"outcome\":\"DECLINED\",\"comment\":" + json(evil + "c".repeat(500)) + "}").status(422);
        assertThat(r.body()).doesNotContain("onerror");
        Resp r2 = api.create("rm-erik", createBody(newApp(), 1, evil.repeat(30)), null).status(422);
        assertThat(r2.body()).doesNotContain("onerror");
    }
}
