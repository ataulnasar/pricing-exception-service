package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import se.ataulnasar.pricingexception.application.ConflictException;
import se.ataulnasar.pricingexception.application.Decision;
import se.ataulnasar.pricingexception.application.NotFoundException;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.RequestView;
import se.ataulnasar.pricingexception.application.UserLookup;
import se.ataulnasar.pricingexception.application.VersionMismatchException;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.ForbiddenActionException;
import se.ataulnasar.pricingexception.domain.InvalidTransitionException;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.RequestStatus;
import se.ataulnasar.pricingexception.domain.ValidationException;

@WebMvcTest(PricingExceptionController.class)
class PricingExceptionControllerTest {

    private static final String BASE = "/api/pricing-exceptions";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor SARA = Actor.reviewer("rev-sara", 50);
    private static final Actor SYSTEM = Actor.mortgageProcess("mortgage-system");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean PricingExceptionService service;
    @MockitoBean UserLookup userLookup;

    @BeforeEach
    void users() {
        when(userLookup.findActor("rm-anna")).thenReturn(Optional.of(ANNA));
        when(userLookup.findActor("rev-sara")).thenReturn(Optional.of(SARA));
        when(userLookup.findActor("mortgage-system")).thenReturn(Optional.of(SYSTEM));
        when(userLookup.findActor("nobody")).thenReturn(Optional.empty());
    }

    // ---------- helpers ----------

    private static PricingExceptionRequest pending() {
        return PricingExceptionRequest.submit(ID, "MA-1002", ANNA, DiscountBps.of(40, 100),
                new Reason("Long-standing customer"), null, CLOCK);
    }

    private static RequestView view(long version) {
        return new RequestView(pending(), version);
    }

    private static String createBody(String applicationId, String discount, String reason) {
        StringBuilder sb = new StringBuilder("{");
        if (applicationId != null) sb.append("\"applicationId\":\"").append(applicationId).append("\",");
        if (discount != null) sb.append("\"discountBps\":").append(discount).append(",");
        if (reason != null) sb.append("\"reason\":\"").append(reason).append("\",");
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append("}").toString();
    }

