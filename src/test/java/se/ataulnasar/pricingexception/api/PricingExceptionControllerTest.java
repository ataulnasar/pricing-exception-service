package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.ataulnasar.pricingexception.application.ApiProblem;
import se.ataulnasar.pricingexception.application.Decision;
import se.ataulnasar.pricingexception.application.IdempotentResult;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.RequestView;
import se.ataulnasar.pricingexception.application.SupersedeCommand;
import se.ataulnasar.pricingexception.application.UserLookup;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.ForbiddenActionException;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.ValidationException;

/**
 * Controller mapping with the service mocked. Only cases an integration test cannot check live here:
 * the caller comes from the header and the clock (never the body), what the service is (not) called
 * with, input rejected before the service, strict number parsing, and exception to status mapping
 * for errors that cannot be provoked end to end. Status and code of the ordinary business errors are
 * asserted by WorkflowIT, SupersedeIT and WithdrawalIT.
 */
@WebMvcTest(PricingExceptionController.class)
class PricingExceptionControllerTest {

    private static final String BASE = "/api/pricing-exceptions";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor SARA = Actor.reviewer("rev-sara", 50);
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final String APPROVE = "{\"outcome\":\"APPROVED\",\"comment\":\"ok\"}";
    private static final String CREATE = "{\"applicationId\":\"MA-1003\",\"discountBps\":40,\"reason\":\"why\"}";
    private static final String SUPERSEDE = "{\"discountBps\":30,\"reason\":\"Revised after talk\"}";

    @Autowired MockMvc mvc;
    @MockitoBean PricingExceptionService service;
    @MockitoBean UserLookup userLookup;

    @BeforeEach
    void users() {
        when(userLookup.findActor("rm-anna")).thenReturn(Optional.of(ANNA));
        when(userLookup.findActor("rev-sara")).thenReturn(Optional.of(SARA));
        when(userLookup.findActor("nobody")).thenReturn(Optional.empty());
        when(userLookup.findActor("")).thenReturn(Optional.empty());
    }

    private static PricingExceptionRequest pending() {
        return PricingExceptionRequest.submit(ID, "MA-1002", ANNA, DiscountBps.of(40, 100),
                new Reason("Long-standing customer"), null, CLOCK);
    }

    private static RequestView view(long version) {
        return new RequestView(pending(), version);
    }

