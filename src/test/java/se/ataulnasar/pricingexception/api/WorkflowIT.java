package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * End-to-end workflow against real H2 + seed data, no mocks.
 *
 * <p>Independence: every test that writes (creates a request or changes a status) creates its OWN
 * OPEN application with a unique id (owner rm-anna, inserted with JDBC) and never relies on a seed
 * application's active slot. Seed rows are used read-only or in calls that fail before any write:
 * MA-1002 / request ...0002 (PENDING, 80 bps: 409 on create, 412 stale If-Match, 428, 403 over limit),
 * MA-1001 / request ...0001 (APPROVED: 409 on re-approve), MA-1004 (CLOSED: 409 on create),
 * MA-1003 (only as the target of a create that is rejected with 403). Tests can run in any order.
 *
 * <p>The edge-case tests (malformed If-Match, bad JSON, error shape, list order, ...) were merged in from
 * ApiAttackIT. They follow the same rule (own application per write) and also read the seed requests
 * ...0001 (APPROVED) and ...0002 (PENDING) only, so they no longer need a database of their own.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIT {

    private static final String BASE = "/api/pricing-exceptions";
    private static final String SEED_PENDING_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SEED_PENDING = BASE + "/00000000-0000-0000-0000-000000000002";
    private static final String SEED_APPROVED = BASE + "/00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** Inserts a fresh OPEN application owned by rm-anna with a unique id. Leftovers are harmless. */
    private String newApp() {
        String id = "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", id, "CUST-" + id);
        return id;
    }

    private static String createBody(String applicationId, int discount) {
        return "{\"applicationId\":\"" + applicationId + "\",\"discountBps\":" + discount
                + ",\"reason\":\"Customer has other offers\"}";
    }

    @Test
    void INV02_each_transition_writes_one_event() throws Exception {
        String app = newApp();
        // create
        MvcResult created = mvc.perform(post(BASE).header("X-User-Id", "rm-erik")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(app, 40)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("ETag", "\"0\""))
                .andReturn();
        String location = created.getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/pricing-exceptions/");

        // get: 1 event
        mvc.perform(get(location).header("X-User-Id", "rm-erik"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedBy").value("rm-erik"))
                .andExpect(jsonPath("$.applicationId").value(app))
                .andExpect(jsonPath("$.discountBps").value(40))
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[0].actorId").value("rm-erik"));

        // approve (sara limit 50 >= 40)
        MvcResult approved = mvc.perform(post(location + "/decision").header("X-User-Id", "rev-sara")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"comment\":\"ok\","
                                + "\"decidedBy\":\"rev-johan\",\"decidedAt\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("rev-sara"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.approvedDiscountBps").value(40))
                .andReturn();
        String decidedAt = com.jayway.jsonpath.JsonPath.read(approved.getResponse().getContentAsString(), "$.decidedAt");
        assertThat(Instant.parse(decidedAt)).isAfter(Instant.parse("2020-01-01T00:00:00Z"));

        // get: 2 events
        mvc.perform(get(location).header("X-User-Id", "mortgage-system"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("rev-sara"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.decisionComment").value("ok"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$.events[1].type").value("APPROVED"))
                .andExpect(jsonPath("$.events[1].actorId").value("rev-sara"))
                .andExpect(jsonPath("$.events[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.events[1].toStatus").value("APPROVED"));

        // INV-01 / INV-05: approving again with the new ETag is 409 and writes no event
        mvc.perform(post(location + "/decision").header("X-User-Id", "rev-sara")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        mvc.perform(get(location).header("X-User-Id", "rm-erik"))
                .andExpect(jsonPath("$.events.length()").value(2));

        // INV-04: the approved request still occupies its application
        mvc.perform(post(BASE).header("X-User-Id", "rm-anna")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(app, 30)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_REQUEST_EXISTS"));
    }

    @Test
    void INV01_approve_then_approve_again_is_409() throws Exception {
        // The happy-path test also re-approves its own request. This one uses the seed APPROVED
        // request (MA-1001), so it needs no free application slot and is order independent.
        String approvedSeedId = "00000000-0000-0000-0000-000000000001";
        MvcResult got = mvc.perform(get(BASE + "/" + approvedSeedId).header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        mvc.perform(post(BASE + "/" + approvedSeedId + "/decision").header("X-User-Id", "rev-johan")
                        .header("If-Match", got.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void INV06_cannot_create_for_closed_application() throws Exception {
        mvc.perform(post(BASE).header("X-User-Id", "rm-anna")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("MA-1004", 40)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_OPEN"));
    }

    @Test
    void INV04_second_request_for_application_with_pending_request_is_409() throws Exception {
        mvc.perform(post(BASE).header("X-User-Id", "rm-anna")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("MA-1002", 40)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_REQUEST_EXISTS"));
    }

    @Test
    void INV05_stale_if_match_is_412() throws Exception {
        mvc.perform(post(BASE + "/" + SEED_PENDING_ID + "/decision").header("X-User-Id", "rev-sara")
                        .header("If-Match", "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
        // and nothing changed
        mvc.perform(get(BASE + "/" + SEED_PENDING_ID).header("X-User-Id", "rm-anna"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void INV05_decision_without_if_match_is_428_end_to_end() throws Exception {
        mvc.perform(post(BASE + "/" + SEED_PENDING_ID + "/decision").header("X-User-Id", "rev-sara")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    }

    @Test
    void api_missing_user_header_is_401_end_to_end() throws Exception {
        mvc.perform(get(BASE + "/" + SEED_PENDING_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(BASE + "/" + SEED_PENDING_ID).header("X-User-Id", "ghost"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void INV03_reviewer_cannot_create_and_rm_cannot_decide_end_to_end() throws Exception {
        mvc.perform(post(BASE).header("X-User-Id", "rev-sara")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("MA-1003", 40)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        mvc.perform(post(BASE + "/" + SEED_PENDING_ID + "/decision").header("X-User-Id", "rm-anna")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
    }

    @Test
    void INV03_over_approval_limit_is_403_end_to_end() throws Exception {
        // seed request 0002 asks for 80 bps (seed data), sara's limit is 50
        MvcResult got = mvc.perform(get(BASE + "/" + SEED_PENDING_ID).header("X-User-Id", "rm-anna"))
                .andExpect(jsonPath("$.discountBps").value(80)).andReturn();
        mvc.perform(post(BASE + "/" + SEED_PENDING_ID + "/decision").header("X-User-Id", "rev-sara")
                        .header("If-Match", got.getResponse().getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OVER_APPROVAL_LIMIT"));
    }

    @Test
    void api_get_unknown_request_is_404_end_to_end() throws Exception {
        mvc.perform(get(BASE + "/00000000-0000-0000-0000-0000000000ff").header("X-User-Id", "rm-anna"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void api_list_filters_by_application_end_to_end() throws Exception {
        mvc.perform(get(BASE).param("applicationId", "MA-1002").param("status", "PENDING")
                        .header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SEED_PENDING_ID));
    }

    // INV06_cannot_approve_for_closed_application: NOT testable end to end here. No endpoint moves
    // an application from OPEN to CLOSED, and the only CLOSED seed application (MA-1004) has no
    // PENDING request. A test would need direct DB writes, which is a service-level test.

    @Test
    void INV06_cannot_approve_for_closed_application() throws Exception {
        // Own application and own request (the previous version closed seed application MA-1002 for a moment).
        String app = newApp();
        MvcResult created = mvc.perform(post(BASE).header("X-User-Id", "rm-anna")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(app, 40)))
                .andExpect(status().isCreated()).andReturn();
        String url = created.getResponse().getHeader("Location");
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        mvc.perform(post(url + "/decision").header("X-User-Id", "rev-johan").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_OPEN"));
        mvc.perform(get(url).header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    // ---------- edge cases against the real stack (merged from ApiAttackIT) ----------

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

    /** Creates on the given own application as rm-erik and returns the Location path. */
    private String createOn(String app, int discount, String reason) throws Exception {
        MvcResult r = createRaw("rm-erik", body(app, discount, json(reason))).andExpect(status().isCreated()).andReturn();
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
        String app = newApp();
        // Contract: "whole number". A string "101" must not be coerced into a number and validated as one.
        createRaw("rm-erik", body(app, "\"101\"", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void api_create_discount_in_exponent_notation_is_400() throws Exception {
        String app = newApp();
        createRaw("rm-erik", body(app, "1e2", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void api_create_discount_larger_than_int_is_400_not_500() throws Exception {
        String app = newApp();
        createRaw("rm-erik", body(app, "2147483648", json("r"))).andExpect(status().isBadRequest());
        createRaw("rm-erik", body(app, "99999999999999999999", json("r"))).andExpect(status().isBadRequest());
    }

    @Test
    void INV01_discount_0_101_and_negative_are_422_INVALID_DISCOUNT_and_nothing_is_stored() throws Exception {
        String app = newApp();
        for (int bad : new int[] {0, 101, -1, Integer.MIN_VALUE}) {
            createRaw("rm-erik", body(app, bad, json("r"))).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_DISCOUNT"));
        }
        mvc.perform(get(BASE).param("applicationId", app).header("X-User-Id", "rm-erik"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0)); // own application: nothing stored
    }

    @Test
    void INV01_discount_1_and_100_are_accepted_by_the_real_service() throws Exception {
        String app = newApp();
        for (int ok : new int[] {1, 100}) {
            String loc = createOn(app, ok, "boundary " + ok);
            mvc.perform(get(loc).header("X-User-Id", "rm-erik")).andExpect(jsonPath("$.discountBps").value(ok));
            withdraw(loc, 0);
        }
    }

    @Test
    void api_create_ignores_unknown_fields_and_client_supplied_status_and_requestedBy() throws Exception {
        String app = newApp();
        String content = "{\"applicationId\":\"" + app + "\",\"discountBps\":10,\"reason\":\"x\",\"status\":\"APPROVED\","
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
        String app = newApp();
        createRaw("rm-erik", body(app, 10, json("  "))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));
        createRaw("rm-erik", body(app, 10, json("​"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void api_reason_round_trips_unchanged_with_whitespace_and_500_multibyte_chars() throws Exception {
        String app = newApp();
        String reason = "  漢".repeat(1) + "é".repeat(495) + " \t";
        assertThat(reason.length()).isEqualTo(500);
        String loc = createOn(app, 10, reason);
        String stored = mvc.perform(get(loc).header("X-User-Id", "rm-erik")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(stored, "$.reason")).isEqualTo(reason);
        withdraw(loc, 0);
    }

    // Removed: "characters" are UTF-16 units (String.length(), same as @Size, never more than the DB column); decided in Phase 1 and written into design.md "Validation".

    @Test
    void api_reason_with_nul_character_never_causes_500() throws Exception {
        String app = newApp();
        MvcResult r = createRaw("rm-erik", body(app, 10, json("a\u0000b"))).andReturn();
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
        String app = newApp();
        for (String comment : new String[] {"null", "\"\"", json("c".repeat(500))}) {
            String loc = createOn(app, 10, "r");
            MvcResult r = decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":" + comment + "}")
                    .andExpect(status().isOk()).andReturn();
            String again = mvc.perform(get(loc).header("X-User-Id", "rev-sara")).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Object>read(again, "$.decisionComment")).isEqualTo(JsonPath.<Object>read(r.getResponse().getContentAsString(), "$.decisionComment"));
        }
        String loc = createOn(app, 10, "r");
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
        String app = newApp();
        String loc = createOn(app, 10, "newest");
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
        String app = newApp();
        String evil = "<img src=x onerror=alert(1)>";
        MvcResult r = decision(SEED_PENDING, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":" + json(evil + "c".repeat(500)) + "}")
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("onerror");
        MvcResult r2 = createRaw("rm-erik", "{\"applicationId\":\"" + app + "\",\"discountBps\":1,\"reason\":" + json(evil.repeat(30)) + "}")
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(r2.getResponse().getContentAsString()).doesNotContain("onerror");
    }

    // ---------- roles ----------

    @Test
    void api_mortgage_process_cannot_create_withdraw_or_decide() throws Exception {
        String app = newApp();
        createRaw("mortgage-system", body(app, 10, json("r"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_ROLE"));
        decision(SEED_PENDING, "mortgage-system", "\"0\"", "{\"outcome\":\"DECLINED\"}").andExpect(status().isForbidden());
        mvc.perform(post(SEED_PENDING + "/withdrawal").header("X-User-Id", "mortgage-system").header("If-Match", "\"0\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void INV06_decline_still_works_on_closed_application() throws Exception {
        String app = newApp();
        // INV-06: the application must be OPEN to create or approve only; decline is allowed.
        String loc = createOn(app, 10, "r");
        jdbc.update("UPDATE mortgage_application SET status = 'CLOSED' WHERE id = ?", app);
        try {
            decision(loc, "rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        } finally {
            jdbc.update("UPDATE mortgage_application SET status = 'OPEN' WHERE id = ?", app);
        }
    }

    // ---------- lifecycle, ETag, time, events ----------

    @Test
    void INV02_decline_gives_etag_1_two_events_microsecond_decidedAt_and_final_state_rejects_everything() throws Exception {
        String app = newApp();
        String loc = createOn(app, 30, "r");
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
        String app = newApp();
        String loc = createOn(app, 30, "r");
        MvcResult w = mvc.perform(post(loc + "/withdrawal").header("X-User-Id", "rm-erik").header("If-Match", "\"0\"")).andExpect(status().isOk()).andReturn();
        assertThat(w.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(JsonPath.<String>read(w.getResponse().getContentAsString(), "$.events[1].type")).isEqualTo("WITHDRAWN");
        String again = createOn(app, 31, "again");
        withdraw(again, 0);
    }

}
