package se.ataulnasar.pricingexception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static se.ataulnasar.pricingexception.controller.Api.*;

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
import se.ataulnasar.pricingexception.controller.Api.Resp;

/** Withdrawal, list filters, If-Match edge cases and history, end to end. Own application per test. */
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Api api;

    @BeforeEach
    void init() {
        api = Api.mock(mvc);
    }

    /** display, endpoint, caller, If-Match, status, code. */
    static Stream<Arguments> rejectedCalls() {
        return Stream.of(
                Arguments.of("INV03 withdrawal by another RM is 403 NOT_OWNER", "withdrawal", "rm-erik", q(0), 403, "NOT_OWNER"),
                Arguments.of("INV03 withdrawal by a reviewer is 403 WRONG_ROLE", "withdrawal", "rev-sara", q(0), 403, "WRONG_ROLE"),
                Arguments.of("INV05 withdrawal without If-Match is 428", "withdrawal", "rm-anna", null, 428, "IF_MATCH_REQUIRED"),
                Arguments.of("INV05 stale withdrawal is 412", "withdrawal", "rm-anna", q(7), 412, "VERSION_MISMATCH"),
                Arguments.of("INV05 bare wildcard If-Match on a decision is 400", "decision", "rev-sara", "*", 400,
                        "MALFORMED_REQUEST"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCalls")
    void rejected_withdrawals_and_decisions_leave_the_request_unchanged(String display, String endpoint, String user,
                                                                       String ifMatch, int status, String code) throws Exception {
        String app = Api.newApp(jdbc);
        String id = api.createPending("rm-anna", app, 40);
        String before = api.fetch(id).body();

        api.send("POST", BASE + "/" + id + "/" + endpoint, user, endpoint.equals("decision") ? "{\"outcome\":\"APPROVED\"}" : null,
                ifMatch == null ? new String[0] : new String[] {"If-Match", ifMatch}).problem(status, code);

        assertThat(api.fetch(id).body()).isEqualTo(before);
        api.withdraw(id, "rm-anna", q(0)).status(200); // the correct call still works afterwards
    }

    @Test
    void list_filters_by_PENDING_and_by_WITHDRAWN_end_to_end() throws Exception {
        String pending = api.createPending("rm-anna", Api.newApp(jdbc), 40);
        String withdrawn = api.createPending("rm-anna", Api.newApp(jdbc), 40);
        api.withdraw(withdrawn, "rm-anna", q(0)).status(200);

        Resp pendingList = api.list("rm-anna", "status=PENDING");
        assertThat(pendingList.strings("$[*].id")).contains(pending).doesNotContain(withdrawn);
        assertThat(pendingList.strings("$[*].status")).containsOnly("PENDING");
        Resp withdrawnList = api.list("rm-anna", "status=WITHDRAWN");
        assertThat(withdrawnList.strings("$[*].id")).contains(withdrawn).doesNotContain(pending);
        assertThat(withdrawnList.strings("$[*].status")).containsOnly("WITHDRAWN");
    }

    @Test
    void INV02_get_after_approval_shows_full_history_in_order() throws Exception {
        String id = api.createPending("rm-anna", Api.newApp(jdbc), 40);
        api.decide(id, "rev-sara", "APPROVED", q(0)).status(200);
        api.fetch(id).has("$.approvedDiscountBps", 40, "$.status", "APPROVED", "$.events.length()", 2,
                "$.events[0].type", "SUBMITTED", "$.events[0].fromStatus", null, "$.events[0].toStatus", "PENDING",
                "$.events[0].actorId", "rm-anna", "$.events[1].type", "APPROVED", "$.events[1].fromStatus", "PENDING",
                "$.events[1].toStatus", "APPROVED", "$.events[1].actorId", "rev-sara");
    }
}