    private ResultActions call(String method, String path, String user, String ifMatch, String body, String key) throws Exception {
        MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), path);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) b.header("X-User-Id", user);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(b);
    }

    private ResultActions decide(String user, String ifMatch, String body) throws Exception {
        return call("POST", BASE + "/" + ID + "/decision", user, ifMatch, body, null);
    }

    private ResultActions withdraw(String user, String ifMatch) throws Exception {
        return call("POST", BASE + "/" + ID + "/withdrawal", user, ifMatch, null, null);
    }

    private ResultActions supersede(String ifMatch, String key) throws Exception {
        return call("POST", BASE + "/" + ID + "/supersessions", "rm-anna", ifMatch, SUPERSEDE, key);
    }

    private static void problem(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---------- table: rejected before the service is called ----------

    /** display, method, path, caller, If-Match, body, expected status, code (null = status only), invalid field (or null). */
    static Stream<Arguments> rejectedBeforeTheService() {
        String d = BASE + "/" + ID + "/decision";
        String w = BASE + "/" + ID + "/withdrawal";
        String s = BASE + "/" + ID + "/supersessions";
        String unauth = "UNAUTHENTICATED";
        String bad = "MALFORMED_REQUEST";
        String invalid = "VALIDATION_FAILED";
        return Stream.of(
                Arguments.of("401 create without X-User-Id", "POST", BASE, null, null, CREATE, 401, unauth, null),
                Arguments.of("401 create with unknown user", "POST", BASE, "nobody", null, CREATE, 401, unauth, null),
                Arguments.of("401 create with blank X-User-Id", "POST", BASE, "", null, CREATE, 401, unauth, null),
                Arguments.of("401 get without X-User-Id", "GET", BASE + "/" + ID, null, null, null, 401, unauth, null),
                Arguments.of("401 list without X-User-Id", "GET", BASE, null, null, null, 401, unauth, null),
                Arguments.of("401 decision without X-User-Id", "POST", d, null, "\"0\"", APPROVE, 401, unauth, null),
                Arguments.of("401 withdrawal without X-User-Id", "POST", w, null, "\"0\"", null, 401, unauth, null),
                Arguments.of("401 supersede without X-User-Id", "POST", s, null, "\"0\"", SUPERSEDE, 401, unauth, null),
                Arguments.of("INV05 428 decision without If-Match", "POST", d, "rev-sara", null, APPROVE, 428, "IF_MATCH_REQUIRED", null),
                Arguments.of("INV05 428 withdrawal without If-Match", "POST", w, "rm-anna", null, null, 428, "IF_MATCH_REQUIRED", null),
                Arguments.of("INV05 428 supersede without If-Match", "POST", s, "rm-anna", null, SUPERSEDE, 428, "IF_MATCH_REQUIRED", null),
                Arguments.of("INV05 supersede with wildcard If-Match", "POST", s, "rm-anna", "*", SUPERSEDE, 400, bad, null),
                Arguments.of("INV05 decision with non-numeric If-Match", "POST", d, "rev-sara", "\"abc\"", APPROVE, 400, null, null),
                Arguments.of("422 blank applicationId", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"\",\"discountBps\":40,\"reason\":\"why\"}", 422, invalid, "applicationId"),
                Arguments.of("422 missing applicationId", "POST", BASE, "rm-anna", null,
                        "{\"discountBps\":40,\"reason\":\"why\"}", 422, invalid, "applicationId"),
                Arguments.of("422 missing discountBps", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1003\",\"reason\":\"why\"}", 422, invalid, "discountBps"),
                Arguments.of("422 blank reason", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1003\",\"discountBps\":40,\"reason\":\"   \"}", 422, invalid, "reason"),
                Arguments.of("422 missing reason", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1003\",\"discountBps\":40}", 422, invalid, "reason"),
                Arguments.of("422 reason of 501 chars", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1003\",\"discountBps\":40,\"reason\":\"" + "x".repeat(501) + "\"}", 422, invalid, "reason"),
                Arguments.of("422 decision without outcome", "POST", d, "rev-sara", "\"0\"", "{\"comment\":\"ok\"}", 422, invalid, "outcome"),
                Arguments.of("422 decision comment over 500 chars", "POST", d, "rev-sara", "\"0\"",
                        "{\"outcome\":\"APPROVED\",\"comment\":\"" + "c".repeat(501) + "\"}", 422, invalid, "comment"),
                Arguments.of("400 create with broken JSON", "POST", BASE, "rm-anna", null, "{\"applicationId\": ", 400, bad, null),
                Arguments.of("400 discountBps as a non-numeric string", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1\",\"discountBps\":\"abc\",\"reason\":\"r\"}", 400, bad, null),
                // Rates are whole basis points (CLAUDE.md). 40.5 must never reach the service as 40.
                Arguments.of("400 fractional discountBps", "POST", BASE, "rm-anna", null,
                        "{\"applicationId\":\"MA-1\",\"discountBps\":40.5,\"reason\":\"r\"}", 400, null, null),
                Arguments.of("400 decision with unknown outcome", "POST", d, "rev-sara", "\"0\"", "{\"outcome\":\"MAYBE\"}", 400, bad, null),
                Arguments.of("400 decision with broken JSON", "POST", d, "rev-sara", "\"0\"", "{oops", 400, bad, null),
                Arguments.of("400 get with non-UUID id", "GET", BASE + "/not-a-uuid", "rm-anna", null, null, 400, bad, null),
                Arguments.of("400 decision with non-UUID id", "POST", BASE + "/not-a-uuid/decision", "rev-sara", "\"0\"", APPROVE, 400, bad, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBeforeTheService")
    void api_input_is_rejected_before_the_service_is_called(String display, String method, String path, String user,
                                                           String ifMatch, String body, int status, String code,
                                                           String field) throws Exception {
        ResultActions r = call(method, path, user, ifMatch, body, null);
        if (code != null) {
            problem(r, status, code);
        } else {
            r.andExpect(status().is(status));
        }
        if (field != null) {
            r.andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]").isNotEmpty())
                    .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        }
        verifyNoInteractions(service);
    }

    // ---------- table: exception to status mapping (service mocked to throw) ----------

    /** display, what the service throws on decide, expected status, code. */
    static Stream<Arguments> serviceErrors() {
        return Stream.of(
                Arguments.of("INV03 self review is 403 SELF_REVIEW",
                        new ForbiddenActionException(ForbiddenActionException.SELF_REVIEW, "own request"), 403, "SELF_REVIEW"),
                Arguments.of("domain INVALID_COMMENT is 422",
                        new ValidationException(ValidationException.INVALID_COMMENT, "long"), 422, "INVALID_COMMENT"),
                Arguments.of("INV05 lost optimistic lock race is 409 CONCURRENT_MODIFICATION",
                        new ObjectOptimisticLockingFailureException(PricingExceptionRequest.class, ID), 409, "CONCURRENT_MODIFICATION"),
                Arguments.of("ApiProblem not found is 404", ApiProblem.requestNotFound(ID), 404, "REQUEST_NOT_FOUND"),
                Arguments.of("INV05 ApiProblem version mismatch is 412", ApiProblem.versionMismatch(3, 4), 412, "VERSION_MISMATCH"),
                Arguments.of("INV06 ApiProblem application not open is 409",
                        ApiProblem.applicationNotOpen("MA-1004", "CLOSED"), 409, "APPLICATION_NOT_OPEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceErrors")
    void api_service_errors_are_mapped_to_problem_json(String display, Throwable error, int status, String code) throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenThrow(error);
        problem(decide("rev-sara", "\"0\"", APPROVE), status, code);
    }

    @Test
    void api_unexpected_error_is_500_INTERNAL_ERROR_without_details() throws Exception {
        when(service.get(any())).thenThrow(new IllegalStateException("secret table pk_violation"));
        problem(call("GET", BASE + "/" + ID, "rm-anna", null, null, null), 500, "INTERNAL_ERROR");
        call("GET", BASE + "/" + ID, "rm-anna", null, null, null)
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void api_error_detail_is_plain_text_and_not_html_interpreted() throws Exception {
        when(service.get(any())).thenThrow(new ApiProblem(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "<script>alert(1)</script>"));
        call("GET", BASE + "/" + ID, "rm-anna", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("<script>alert(1)</script>"));
    }

    @Test
    void api_create_returns_201_with_location_and_etag() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString())).thenReturn(view(0));

        call("POST", BASE, "rm-anna", null, "{\"applicationId\":\"MA-1002\",\"discountBps\":40,\"reason\":\"Long-standing customer\"}", null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pricing-exceptions/" + ID))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.applicationId").value("MA-1002"))
                .andExpect(jsonPath("$.requestedBy").value("rm-anna"))
                .andExpect(jsonPath("$.discountBps").value(40))
                .andExpect(jsonPath("$.reason").value("Long-standing customer"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T10:00:00Z"))
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[0].actorId").value("rm-anna"))
                .andExpect(jsonPath("$.events[0].toStatus").value("PENDING"));

        verify(service).create(ANNA, "MA-1002", 40, "Long-standing customer");
    }

    @Test
    void api_create_passes_caller_from_header_not_from_body() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString())).thenReturn(view(0));
        call("POST", BASE, "rm-anna", null,
                "{\"applicationId\":\"MA-1003\",\"discountBps\":40,\"reason\":\"r\",\"requestedBy\":\"rm-erik\"}", null)
                .andExpect(status().isCreated());
        verify(service).create(eq(ANNA), eq("MA-1003"), eq(40), eq("r"));
    }

    @Test
    void api_decision_without_comment_passes_null_comment() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(1));
        decide("rev-sara", "\"0\"", "{\"outcome\":\"DECLINED\"}").andExpect(status().isOk());
        verify(service).decide(SARA, ID, 0L, Decision.DECLINED, null);
    }

    @Test
    void INV05_if_match_quoted_3_is_passed_as_expectedVersion_3() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(4));
        decide("rev-sara", "\"3\"", APPROVE).andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""));

        ArgumentCaptor<Long> version = ArgumentCaptor.forClass(Long.class);
        verify(service).decide(eq(SARA), eq(ID), version.capture(), eq(Decision.APPROVED), eq("ok"));
        assertThat(version.getValue()).isEqualTo(3L);
    }

    @Test
    void INV05_withdrawal_if_match_quoted_5_is_passed_as_expectedVersion_5() throws Exception {
        PricingExceptionRequest r = pending();
        r.withdraw(ANNA, CLOCK);
        when(service.withdraw(any(), any(), anyLong())).thenReturn(new RequestView(r, 6));
        withdraw("rm-anna", "\"5\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        verify(service).withdraw(ANNA, ID, 5L);
    }

    @Test
    void INV03_decidedBy_and_decidedAt_in_body_are_ignored_caller_comes_from_header() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(1));
        decide("rev-sara", "\"0\"", "{\"outcome\":\"APPROVED\",\"comment\":\"ok\",\"decidedBy\":\"rev-johan\","
                + "\"decidedAt\":\"2020-01-01T00:00:00Z\"}").andExpect(status().isOk());
        verify(service).decide(SARA, ID, 0L, Decision.APPROVED, "ok");
        verify(service, never()).decide(eq(Actor.reviewer("rev-johan", 150)), any(), anyLong(), any(), any());
    }

    @Test
    void api_supersede_passes_if_match_as_expected_version_and_returns_201_with_new_request() throws Exception {
        PricingExceptionRequest replacement = PricingExceptionRequest.submit(NEW_ID, "MA-1002", ANNA,
                DiscountBps.of(30, 100), new Reason("Revised after talk"), ID, CLOCK);
        when(service.supersede(any(), any(), anyLong(), anyInt(), anyString())).thenReturn(new RequestView(replacement, 0));

        supersede("\"2\"", null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pricing-exceptions/" + NEW_ID))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(NEW_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.supersedesId").value(ID.toString()))
                .andExpect(jsonPath("$.discountBps").value(30))
                .andExpect(jsonPath("$.reason").value("Revised after talk"));

        verify(service).supersede(ANNA, ID, 2L, 30, "Revised after talk");
        verify(service, never()).supersedeIdempotently(any(), any(), any(), any());
    }

    @Test
    void api_supersede_with_idempotency_key_uses_idempotent_path_and_returns_stored_response() throws Exception {
        when(service.supersedeIdempotently(any(), anyString(), any(), any()))
                .thenReturn(new IdempotentResult(201, "{\"stored\":true}", NEW_ID, 0, true));

        supersede("\"2\"", "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pricing-exceptions/" + NEW_ID))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"stored\":true}"));

        ArgumentCaptor<SupersedeCommand> cmd = ArgumentCaptor.forClass(SupersedeCommand.class);
        verify(service).supersedeIdempotently(eq(ANNA), eq("key-1"), cmd.capture(), any());
        assertThat(cmd.getValue()).isEqualTo(new SupersedeCommand(ID, 2L, 30, "Revised after talk"));
        verify(service, never()).supersede(any(), any(), anyLong(), anyInt(), anyString());
    }
}
