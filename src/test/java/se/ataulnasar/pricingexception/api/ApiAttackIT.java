package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Attack tests against the real stack (H2 + seed data, no mocks).
 *
 * <p>This class uses its OWN in-memory database (different datasource URL, so a separate Spring
 * context) so that WorkflowIT consuming MA-1003's active slot cannot influence it. Inside this class
 * every test that creates a request on MA-1003 frees the slot again (decline or withdraw) before it ends.
 * Seed request ...0002 (PENDING, 80 bps) is never written to.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:attack;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiAttackIT {

    private static final String BASE = "/api/pricing-exceptions";
    private static final String SEED_PENDING = BASE + "/00000000-0000-0000-0000-000000000002";
    private static final String SEED_APPROVED = BASE + "/00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private static String body(String app, Object discount, String reasonJson) {
        return "{\"applicationId\":\"" + app + "\",\"discountBps\":" + discount + ",\"reason\":" + reasonJson + "}";
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20 || c == ' ') sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private ResultActions createRaw(String user, String content) throws Exception {
        return mvc.perform(post(BASE).header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    /** Creates on MA-1003 as rm-erik and returns the Location path. Caller must free the slot. */
    private String createOnMa1003(int discount, String reason) throws Exception {
        MvcResult r = createRaw("rm-erik", body("MA-1003", discount, json(reason))).andExpect(status().isCreated()).andReturn();
        return r.getResponse().getHeader("Location");
    }

    private void withdraw(String location, long version) throws Exception {
        mvc.perform(post(location + "/withdrawal").header("X-User-Id", "rm-erik").header("If-Match", "\"" + version + "\""))
                .andExpect(status().isOk());
    }

    private ResultActions decision(String location, String user, String ifMatch, String content) throws Exception {
        MockHttpServletRequestBuilder b = post(location + "/decision").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return mvc.perform(b);
    }

    private static String problemJson(MvcResult r) throws Exception {
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        return r.getResponse().getContentAsString();
    }

    // ---------- identity header ----------

    @Test
    void api_user_id_with_different_case_is_401() throws Exception {
        mvc.perform(get(BASE).header("X-User-Id", "RM-ANNA")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void api_very_long_user_id_is_401_not_500() throws Exception {
        mvc.perform(get(BASE).header("X-User-Id", "x".repeat(20_000))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // JUDGEMENT: design says "unknown X-User-Id -> 401"; the resolver trims, so " rm-anna " is accepted as rm-anna.
    @Test
    void api_user_id_with_surrounding_whitespace_is_not_a_known_user_JUDGEMENT() throws Exception {
        mvc.perform(get(BASE).header("X-User-Id", " rm-anna ")).andExpect(status().isUnauthorized());
    }

    @Test
    void api_unknown_user_wins_over_invalid_body() throws Exception {
        createRaw("nobody", "{\"reason\":\"\"}").andExpect(status().isUnauthorized());
        createRaw("nobody", "not json").andExpect(status().isUnauthorized());
        decision(SEED_PENDING, "nobody", null, "{}").andExpect(status().isUnauthorized());
    }

    // ---------- If-Match forms (writes never happen: sara's limit 50 < 80 bps, so a parsed "0" ends in 403) ----------

    private ResultActions approveAsSara(String ifMatch) throws Exception {
        return decision(SEED_PENDING, "rev-sara", ifMatch, "{\"outcome\":\"APPROVED\"}");
    }

    // The documented contract: "weak tags are not accepted" (400).
    @Test
    void INV05_weak_if_match_is_400() throws Exception {
        approveAsSara("W/\"0\"").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // JUDGEMENT: design: "If-Match must carry exactly that value" (the ETag is the quoted version).
    @Test
    void INV05_unquoted_if_match_is_400() throws Exception {
        approveAsSara("0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // JUDGEMENT: "exactly that value" - "00" and "+0" are not the ETag "0".
    @Test
    void INV05_if_match_with_leading_zero_or_plus_sign_is_not_the_etag() throws Exception {
        approveAsSara("\"00\"").andExpect(status().isBadRequest());
        approveAsSara("\"+0\"").andExpect(status().isBadRequest());
    }

    @Test
    void INV05_if_match_list_is_400() throws Exception {
        approveAsSara("\"0\", \"1\"").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void INV05_if_match_overflowing_long_and_negative_are_400() throws Exception {
        approveAsSara("\"9223372036854775808\"").andExpect(status().isBadRequest());
        // A negative number can never be an ETag the server issued, so like "abc" it is malformed (400), not a stale version (412).
        approveAsSara("\"-1\"").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void INV05_if_match_surrounded_by_spaces_is_still_the_version() throws Exception {
        // HTTP allows optional whitespace around a field value; version 0 matches, so the next rule (limit) fires.
        approveAsSara(" \"0\" ").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OVER_APPROVAL_LIMIT"));
    }

    // ---------- request bodies ----------

    @Test
    void api_create_discount_as_json_string_is_not_silently_coerced_JUDGEMENT() throws Exception {
        // Contract: "whole number". A string "101" must not be coerced into a number and validated as one.
        createRaw("rm-erik", body("MA-1003", "\"101\"", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void api_create_discount_in_exponent_notation_is_400() throws Exception {
        createRaw("rm-erik", body("MA-1003", "1e2", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void api_create_discount_larger_than_int_is_400_not_500() throws Exception {
        createRaw("rm-erik", body("MA-1003", "2147483648", json("r"))).andExpect(status().isBadRequest());
        createRaw("rm-erik", body("MA-1003", "99999999999999999999", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void INV01_discount_0_101_and_negative_are_422_INVALID_DISCOUNT_and_nothing_is_stored() throws Exception {
        for (int bad : new int[] {0, 101, -1, Integer.MIN_VALUE}) {
            createRaw("rm-erik", body("MA-1003", bad, json("r"))).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_DISCOUNT"));
        }
        mvc.perform(get(BASE).param("applicationId", "MA-1003").header("X-User-Id", "rm-erik"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)); // only the seed DECLINED one
    }

    @Test
    void INV01_discount_1_and_100_are_accepted_by_the_real_service() throws Exception {
        for (int ok : new int[] {1, 100}) {
            String loc = createOnMa1003(ok, "boundary " + ok);
            mvc.perform(get(loc).header("X-User-Id", "rm-erik")).andExpect(jsonPath("$.discountBps").value(ok));
            withdraw(loc, 0);
        }
    }

    @Test
    void api_create_ignores_unknown_fields_and_client_supplied_status_and_requestedBy() throws Exception {
        String content = "{\"applicationId\":\"MA-1003\",\"discountBps\":10,\"reason\":\"x\",\"status\":\"APPROVED\","
                + "\"requestedBy\":\"rm-anna\",\"version\":7,\"decidedBy\":\"rev-sara\",\"id\":\"00000000-0000-0000-0000-000000000099\"}";
        MvcResult r = createRaw("rm-erik", content).andExpect(status().isCreated()).andReturn();
        String json = r.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(json, "$.requestedBy")).isEqualTo("rm-erik");
        assertThat(JsonPath.<Integer>read(json, "$.version")).isZero();
        assertThat(JsonPath.<String>read(json, "$.id")).isNotEqualTo("00000000-0000-0000-0000-000000000099");
        withdraw(r.getResponse().getHeader("Location"), 0);
    }

    @Test
    void api_create_null_and_missing_fields_are_422_with_all_field_names() throws Exception {
        MvcResult r = createRaw("rm-erik", "{\"applicationId\":null,\"discountBps\":null}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn();
        String json = problemJson(r);
        List<String> fields = JsonPath.read(json, "$.errors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("applicationId", "discountBps", "reason");
    }

    @Test
    void api_create_empty_body_and_json_null_are_400() throws Exception {
        mvc.perform(post(BASE).header("X-User-Id", "rm-erik").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        createRaw("rm-erik", "null").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void api_create_reason_of_only_nbsp_or_zero_width_space_is_422_INVALID_REASON() throws Exception {
        createRaw("rm-erik", body("MA-1003", 10, json("  "))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));
        createRaw("rm-erik", body("MA-1003", 10, json("​"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void api_reason_round_trips_unchanged_with_whitespace_and_500_multibyte_chars() throws Exception {
        String reason = "  漢".repeat(1) + "é".repeat(495) + " \t";
        assertThat(reason.length()).isEqualTo(500);
        String loc = createOnMa1003(10, reason);
        String stored = mvc.perform(get(loc).header("X-User-Id", "rm-erik")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(stored, "$.reason")).isEqualTo(reason);
        withdraw(loc, 0);
    }

    // Removed: "characters" are UTF-16 units (String.length(), same as @Size, never more than the DB column); decided in Phase 1 and written into design.md "Validation".

    @Test
    void api_reason_with_nul_character_never_causes_500() throws Exception {
        MvcResult r = createRaw("rm-erik", body("MA-1003", 10, json("a\u0000b"))).andReturn();
        assertThat(r.getResponse().getStatus()).isLessThan(500);
        if (r.getResponse().getStatus() == 201) withdraw(r.getResponse().getHeader("Location"), 0);
    }

    @Test
    void api_create_with_10000_char_application_id_is_404_not_500() throws Exception {
        createRaw("rm-erik", body("M".repeat(10_000), 10, json("r"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test
    void api_decision_comment_null_empty_500_accepted_501_rejected_without_changing_state() throws Exception {
        for (String comment : new String[] {"null", "\"\"", json("c".repeat(500))}) {
            String loc = createOnMa1003(10, "r");
            MvcResult r = decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":" + comment + "}")
                    .andExpect(status().isOk()).andReturn();
            String again = mvc.perform(get(loc).header("X-User-Id", "rev-sara")).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Object>read(again, "$.decisionComment")).isEqualTo(JsonPath.<Object>read(r.getResponse().getContentAsString(), "$.decisionComment"));
        }
        String loc = createOnMa1003(10, "r");
        decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":" + json("c".repeat(501)) + "}")
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(loc).header("X-User-Id", "rev-sara")).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.version").value(0));
        withdraw(loc, 0);
    }

    @Test
    void api_decision_outcome_null_and_empty_object_are_422_and_json_null_body_is_400() throws Exception {
        decision(SEED_PENDING, "rev-sara", "\"0\"", "{\"outcome\":null}").andExpect(status().isUnprocessableEntity());
        decision(SEED_PENDING, "rev-sara", "\"0\"", "{}").andExpect(status().isUnprocessableEntity());
        decision(SEED_PENDING, "rev-sara", "\"0\"", "null").andExpect(status().isBadRequest());
        decision(SEED_PENDING, "rev-sara", "\"0\"", "{\"outcome\":\"approved\"}").andExpect(status().isBadRequest()); // case matters
    }

    // ---------- list ----------

    @Test
    void api_list_unknown_application_is_empty_array_and_both_filters_combine() throws Exception {
        mvc.perform(get(BASE).param("applicationId", "MA-9999").header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(BASE).param("applicationId", "MA-1002").param("status", "PENDING").header("X-User-Id", "rm-anna"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].applicationId").value("MA-1002"));
        mvc.perform(get(BASE).param("applicationId", "MA-1002").param("status", "APPROVED").header("X-User-Id", "rm-anna"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // JUDGEMENT: status is an enum; lower case is rejected with 400 like the decision outcome. Either is defensible if documented.
    @Test
    void api_list_lowercase_status_is_400() throws Exception {
        mvc.perform(get(BASE).param("status", "pending").header("X-User-Id", "rm-anna")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void api_list_is_newest_first() throws Exception {
        String loc = createOnMa1003(10, "newest");
        String list = mvc.perform(get(BASE).header("X-User-Id", "rm-anna")).andReturn().getResponse().getContentAsString();
        List<String> created = JsonPath.read(list, "$[*].createdAt");
        List<Instant> times = new ArrayList<>();
        created.forEach(s -> times.add(Instant.parse(s)));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(JsonPath.<String>read(list, "$[0].id")).isEqualTo(loc.substring(loc.lastIndexOf('/') + 1));
        withdraw(loc, 0);
    }

    // ---------- error order ----------

    // Removed: documented check order (design.md "API error codes"): 401, then request shape (body 422, If-Match 428/400), then use case (404, 412, domain rules 403/409/422); an invalid body is reported before a wrong role.

    // Removed: same documented order; If-Match is part of the request shape and is checked before the use case loads the request, so 428 comes before 404.

    // Removed: same documented order; body validation (422) runs before the If-Match check (428) on endpoints with a body; withdrawal has no body, so it goes straight to 428.

    // JUDGEMENT: check order for decisions is "status, then role..."; a stale If-Match on a final request:
    // 409 (it can never succeed, whatever version) vs 412. Test only pins that it is a clean 4xx with a code.
    @Test
    void INV05_stale_version_on_a_non_pending_request_is_a_clean_409_or_412() throws Exception {
        MvcResult r = decision(SEED_APPROVED, "rev-sara", "\"99\"", "{\"outcome\":\"DECLINED\"}").andReturn();
        assertThat(r.getResponse().getStatus()).isIn(409, 412);
        assertThat(JsonPath.<String>read(problemJson(r), "$.code")).isIn("INVALID_TRANSITION", "VERSION_MISMATCH");
    }

    // ---------- shape of responses ----------

    @Test
    void api_problem_has_type_title_status_instance_code_and_problem_json() throws Exception {
        MvcResult r = mvc.perform(get(BASE + "/00000000-0000-0000-0000-0000000000ff").header("X-User-Id", "rm-anna"))
                .andExpect(status().isNotFound()).andReturn();
        String json = problemJson(r);
        // RFC 9457: an absent "type" means about:blank, so only a present non-blank value is checked
        assertThat(JsonPath.<java.util.Map<String, Object>>read(json, "$").getOrDefault("type", "about:blank").toString()).isNotBlank();
        assertThat(JsonPath.<String>read(json, "$.title")).isNotBlank();
        assertThat(JsonPath.<Integer>read(json, "$.status")).isEqualTo(404);
        assertThat(JsonPath.<String>read(json, "$.instance")).isEqualTo("/api/pricing-exceptions/00000000-0000-0000-0000-0000000000ff");
        assertThat(JsonPath.<String>read(json, "$.code")).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    void api_every_error_class_is_problem_json() throws Exception {
        problemJson(mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andReturn());
        problemJson(createRaw("rm-erik", "{}").andExpect(status().isUnprocessableEntity()).andReturn());
        problemJson(createRaw("rm-erik", "{").andExpect(status().isBadRequest()).andReturn());
        problemJson(approveAsSara("\"5\"").andExpect(status().isPreconditionFailed()).andReturn());
        problemJson(approveAsSara(null).andExpect(status().isPreconditionRequired()).andReturn());
        problemJson(approveAsSara("\"0\"").andExpect(status().isForbidden()).andReturn());
    }

    @Test
    void api_error_with_accept_text_html_never_returns_html() throws Exception {
        MvcResult r = mvc.perform(get(BASE + "/not-a-uuid<script>").header("X-User-Id", "rm-anna").accept(MediaType.TEXT_HTML)).andReturn();
        String ct = String.valueOf(r.getResponse().getContentType());
        assertThat(ct).doesNotContain("html");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("<script>");
    }

    @Test
    void api_unknown_route_is_404_HTTP_404_problem_json() throws Exception {
        MvcResult r = mvc.perform(get("/api/nothing").header("X-User-Id", "rm-anna")).andExpect(status().isNotFound()).andReturn();
        assertThat(JsonPath.<String>read(problemJson(r), "$.code")).isEqualTo("HTTP_404");
    }

    @Test
    void api_wrong_method_is_405_and_wrong_content_type_is_415() throws Exception {
        MvcResult r = mvc.perform(delete(SEED_PENDING).header("X-User-Id", "rm-anna")).andExpect(status().isMethodNotAllowed()).andReturn();
        assertThat(JsonPath.<String>read(problemJson(r), "$.code")).isEqualTo("HTTP_405");
        mvc.perform(post(BASE).header("X-User-Id", "rm-erik").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("HTTP_415"));
    }

    @Test
    void api_validation_detail_does_not_echo_user_text() throws Exception {
        String evil = "<img src=x onerror=alert(1)>";
        MvcResult r = decision(SEED_PENDING, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":" + json(evil + "c".repeat(500)) + "}")
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("onerror");
        MvcResult r2 = createRaw("rm-erik", "{\"applicationId\":\"MA-1003\",\"discountBps\":1,\"reason\":" + json(evil.repeat(30)) + "}")
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(r2.getResponse().getContentAsString()).doesNotContain("onerror");
    }

    // ---------- roles ----------

    @Test
    void api_mortgage_process_cannot_create_withdraw_or_decide() throws Exception {
        createRaw("mortgage-system", body("MA-1003", 10, json("r"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        decision(SEED_PENDING, "mortgage-system", "\"0\"", "{\"outcome\":\"DECLINED\"}").andExpect(status().isForbidden());
        mvc.perform(post(SEED_PENDING + "/withdrawal").header("X-User-Id", "mortgage-system").header("If-Match", "\"0\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void INV06_decline_still_works_on_closed_application() throws Exception {
        // INV-06: the application must be OPEN to create or approve only; decline is allowed.
        String loc = createOnMa1003(10, "r");
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = 'MA-1003'");
        try {
            decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        } finally {
            jdbc.update("UPDATE mortgage_application SET status = 'OPEN' WHERE id = 'MA-1003'");
        }
    }

    // ---------- lifecycle, ETag, time, events ----------

    @Test
    void INV02_decline_gives_etag_1_two_events_microsecond_decidedAt_and_final_state_rejects_everything() throws Exception {
        String loc = createOnMa1003(30, "r");
        MvcResult r = decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":\"no\"}").andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        String json = r.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(json, "$.version")).isEqualTo(1);
        assertThat(JsonPath.<List<?>>read(json, "$.events")).hasSize(2);
        Instant decidedAt = Instant.parse(JsonPath.<String>read(json, "$.decidedAt"));
        assertThat(decidedAt.getNano() % 1000).as("no sub-microsecond digits").isZero();
        assertThat(JsonPath.<String>read(json, "$.decidedBy")).isEqualTo("rev-sara");
        assertThat(JsonPath.<Object>read(json, "$.approvedDiscountBps")).isNull();

        // GET after the decision equals the POST response (precision survives the DB round trip) and ETag is not weak
        MvcResult g = mvc.perform(get(loc).header("X-User-Id", "rm-anna")).andReturn();
        assertThat(g.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(JsonPath.<String>read(g.getResponse().getContentAsString(), "$.decidedAt")).isEqualTo(JsonPath.<String>read(json, "$.decidedAt"));

        // retry of the same decision (stale version) and a withdraw of a final request
        decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\"}").andExpect(status().isPreconditionFailed());
        mvc.perform(post(loc + "/withdrawal").header("X-User-Id", "rm-erik").header("If-Match", "\"1\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        decision(loc, "rev-johan", "\"1\"", "{\"outcome\":\"APPROVED\"}").andExpect(status().isConflict());
        mvc.perform(get(loc).header("X-User-Id", "rm-anna")).andExpect(jsonPath("$.events.length()").value(2));
    }

    @Test
    void INV02_withdraw_gives_etag_1_and_frees_the_slot_for_a_new_request() throws Exception {
        String loc = createOnMa1003(30, "r");
        MvcResult w = mvc.perform(post(loc + "/withdrawal").header("X-User-Id", "rm-erik").header("If-Match", "\"0\"")).andExpect(status().isOk()).andReturn();
        assertThat(w.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(JsonPath.<String>read(w.getResponse().getContentAsString(), "$.events[1].type")).isEqualTo("WITHDRAWN");
        String again = createOnMa1003(31, "again");
        withdraw(again, 0);
    }

    @Test
    void INV05_two_parallel_declines_with_the_same_version_have_exactly_one_winner_and_one_event() throws Exception {
        String loc = createOnMa1003(30, "r");
        List<Integer> codes = parallel(8, () -> decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\"}")
                .andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 409 || c == 412);
        mvc.perform(get(loc).header("X-User-Id", "rm-anna")).andExpect(jsonPath("$.events.length()").value(2)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void INV04_parallel_creates_on_the_same_application_give_one_201_and_the_rest_409_ACTIVE_REQUEST_EXISTS() throws Exception {
        List<MvcResult> results = parallel(8, () -> createRaw("rm-erik", body("MA-1003", 10, json("r"))).andReturn());
        List<MvcResult> created = results.stream().filter(r -> r.getResponse().getStatus() == 201).toList();
        assertThat(created).hasSize(1);
        for (MvcResult r : results) {
            if (r.getResponse().getStatus() != 201) {
                assertThat(r.getResponse().getStatus()).isEqualTo(409);
                assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.code")).isEqualTo("ACTIVE_REQUEST_EXISTS");
            }
        }
        withdraw(created.get(0).getResponse().getHeader("Location"), 0);
    }

    private static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> { go.await(); return task.call(); }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
