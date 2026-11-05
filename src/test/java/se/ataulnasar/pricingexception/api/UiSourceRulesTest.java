package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Phase 7 source rules for the static UI. Plain JUnit, reads the files from the project tree. */
class UiSourceRulesTest {

    private static String read(String name) throws IOException {
        try (InputStream in = UiSourceRulesTest.class.getResourceAsStream("/static/" + name)) {
            assertThat(in).as("classpath resource /static/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ui_source_has_no_html_sinks_and_uses_textContent() throws IOException {
        String js = read("app.js");
        for (String bad : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                "new Function(")) {
            assertThat(js).as("forbidden: " + bad).doesNotContain(bad);
        }
        assertThat(js).contains("textContent");
    }

    @Test
    void ui_has_no_inline_event_handlers_or_inline_scripts() throws IOException {
        String html = read("index.html");
        assertThat(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("inline event handler").isFalse();
        assertThat(html.toLowerCase()).doesNotContain("javascript:");
        Matcher m = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        List<String> tags = new ArrayList<>();
        while (m.find()) {
            tags.add(m.group());
        }
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0)).containsPattern("src\\s*=\\s*[\"']app\\.js[\"']");
        // no inline script body: whatever sits between <script ...> and </script> must be blank
        Matcher bodies = Pattern.compile("<script\\b[^>]*>(.*?)</script>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        while (bodies.find()) {
            assertThat(bodies.group(1)).as("inline script body").isBlank();
        }
    }

    @Test
    void ui_sends_identity_version_and_idempotency_headers() throws IOException {
        String js = read("app.js");
        assertThat(js).contains("X-User-Id", "If-Match", "Idempotency-Key", "crypto.randomUUID()");
        for (String path : List.of("/decision", "/withdrawal", "/supersessions")) {
            assertThat(optionsAfterCall(js, path)).as("options of POST " + path).contains("ifMatch:");
        }
        assertThat(optionsAfterCall(js, "/supersessions")).contains("idempotencyKey:");
        assertThat(optionsAfterPost(js, "'/api/pricing-exceptions'")).contains("idempotencyKey:");
    }

    /** The 200 characters after the api('POST', ... + '<path>', call. */
    private static String optionsAfterCall(String js, String path) {
        Matcher m = Pattern.compile("api\\(\\s*'POST'\\s*,[^;]*?\\+\\s*'" + Pattern.quote(path) + "'\\s*,").matcher(js);
        assertThat(m.find()).as("POST call to " + path).isTrue();
        return js.substring(m.end(), Math.min(js.length(), m.end() + 200));
    }

    private static String optionsAfterPost(String js, String literal) {
        Matcher m = Pattern.compile("api\\(\\s*'POST'\\s*,\\s*" + Pattern.quote(literal) + "\\s*,").matcher(js);
        assertThat(m.find()).as("POST create call").isTrue();
        return js.substring(m.end(), Math.min(js.length(), m.end() + 200));
    }

    @Test
    void ui_every_element_id_used_by_app_js_exists_in_index_html() throws IOException {
        String js = read("app.js");
        String html = read("index.html");
        Matcher m = Pattern.compile("\\$\\(\\s*'([^']+)'\\s*\\)").matcher(js);
        int n = 0;
        while (m.find()) {
            n++;
            String id = m.group(1);
            boolean inHtml = html.contains("id=\"" + id + "\"");
            boolean inJs = Pattern.compile("\\bid:\\s*'" + Pattern.quote(id) + "'").matcher(js).find();
            assertThat(inHtml || inJs).as("element id " + id).isTrue();
        }
        assertThat(n).isGreaterThan(0);
    }

    @Test
    void ui_handles_412_with_the_reload_message() throws IOException {
        assertThat(read("app.js")).contains("412", "This request changed");
    }

    @Test
    void ui_uses_only_documented_api_paths() throws IOException {
        String js = read("app.js");
        List<String> roots = List.of("/api/users", "/api/applications", "/api/pricing-exceptions");
        Matcher m = Pattern.compile("[\"'`](/api/[^\"'`?]*)").matcher(js);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).isNotEmpty();
        for (String p : found) {
            assertThat(roots.stream().anyMatch(r -> p.equals(r) || p.startsWith(r + "/")))
                    .as("undocumented API path " + p).isTrue();
        }
        assertThat(js).contains("/api/users", "/api/applications", "/api/pricing-exceptions", "/decision",
                "/withdrawal", "/supersessions", "/approved-discount");
    }

    /** Body of "function name(...) {" up to the next top-level function (heuristic: next "\n  function " or "\n  async function "). */
    private static String functionBody(String js, String name) {
        Matcher m = Pattern.compile("\\n  (?:async )?function " + name + "\\s*\\(").matcher(js);
        assertThat(m.find()).as("function " + name).isTrue();
        Matcher next = Pattern.compile("\\n  (?:async )?function |\\n  // -{5,}").matcher(js);
        int end = next.find(m.end()) ? next.start() : js.length();
        return js.substring(m.start(), end);
    }

    @Test
    void ui_startup_loads_the_list_and_renders_rows_or_the_empty_message() throws IOException {
        String js = read("app.js");
        Matcher dom = Pattern.compile("addEventListener\\(\\s*'DOMContentLoaded'").matcher(js);
        assertThat(dom.find()).as("DOMContentLoaded handler").isTrue();
        String startup = js.substring(dom.start());
        assertThat(startup).containsPattern("loadUsers\\(\\)").containsPattern("reloadAll\\(\\)");
        assertThat(functionBody(js, "reloadAll")).containsPattern("loadList\\(\\)");
        String loadList = functionBody(js, "loadList");
        assertThat(loadList).contains("/api/pricing-exceptions").containsPattern("renderList\\(\\)");
        String renderList = functionBody(js, "renderList");
        assertThat(renderList).containsPattern("\\$\\(\\s*'request-list'\\s*\\)").contains("'No requests.'");
    }

    @Test
    void ui_list_rows_are_focusable_but_not_styled_as_buttons() throws IOException {
        String js = read("app.js");
        String html = read("index.html");
        // Pico styles role=button as a dark button, which made the status text unreadable.
        assertThat(js).doesNotContainPattern("role\\s*[:=]\\s*[\"']button[\"']");
        String renderList = functionBody(js, "renderList");
        assertThat(renderList).contains("tabindex: 0").contains("onkeydown");
        assertThat(html).containsPattern("tr\\.selected\\s*[{,]").containsPattern("tr\\.selectable:hover\\s*[{,]");
    }
}
