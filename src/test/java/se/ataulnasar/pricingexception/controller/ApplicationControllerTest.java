package se.ataulnasar.pricingexception.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import se.ataulnasar.pricingexception.exception.ApiProblem;
import se.ataulnasar.pricingexception.dto.ApprovedDiscount;
import se.ataulnasar.pricingexception.service.PricingExceptionService;
import se.ataulnasar.pricingexception.service.ReferenceDataService;
import se.ataulnasar.pricingexception.service.UserLookup;
import se.ataulnasar.pricingexception.model.Actor;

/**
 * Mapping of the approved-discount endpoint and the empty reference-data list, services mocked.
 * Status and code of 401, 403 and 404 and the field mapping of the reference data are asserted
 * end to end in SupersedeIT (approved_discount_*, reference_data_*).
 */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    private static final Actor SYSTEM = Actor.mortgageProcess("mortgage-system");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean PricingExceptionService service;
    @MockitoBean ReferenceDataService referenceData;
    @MockitoBean UserLookup userLookup;

    @BeforeEach
    void users() {
        when(userLookup.findActor("rm-anna")).thenReturn(Optional.of(Actor.rm("rm-anna")));
        when(userLookup.findActor("mortgage-system")).thenReturn(Optional.of(SYSTEM));
    }

    @Test
    void api_approved_discount_maps_all_five_fields_and_passes_caller_and_id() throws Exception {
        when(service.approvedDiscount(any(), anyString())).thenReturn(
                new ApprovedDiscount("MA-1001", 30, REQUEST_ID, "rev-sara", Instant.parse("2026-01-10T09:30:00Z")));

        mvc.perform(get("/api/applications/MA-1001/approved-discount").header("X-User-Id", "mortgage-system"))
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

    @Test
    void api_approved_discount_error_detail_is_returned_as_plain_text() throws Exception {
        when(service.approvedDiscount(any(), anyString())).thenThrow(ApiProblem.noApprovedDiscount("MA-1002<b>"));

        mvc.perform(get("/api/applications/MA-1002/approved-discount").header("X-User-Id", "mortgage-system"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NO_APPROVED_DISCOUNT"))
                .andExpect(jsonPath("$.detail").value("mortgage application MA-1002<b> has no approved pricing exception"));
    }

    @Test
    void api_applications_empty_list_is_empty_array() throws Exception {
        when(referenceData.applications()).thenReturn(List.of());
        mvc.perform(get("/api/applications").header("X-User-Id", "rm-anna"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