    private ResultActions create(String user, String body) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE).contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) b.header("X-User-Id", user);
        return mvc.perform(b);
    }

    private ResultActions decide(String user, String id, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + id + "/decision")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) b.header("X-User-Id", user);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return mvc.perform(b);
    }

    private ResultActions withdraw(String user, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + ID + "/withdrawal");
        if (user != null) b.header("X-User-Id", user);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return mvc.perform(b);
    }

    private static void problem(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").exists());
    }

    private static final String APPROVE = "{\"outcome\":\"APPROVED\",\"comment\":\"ok\"}";

    // ---------- authentication ----------

    @Test
    void api_create_without_user_header_is_401_UNAUTHENTICATED() throws Exception {
        problem(create(null, createBody("MA-1003", "40", "why")), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_create_with_unknown_user_is_401_UNAUTHENTICATED() throws Exception {
        problem(create("nobody", createBody("MA-1003", "40", "why")), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_get_without_user_header_is_401() throws Exception {
        problem(mvc.perform(get(BASE + "/" + ID)), 401, "UNAUTHENTICATED");
    }

    @Test
    void api_list_without_user_header_is_401() throws Exception {
        problem(mvc.perform(get(BASE)), 401, "UNAUTHENTICATED");
    }

    @Test
    void api_decision_without_user_header_is_401() throws Exception {
        problem(decide(null, ID.toString(), "\"0\"", APPROVE), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_withdrawal_without_user_header_is_401() throws Exception {
        problem(withdraw(null, "\"0\""), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_blank_user_header_is_401() throws Exception {
        when(userLookup.findActor("")).thenReturn(Optional.empty());
        problem(create("", createBody("MA-1003", "40", "why")), 401, "UNAUTHENTICATED");
    }

    // ---------- bean validation ----------

    @Test
    void api_create_blank_applicationId_error_names_applicationId() throws Exception {
        create("rm-anna", createBody("", "40", "why"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='applicationId')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void api_create_missing_applicationId_is_422() throws Exception {
        create("rm-anna", createBody(null, "40", "why"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field=='applicationId')]").isNotEmpty());
    }

    @Test
    void api_create_missing_discountBps_error_names_discountBps() throws Exception {
        create("rm-anna", createBody("MA-1003", null, "why"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='discountBps')]").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void api_create_blank_reason_error_names_reason() throws Exception {
        create("rm-anna", createBody("MA-1003", "40", "   "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='reason')]").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void api_create_missing_reason_is_422() throws Exception {
        create("rm-anna", createBody("MA-1003", "40", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field=='reason')]").isNotEmpty());
    }

    @Test
    void api_create_reason_of_501_chars_is_422_and_500_chars_is_accepted() throws Exception {
        create("rm-anna", createBody("MA-1003", "40", "x".repeat(501)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='reason')]").isNotEmpty());
        verifyNoInteractions(service);

        when(service.create(any(), anyString(), anyInt(), anyString())).thenReturn(view(0));
        create("rm-anna", createBody("MA-1003", "40", "x".repeat(500)))
                .andExpect(status().isCreated());
    }

    @Test
    void api_create_reports_all_invalid_fields_at_once() throws Exception {
        create("rm-anna", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void api_decision_missing_outcome_is_422_naming_outcome() throws Exception {
        decide("rev-sara", ID.toString(), "\"0\"", "{\"comment\":\"ok\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='outcome')]").isNotEmpty());
        verify(service, never()).decide(any(), any(), anyLong(), any(), any());
    }

    @Test
    void api_decision_comment_over_500_chars_is_422_naming_comment() throws Exception {
        decide("rev-sara", ID.toString(), "\"0\"",
                "{\"outcome\":\"APPROVED\",\"comment\":\"" + "c".repeat(501) + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='comment')]").isNotEmpty());
    }

    @Test
    void api_decision_unknown_outcome_value_is_400_MALFORMED_REQUEST() throws Exception {
        problem(decide("rev-sara", ID.toString(), "\"0\"", "{\"outcome\":\"MAYBE\"}"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void api_decision_without_comment_passes_null_comment() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(1));
        decide("rev-sara", ID.toString(), "\"0\"", "{\"outcome\":\"DECLINED\"}").andExpect(status().isOk());
        verify(service).decide(SARA, ID, 0L, Decision.DECLINED, null);
    }

    @Test
    void INV01_domain_INVALID_DISCOUNT_is_422_with_code() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new ValidationException(ValidationException.INVALID_DISCOUNT, "too high"));
        problem(create("rm-anna", createBody("MA-1003", "101", "why")), 422, "INVALID_DISCOUNT");
    }

    @Test
    void api_domain_INVALID_REASON_and_INVALID_COMMENT_are_422() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new ValidationException(ValidationException.INVALID_REASON, "blank"));
        problem(create("rm-anna", createBody("MA-1003", "40", "\\u200b")), 422, "INVALID_REASON");

        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new ValidationException(ValidationException.INVALID_COMMENT, "long"));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 422, "INVALID_COMMENT");
    }

    // ---------- create ----------

    @Test
    void api_create_returns_201_with_location_and_etag() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString())).thenReturn(view(0));

        create("rm-anna", createBody("MA-1002", "40", "Long-standing customer"))
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
        create("rm-anna", "{\"applicationId\":\"MA-1003\",\"discountBps\":40,\"reason\":\"r\",\"requestedBy\":\"rm-erik\"}")
                .andExpect(status().isCreated());
        verify(service).create(eq(ANNA), eq("MA-1003"), eq(40), eq("r"));
    }

    @Test
    void api_create_by_reviewer_is_403_WRONG_ROLE() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE, "only RM"));
        problem(create("rev-sara", createBody("MA-1003", "40", "why")), 403, "WRONG_ROLE");
        verify(service).create(eq(SARA), anyString(), anyInt(), anyString());
    }

    @Test
    void api_create_unknown_application_is_404_APPLICATION_NOT_FOUND() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new NotFoundException("APPLICATION_NOT_FOUND", "no such application"));
        problem(create("rm-anna", createBody("MA-9", "40", "why")), 404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void INV04_active_request_exists_is_409() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new ConflictException("ACTIVE_REQUEST_EXISTS", "already active"));
        problem(create("rm-anna", createBody("MA-1002", "40", "why")), 409, "ACTIVE_REQUEST_EXISTS");
    }

    @Test
    void INV06_application_not_open_is_409() throws Exception {
        when(service.create(any(), anyString(), anyInt(), anyString()))
                .thenThrow(new ConflictException("APPLICATION_NOT_OPEN", "closed"));
        problem(create("rm-anna", createBody("MA-1004", "40", "why")), 409, "APPLICATION_NOT_OPEN");
    }

    // ---------- malformed input ----------

    @Test
    void api_create_broken_json_is_400_MALFORMED_REQUEST() throws Exception {
        problem(create("rm-anna", "{\"applicationId\": "), 400, "MALFORMED_REQUEST");
        verifyNoInteractions(service);
    }

    @Test
    void api_create_discountBps_not_a_number_is_400_MALFORMED_REQUEST() throws Exception {
        problem(create("rm-anna", "{\"applicationId\":\"MA-1\",\"discountBps\":\"abc\",\"reason\":\"r\"}"),
                400, "MALFORMED_REQUEST");
    }

    @Test
    void api_create_fractional_discountBps_is_not_accepted() throws Exception {
        // Rates are whole basis points (CLAUDE.md). 40.5 must never reach the service as 40.
        create("rm-anna", "{\"applicationId\":\"MA-1\",\"discountBps\":40.5,\"reason\":\"r\"}")
                .andExpect(status().is4xxClientError());
        verify(service, never()).create(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void api_get_with_non_uuid_id_is_400_MALFORMED_REQUEST() throws Exception {
        problem(mvc.perform(get(BASE + "/not-a-uuid").header("X-User-Id", "rm-anna")), 400, "MALFORMED_REQUEST");
    }

    @Test
    void api_decision_with_non_uuid_id_is_400_MALFORMED_REQUEST() throws Exception {
        problem(decide("rev-sara", "not-a-uuid", "\"0\"", APPROVE), 400, "MALFORMED_REQUEST");
    }

    @Test
    void api_decision_broken_json_is_400_MALFORMED_REQUEST() throws Exception {
        problem(decide("rev-sara", ID.toString(), "\"0\"", "{oops"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void api_unexpected_error_is_500_INTERNAL_ERROR_without_details() throws Exception {
        when(service.get(any())).thenThrow(new IllegalStateException("secret table pk_violation"));
        problem(mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "rm-anna")), 500, "INTERNAL_ERROR");
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "rm-anna"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void api_error_detail_is_plain_text_and_not_html_interpreted() throws Exception {
        when(service.get(any())).thenThrow(new NotFoundException("REQUEST_NOT_FOUND", "<script>alert(1)</script>"));
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "rm-anna"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---------- get / list ----------

    @Test
    void api_get_returns_200_with_etag_equal_to_version() throws Exception {
        when(service.get(ID)).thenReturn(view(7));
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "mortgage-system"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void api_get_unknown_id_is_404_REQUEST_NOT_FOUND() throws Exception {
        when(service.get(any())).thenThrow(new NotFoundException("REQUEST_NOT_FOUND", "no such request"));
        problem(mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("X-User-Id", "rm-anna")),
                404, "REQUEST_NOT_FOUND");
    }

    @Test
    void api_get_shows_decision_fields_and_both_events_after_approval() throws Exception {
        PricingExceptionRequest r = pending();
        r.approve(Actor.reviewer("rev-johan", 150), "fine",
                Clock.fixed(Instant.parse("2026-01-16T09:30:00Z"), ZoneOffset.UTC));
        when(service.get(ID)).thenReturn(new RequestView(r, 1));
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("rev-johan"))
                .andExpect(jsonPath("$.decidedAt").value("2026-01-16T09:30:00Z"))
                .andExpect(jsonPath("$.decisionComment").value("fine"))
                .andExpect(jsonPath("$.approvedDiscountBps").value(40))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[1].type").value("APPROVED"))
                .andExpect(jsonPath("$.events[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.events[1].toStatus").value("APPROVED"));
    }

    @Test
    void api_list_passes_filters_and_returns_array() throws Exception {
        when(service.list(RequestStatus.PENDING, "MA-1002")).thenReturn(List.of(view(0)));
        mvc.perform(get(BASE).param("status", "PENDING").param("applicationId", "MA-1002")
                        .header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
        verify(service).list(RequestStatus.PENDING, "MA-1002");
    }

    @Test
    void api_list_without_filters_passes_nulls_and_empty_result_is_empty_array() throws Exception {
        when(service.list(null, null)).thenReturn(List.of());
        mvc.perform(get(BASE).header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        verify(service).list(isNull(), isNull());
    }

    @Test
    void api_list_with_invalid_status_is_400_MALFORMED_REQUEST() throws Exception {
        problem(mvc.perform(get(BASE).param("status", "BOGUS").header("X-User-Id", "rm-anna")),
                400, "MALFORMED_REQUEST");
    }

    // ---------- decision ----------

    @Test
    void INV05_decision_without_if_match_is_428() throws Exception {
        problem(decide("rev-sara", ID.toString(), null, APPROVE), 428, "IF_MATCH_REQUIRED");
        verify(service, never()).decide(any(), any(), anyLong(), any(), any());
    }

    @Test
    void INV05_withdrawal_without_if_match_is_428() throws Exception {
        problem(withdraw("rm-anna", null), 428, "IF_MATCH_REQUIRED");
        verify(service, never()).withdraw(any(), any(), anyLong());
    }

    @Test
    void INV05_if_match_quoted_3_is_passed_as_expectedVersion_3() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(4));
        decide("rev-sara", ID.toString(), "\"3\"", APPROVE)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""));

        ArgumentCaptor<Long> version = ArgumentCaptor.forClass(Long.class);
        verify(service).decide(eq(SARA), eq(ID), version.capture(), eq(Decision.APPROVED), eq("ok"));
        assertThat(version.getValue()).isEqualTo(3L);
    }

    @Test
    void INV05_withdrawal_if_match_quoted_5_is_passed_as_expectedVersion_5() throws Exception {
        PricingExceptionRequest r = pending();
        r.withdraw(ANNA, CLOCK);
        when(service.withdraw(any(), any(), anyLong())).thenReturn(new RequestView(r, 6));
        withdraw("rm-anna", "\"5\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        verify(service).withdraw(ANNA, ID, 5L);
    }

    @Test
    void INV05_if_match_that_is_not_a_number_is_rejected_without_calling_service() throws Exception {
        decide("rev-sara", ID.toString(), "\"abc\"", APPROVE).andExpect(status().is4xxClientError());
        verify(service, never()).decide(any(), any(), anyLong(), any(), any());
    }

    @Test
    void INV05_version_mismatch_is_412_VERSION_MISMATCH() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new VersionMismatchException(3, 4));
        problem(decide("rev-sara", ID.toString(), "\"3\"", APPROVE), 412, "VERSION_MISMATCH");
    }

    @Test
    void INV05_withdrawal_version_mismatch_is_412() throws Exception {
        when(service.withdraw(any(), any(), anyLong())).thenThrow(new VersionMismatchException(0, 1));
        problem(withdraw("rm-anna", "\"0\""), 412, "VERSION_MISMATCH");
    }

    @Test
    void INV05_lost_optimistic_lock_race_is_409_CONCURRENT_MODIFICATION() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        PricingExceptionRequest.class, ID));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 409, "CONCURRENT_MODIFICATION");
    }

    @Test
    void INV05_decision_on_non_pending_is_409_INVALID_TRANSITION() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new InvalidTransitionException(RequestStatus.APPROVED, "approve"));
        problem(decide("rev-sara", ID.toString(), "\"1\"", APPROVE), 409, "INVALID_TRANSITION");
    }

    @Test
    void INV05_withdrawal_on_non_pending_is_409_INVALID_TRANSITION() throws Exception {
        when(service.withdraw(any(), any(), anyLong()))
                .thenThrow(new InvalidTransitionException(RequestStatus.DECLINED, "withdraw"));
        problem(withdraw("rm-anna", "\"1\""), 409, "INVALID_TRANSITION");
    }

    @Test
    void INV03_self_review_is_403_with_code_SELF_REVIEW() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.SELF_REVIEW, "own request"));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 403, "SELF_REVIEW");
    }

    @Test
    void INV03_over_approval_limit_is_403_with_code_OVER_APPROVAL_LIMIT() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.OVER_APPROVAL_LIMIT, "limit 50"));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 403, "OVER_APPROVAL_LIMIT");
    }

    @Test
    void api_decision_by_rm_is_403_WRONG_ROLE_from_service() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE, "only reviewer"));
        problem(decide("rm-anna", ID.toString(), "\"0\"", APPROVE), 403, "WRONG_ROLE");
        verify(service).decide(eq(ANNA), eq(ID), eq(0L), eq(Decision.APPROVED), eq("ok"));
    }

    @Test
    void api_decision_unknown_request_is_404() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new NotFoundException("REQUEST_NOT_FOUND", "missing"));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 404, "REQUEST_NOT_FOUND");
    }

    @Test
    void INV06_decision_on_closed_application_is_409_APPLICATION_NOT_OPEN() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any()))
                .thenThrow(new ConflictException("APPLICATION_NOT_OPEN", "closed"));
        problem(decide("rev-sara", ID.toString(), "\"0\"", APPROVE), 409, "APPLICATION_NOT_OPEN");
    }

    @Test
    void INV03_decidedBy_and_decidedAt_in_body_are_ignored_caller_comes_from_header() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(1));
        decide("rev-sara", ID.toString(), "\"0\"",
                "{\"outcome\":\"APPROVED\",\"comment\":\"ok\",\"decidedBy\":\"rev-johan\","
                        + "\"decidedAt\":\"2020-01-01T00:00:00Z\"}")
                .andExpect(status().isOk());
        verify(service).decide(SARA, ID, 0L, Decision.APPROVED, "ok");
        verify(service, never()).decide(eq(Actor.reviewer("rev-johan", 150)), any(), anyLong(), any(), any());
    }

    @Test
    void api_decision_decline_outcome_is_mapped() throws Exception {
        when(service.decide(any(), any(), anyLong(), any(), any())).thenReturn(view(1));
        decide("rev-sara", ID.toString(), "\"0\"", "{\"outcome\":\"DECLINED\",\"comment\":\"no\"}")
                .andExpect(status().isOk());
        verify(service).decide(SARA, ID, 0L, Decision.DECLINED, "no");
    }

    // ---------- withdrawal ----------

    @Test
    void api_withdrawal_not_owner_is_403_NOT_OWNER() throws Exception {
        when(service.withdraw(any(), any(), anyLong()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.NOT_OWNER, "not yours"));
        problem(withdraw("rm-anna", "\"0\""), 403, "NOT_OWNER");
    }

    @Test
    void api_withdrawal_by_reviewer_is_403_WRONG_ROLE() throws Exception {
        when(service.withdraw(any(), any(), anyLong()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE, "only RM"));
        problem(withdraw("rev-sara", "\"0\""), 403, "WRONG_ROLE");
    }

    @Test
    void api_withdrawal_accepts_empty_body_and_returns_200() throws Exception {
        PricingExceptionRequest r = pending();
        r.withdraw(ANNA, CLOCK);
        when(service.withdraw(any(), any(), anyLong())).thenReturn(new RequestView(r, 1));
        withdraw("rm-anna", "\"0\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    // ---------- supersede (Phase 5) ----------

    private ResultActions supersede(String user, String ifMatch, String body, String key) throws Exception {
        MockHttpServletRequestBuilder b = post(BASE + "/" + ID + "/supersessions")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (user != null) b.header("X-User-Id", user);
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (key != null) b.header("Idempotency-Key", key);
        return mvc.perform(b);
    }

    private static final String SUPERSEDE_BODY = "{\"discountBps\":30,\"reason\":\"Revised after talk\"}";

    @Test
    void INV05_api_supersede_without_if_match_is_428_and_service_not_called() throws Exception {
        problem(supersede("rm-anna", null, SUPERSEDE_BODY, null), 428, "IF_MATCH_REQUIRED");
        problem(supersede("rm-anna", null, SUPERSEDE_BODY, "some-key"), 428, "IF_MATCH_REQUIRED");
        verifyNoInteractions(service);
    }

    @Test
    void INV05_api_supersede_malformed_if_match_is_400_and_service_not_called() throws Exception {
        problem(supersede("rm-anna", "*", SUPERSEDE_BODY, null), 400, "MALFORMED_REQUEST");
        problem(supersede("rm-anna", "2", SUPERSEDE_BODY, null), 400, "MALFORMED_REQUEST");
        verifyNoInteractions(service);
    }

    @Test
    void api_supersede_without_user_is_401() throws Exception {
        problem(supersede(null, "\"0\"", SUPERSEDE_BODY, null), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_supersede_passes_if_match_as_expected_version_and_returns_201_with_new_request() throws Exception {
        UUID newId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        PricingExceptionRequest replacement = PricingExceptionRequest.submit(newId, "MA-1002", ANNA,
                DiscountBps.of(30, 100), new Reason("Revised after talk"), ID, CLOCK);
        when(service.supersede(any(), any(), anyLong(), anyInt(), anyString()))
                .thenReturn(new RequestView(replacement, 0));

        supersede("rm-anna", "\"2\"", SUPERSEDE_BODY, null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pricing-exceptions/" + newId))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(newId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.supersedesId").value(ID.toString()))
                .andExpect(jsonPath("$.discountBps").value(30))
                .andExpect(jsonPath("$.reason").value("Revised after talk"));

        verify(service).supersede(ANNA, ID, 2L, 30, "Revised after talk");
        verify(service, never()).supersedeIdempotently(any(), any(), any(), any());
    }

    @Test
    void api_supersede_with_idempotency_key_uses_idempotent_path_and_returns_stored_response() throws Exception {
        UUID newId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(service.supersedeIdempotently(any(), anyString(), any(), any()))
                .thenReturn(new se.ataulnasar.pricingexception.application.IdempotentResult(
                        201, "{\"stored\":true}", newId, 0, true));

        supersede("rm-anna", "\"2\"", SUPERSEDE_BODY, "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pricing-exceptions/" + newId))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"stored\":true}"));

        ArgumentCaptor<se.ataulnasar.pricingexception.application.SupersedeCommand> cmd =
                ArgumentCaptor.forClass(se.ataulnasar.pricingexception.application.SupersedeCommand.class);
        verify(service).supersedeIdempotently(eq(ANNA), eq("key-1"), cmd.capture(), any());
        assertThat(cmd.getValue()).isEqualTo(
                new se.ataulnasar.pricingexception.application.SupersedeCommand(ID, 2L, 30, "Revised after talk"));
        verify(service, never()).supersede(any(), any(), anyLong(), anyInt(), anyString());
    }

    @Test
    void api_supersede_first_idempotent_response_has_no_replayed_header() throws Exception {
        UUID newId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(service.supersedeIdempotently(any(), anyString(), any(), any()))
                .thenReturn(new se.ataulnasar.pricingexception.application.IdempotentResult(
                        201, "{}", newId, 0, false));

        supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, "key-2")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void api_supersede_idempotency_key_length_is_1_to_128() throws Exception {
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, "k".repeat(129)), 400, "MALFORMED_REQUEST");
        verifyNoInteractions(service);
    }

    @Test
    void api_supersede_blank_reason_is_422_VALIDATION_FAILED_and_service_not_called() throws Exception {
        supersede("rm-anna", "\"0\"", "{\"discountBps\":30,\"reason\":\"   \"}", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        problem(supersede("rm-anna", "\"0\"", "{\"discountBps\":30}", null), 422, "VALIDATION_FAILED");
        problem(supersede("rm-anna", "\"0\"", "{\"reason\":\"r\"}", null), 422, "VALIDATION_FAILED");
        problem(supersede("rm-anna", "\"0\"", "{\"discountBps\":30,\"reason\":\"" + "x".repeat(501) + "\"}", null),
                422, "VALIDATION_FAILED");
        verifyNoInteractions(service);
    }

    @Test
    void api_supersede_body_is_validated_before_if_match() throws Exception {
        problem(supersede("rm-anna", null, "{\"discountBps\":30,\"reason\":\"\"}", null), 422, "VALIDATION_FAILED");
        verifyNoInteractions(service);
    }

    @Test
    void api_supersede_domain_errors_are_mapped() throws Exception {
        when(service.supersede(any(), any(), anyLong(), anyInt(), anyString()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.NOT_OWNER, "not yours"))
                .thenThrow(new InvalidTransitionException(RequestStatus.APPROVED, "supersede"))
                .thenThrow(new ValidationException(ValidationException.INVALID_DISCOUNT, "bad"))
                .thenThrow(new VersionMismatchException(0, 1))
                .thenThrow(new NotFoundException("REQUEST_NOT_FOUND", "none"))
                .thenThrow(new ConflictException("APPLICATION_NOT_OPEN", "closed"));
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 403, "NOT_OWNER");
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 409, "INVALID_TRANSITION");
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 422, "INVALID_DISCOUNT");
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 412, "VERSION_MISMATCH");
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 404, "REQUEST_NOT_FOUND");
        problem(supersede("rm-anna", "\"0\"", SUPERSEDE_BODY, null), 409, "APPLICATION_NOT_OPEN");
    }
}
