package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Phase 7: the simple UI is served as static files and does not open the API. */
@SpringBootTest
@AutoConfigureMockMvc
class StaticUiIT {

    private static final String[] IDS = {"user-select", "status-filter", "request-list", "detail", "error",
            "create-form"};

    @Autowired MockMvc mvc;

    private void assertIndex(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/html");
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("<script src=\"app.js\"");
        for (String id : IDS) {
            assertThat(body).as("element id " + id).containsPattern("id\\s*=\\s*[\"']" + id + "[\"']");
        }
    }

    @Test
    void ui_index_is_served_at_root_and_index_html_without_user_header() throws Exception {
        // MockMvc does not run Boot's welcome-page forward, so for "/" we can only see the forward target.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        assertIndex(mvc.perform(get("/index.html")).andReturn());
    }

    @Test
    void ui_app_js_is_served_without_user_header() throws Exception {
        MvcResult r = mvc.perform(get("/app.js")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).matches("(text|application)/javascript.*");
        assertThat(r.getResponse().getContentAsString()).contains("fetch(");
    }

    @Test
    void ui_static_files_do_not_open_the_api() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/app.js").header("X-User-Id", "nobody-known")).andExpect(status().isOk());
    }

    @Test
    void ui_css_comes_from_a_pinned_cdn_version() throws Exception {
        String body = mvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("<link[^>]*rel\\s*=\\s*[\"']stylesheet[\"'][^>]*>").matcher(body);
        int count = 0;
        while (m.find()) {
            count++;
            assertThat(m.group()).containsPattern("integrity\\s*=\\s*\"sha384-[A-Za-z0-9+/]{64}\"");
            assertThat(m.group()).containsPattern("crossorigin\\s*=\\s*\"anonymous\"");
            Matcher href = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(m.group());
            assertThat(href.find()).isTrue();
            assertThat(href.group(1)).matches(
                    "^https://cdn\\.jsdelivr\\.net/npm/@picocss/pico@\\d+\\.\\d+\\.\\d+/css/pico\\.classless\\.min\\.css$");
        }
        assertThat(count).as("exactly one stylesheet").isEqualTo(1);
    }
}
