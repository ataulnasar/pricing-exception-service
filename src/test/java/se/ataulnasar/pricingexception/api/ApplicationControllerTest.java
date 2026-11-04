package se.ataulnasar.pricingexception.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.ataulnasar.pricingexception.application.ApplicationInfo;
import se.ataulnasar.pricingexception.application.ApprovedDiscount;
import se.ataulnasar.pricingexception.application.NotFoundException;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.ReferenceDataService;
import se.ataulnasar.pricingexception.application.UserInfo;
import se.ataulnasar.pricingexception.application.UserLookup;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.ForbiddenActionException;
import se.ataulnasar.pricingexception.domain.Role;

/** Phase 5: mapping of the approved-discount endpoint and the reference-data endpoints. Services are mocked. */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    private static final Actor ANNA = Actor.rm("rm-anna");
    private static final Actor SYSTEM = Actor.mortgageProcess("mortgage-system");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean PricingExceptionService service;
    @MockitoBean ReferenceDataService referenceData;
    @MockitoBean UserLookup userLookup;

    @BeforeEach
    void users() {
        when(userLookup.findActor("rm-anna")).thenReturn(Optional.of(ANNA));
        when(userLookup.findActor("mortgage-system")).thenReturn(Optional.of(SYSTEM));
        when(userLookup.findActor("nobody")).thenReturn(Optional.empty());
    }

    private ResultActions perform(String path, String user) throws Exception {
        MockHttpServletRequestBuilder b = get(path);
        if (user != null) b.header("X-User-Id", user);
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

    // ---------- authentication ----------

    @Test
    void api_approved_discount_without_user_is_401() throws Exception {
        problem(perform("/api/applications/MA-1001/approved-discount", null), 401, "UNAUTHENTICATED");
        problem(perform("/api/applications/MA-1001/approved-discount", "nobody"), 401, "UNAUTHENTICATED");
        verifyNoInteractions(service);
    }

    @Test
    void api_applications_and_users_without_user_are_401() throws Exception {
        problem(perform("/api/applications", null), 401, "UNAUTHENTICATED");
        problem(perform("/api/users", null), 401, "UNAUTHENTICATED");
        problem(perform("/api/applications", "nobody"), 401, "UNAUTHENTICATED");
        problem(perform("/api/users", "nobody"), 401, "UNAUTHENTICATED");
        verifyNoInteractions(referenceData);
    }

    // ---------- approved discount ----------

    @Test
    void api_approved_discount_wrong_role_is_403_WRONG_ROLE() throws Exception {
        when(service.approvedDiscount(any(), anyString()))
                .thenThrow(new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE, "only the mortgage process"));
        problem(perform("/api/applications/MA-1001/approved-discount", "rm-anna"), 403, "WRONG_ROLE");
        verify(service).approvedDiscount(eq(ANNA), eq("MA-1001"));
    }

    @Test
    void api_approved_discount_unknown_application_is_404_APPLICATION_NOT_FOUND() throws Exception {
        when(service.approvedDiscount(any(), anyString()))
                .thenThrow(new NotFoundException("APPLICATION_NOT_FOUND", "no such application"));
        problem(perform("/api/applications/MA-9/approved-discount", "mortgage-system"), 404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void api_approved_discount_none_is_404_NO_APPROVED_DISCOUNT() throws Exception {
        when(service.approvedDiscount(any(), anyString()))
                .thenThrow(new NotFoundException("NO_APPROVED_DISCOUNT", "<b>nothing approved</b>"));
        problem(perform("/api/applications/MA-1002/approved-discount", "mortgage-system"), 404, "NO_APPROVED_DISCOUNT");
    }

    @Test
    void api_approved_discount_maps_all_five_fields_and_passes_caller_and_id() throws Exception {
        when(service.approvedDiscount(any(), anyString())).thenReturn(
                new ApprovedDiscount("MA-1001", 30, REQUEST_ID, "rev-sara", Instant.parse("2026-01-10T09:30:00Z")));

        perform("/api/applications/MA-1001/approved-discount", "mortgage-system")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.applicationId").value("MA-1001"))
                .andExpect(jsonPath("$.discountBps").value(30))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.decidedBy").value("rev-sara"))
                .andExpect(jsonPath("$.decidedAt").value("2026-01-10T09:30:00Z"))
                .andExpect(jsonPath("$.length()").value(5));

        verify(service).approvedDiscount(SYSTEM, "MA-1001");
    }

    // ---------- reference data ----------

    @Test
    void api_applications_maps_fields() throws Exception {
        when(referenceData.applications()).thenReturn(List.of(
                new ApplicationInfo("MA-1001", "CUST-0001", 425, "OPEN", "rm-anna"),
                new ApplicationInfo("MA-1004", "CUST-0004", 395, "CLOSED", "rm-erik")));

        perform("/api/applications", "rm-anna")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("MA-1001"))
                .andExpect(jsonPath("$[0].customerRef").value("CUST-0001"))
                .andExpect(jsonPath("$[0].standardRateBps").value(425))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].ownerRmId").value("rm-anna"))
                .andExpect(jsonPath("$[1].id").value("MA-1004"))
                .andExpect(jsonPath("$[1].status").value("CLOSED"))
                .andExpect(jsonPath("$[1].ownerRmId").value("rm-erik"));
    }

    @Test
    void api_users_maps_fields_with_null_limit_for_non_reviewers() throws Exception {
        when(referenceData.users()).thenReturn(List.of(
                new UserInfo("rm-anna", "Anna Lind", Role.RM, null),
                new UserInfo("rev-sara", "Sara Holm", Role.REVIEWER, 50)));

        perform("/api/users", "mortgage-system")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("rm-anna"))
                .andExpect(jsonPath("$[0].name").value("Anna Lind"))
                .andExpect(jsonPath("$[0].role").value("RM"))
                .andExpect(jsonPath("$[0].approvalLimitBps").value((Object) null))
                .andExpect(jsonPath("$[1].role").value("REVIEWER"))
                .andExpect(jsonPath("$[1].approvalLimitBps").value(50));
    }

    @Test
    void api_applications_empty_list_is_empty_array() throws Exception {
        when(referenceData.applications()).thenReturn(List.of());
        perform("/api/applications", "rm-anna").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
