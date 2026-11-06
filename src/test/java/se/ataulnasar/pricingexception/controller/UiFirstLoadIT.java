package se.ataulnasar.pricingexception.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The data the page asks for on first load, as rm-anna, against real H2 + seed data.
 *
 * <p>LIMITATION: this proves the API returns what the list needs. It does NOT prove that the DOM
 * shows the rows. That needs a real browser engine (for example Playwright or Selenium), which is
 * not a dependency of this project. The human decides whether to add one.
 *
 * <p>Other ITs share the same in-memory database and may have added requests and applications, so
 * the list and application checks use "contains" / "at least", not exact counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UiFirstLoadIT {

    private static final String USER = "rm-anna";
    private static final String R1 = "00000000-0000-0000-0000-000000000001";
    private static final String R2 = "00000000-0000-0000-0000-000000000002";
    private static final String R3 = "00000000-0000-0000-0000-000000000003";

    @Autowired MockMvc mvc;

    private void row(String id, String app, int bps, String status, String by) throws Exception {
        String base = "$[?(@.id=='" + id + "')]";
        mvc.perform(get("/api/pricing-exceptions").header("X-User-Id", USER))
                .andExpect(jsonPath(base + ".applicationId").value(hasItems(app)))
                .andExpect(jsonPath(base + ".discountBps").value(hasItems(bps)))
                .andExpect(jsonPath(base + ".status").value(hasItems(status)))
                .andExpect(jsonPath(base + ".requestedBy").value(hasItems(by)))
                .andExpect(jsonPath(base + ".createdAt").value(hasItems(not(emptyOrNullString()))));
    }

    @Test
    void ui_first_load_data_as_rm_anna_contains_the_three_seed_requests() throws Exception {
        // 1. users
        mvc.perform(get("/api/users").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[*].id")
                        .value(hasItems("rm-anna", "rm-erik", "rev-sara", "rev-johan", "mortgage-system")));

        // 2. list
        mvc.perform(get("/api/pricing-exceptions").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItems(R1, R2, R3)));
        row(R1, "MA-1001", 30, "APPROVED", "rm-anna");
        row(R2, "MA-1002", 80, "PENDING", "rm-anna");
        row(R3, "MA-1003", 60, "DECLINED", "rm-erik");

        // 3. applications
        mvc.perform(get("/api/applications").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$[*].id").value(hasItems("MA-1001", "MA-1002", "MA-1003", "MA-1004")));
    }
}
