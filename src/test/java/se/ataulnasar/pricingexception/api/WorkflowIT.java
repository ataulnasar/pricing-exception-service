package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end workflow against real H2 + seed data, no mocks.
 *
 * <p>Independence: every test that writes (creates a request or changes a status) creates its OWN
 * OPEN application with a unique id (owner rm-anna, inserted with JDBC) and never relies on a seed
 * application's active slot. Seed rows are used read-only or in calls that fail before any write:
 * MA-1002 / request ...0002 (PENDING, 80 bps: 409 on create, 412 stale If-Match, 428, 403 over limit),
 * MA-1001 / request ...0001 (APPROVED: 409 on re-approve), MA-1004 (CLOSED: 409 on create),
 * MA-1003 (only as the target of a create that is rejected with 403). Tests can run in any order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIT {

    private static final String BASE = "/api/pricing-exceptions";
    private static final String SEED_PENDING_ID = "00000000-0000-0000-0000-000000000002";

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
}
