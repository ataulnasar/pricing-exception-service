package se.ataulnasar.pricingexception.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Shared HTTP helper for the integration tests (create, get, list, decide, withdraw, supersede,
 * approved-discount; X-User-Id, If-Match and Idempotency-Key headers) plus the "new application row"
 * fixture and the parallel runner. One helper, two transports: {@link #mock(MockMvc)} for the
 * MockMvc classes and {@link #http(int)} for the RANDOM_PORT classes (JDK HttpClient). Both give the same
 * {@link Resp}. A path argument named {@code target} is a request id or a full path such as a Location.
 */
public final class Api {

    public static final String BASE = "/api/pricing-exceptions";

    @FunctionalInterface
    interface Transport {
        Resp send(String method, String path, String user, String body, String... headers) throws Exception;
    }

    /** Status, headers (case-insensitive) and body bytes of one response. */
    public record Resp(int status, Map<String, String> headers, byte[] bytes) {
        public String body() { return new String(bytes, StandardCharsets.UTF_8); }

        public String header(String name) { return headers.get(name); }

        public String etag() { return header("ETag"); }

        public String location() { return header("Location"); }

        public boolean replayed() { return "true".equals(header("Idempotent-Replayed")); }

        public String str(String path) { return JsonPath.read(body(), path); }

        public int num(String path) { return JsonPath.<Integer>read(body(), path); }

        /** The value at the path, or null when the path is absent or null. */
        public Object any(String path) {
            try {
                return JsonPath.<Object>read(body(), path);
            } catch (PathNotFoundException e) {
                return null;
            }
        }

        public Map<String, Object> map() { return JsonPath.read(body(), "$"); }

        /** The body as a JSON array of objects. */
        public List<Map<String, Object>> json() { return JsonPath.read(body(), "$"); }

        public List<String> strings(String path) { return JsonPath.read(body(), path); }

        public String id() { return str("$.id"); }

        public int events() { return num("$.events.length()"); }

        /** Asserts path/value pairs, e.g. {@code has("$.status", "PENDING", "$.version", 0)}. */
        public Resp has(Object... pathAndValue) {
            for (int i = 0; i < pathAndValue.length; i += 2) {
                assertThat(any((String) pathAndValue[i])).as((String) pathAndValue[i]).isEqualTo(pathAndValue[i + 1]);
            }
            return this;
        }

        /** Status only when code is null, otherwise status plus problem+json plus code. */
        public Resp is(int expected, String code) {
            return code == null ? status(expected) : problem(expected, code);
        }

        /** Asserts the status (the body is the failure message) and returns this. */
        public Resp status(int expected) {
            assertThat(status).as(body()).isEqualTo(expected);
            return this;
        }

        /** Asserts status, application/problem+json and the error code; returns this. */
        public Resp problem(int expected, String code) {
            status(expected);
            assertThat(header("Content-Type")).startsWith("application/problem+json");
            assertThat(str("$.code")).isEqualTo(code);
            return this;
        }
    }

    private final Transport transport;

    private Api(Transport transport) {
        this.transport = transport;
    }

    public static Api mock(MockMvc mvc) {
        return new Api((method, path, user, body, headers) -> {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (user != null) b.header("X-User-Id", user);
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
            Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            r.getHeaderNames().forEach(n -> h.put(n, r.getHeader(n)));
            return new Resp(r.getStatus(), h, r.getContentAsByteArray());
        });
    }

    public static Api http(int port) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return new Api((method, path, user, body, headers) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30));
            if (user != null) b.header("X-User-Id", user);
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            r.headers().map().forEach((k, v) -> h.put(k, v.get(0)));
            return new Resp(r.statusCode(), h, r.body());
        });
    }

    // ---------- calls ----------

    public Resp send(String method, String path, String user, String body, String... headers) throws Exception {
        return transport.send(method, path, user, body, headers);
    }

    private static String path(String target) {
        return target.startsWith("/") ? target : BASE + "/" + target;
    }

    private static String[] headers(String ifMatch, String key) {
        List<String> h = new ArrayList<>();
        if (ifMatch != null) h.addAll(List.of("If-Match", ifMatch));
        if (key != null) h.addAll(List.of("Idempotency-Key", key));
        return h.toArray(String[]::new);
    }

    public Resp create(String user, String body, String key) throws Exception {
        return send("POST", BASE, user, body, headers(null, key));
    }

    public Resp create(String user, String app, int discount) throws Exception {
        return create(user, createBody(app, discount), null);
    }

    /** Creates a PENDING request (version 0), asserts 201 and returns its id. */
    public String createPending(String user, String app, int discount) throws Exception {
        return create(user, app, discount).status(201).id();
    }

    public Resp get(String user, String path) throws Exception {
        return send("GET", path, user, null);
    }

    /** GET of one request as rm-anna, asserted 200. */
    public Resp fetch(String target) throws Exception {
        return get("rm-anna", path(target)).status(200);
    }

    public Resp list(String user, String query) throws Exception {
        return get(user, BASE + (query.isEmpty() ? "" : "?" + query));
    }

    public Resp decision(String target, String user, String ifMatch, String body) throws Exception {
        return send("POST", path(target) + "/decision", user, body, headers(ifMatch, null));
    }

    public Resp decide(String target, String user, String outcome, String ifMatch) throws Exception {
        return decision(target, user, ifMatch, "{\"outcome\":\"" + outcome + "\",\"comment\":\"c\"}");
    }

    public Resp withdraw(String target, String user, String ifMatch) throws Exception {
        return send("POST", path(target) + "/withdrawal", user, null, headers(ifMatch, null));
    }

    public Resp supersede(String user, String target, String ifMatch, String body, String key) throws Exception {
        return send("POST", path(target) + "/supersessions", user, body, headers(ifMatch, key));
    }

    /** Supersedes as rm-anna with a valid body and the given version; asserts 201 and returns the new id. */
    public String supersedeOk(String target, long version, int discount) throws Exception {
        return supersede("rm-anna", target, q(version), supBody(discount), null).status(201).id();
    }

    public Resp approvedDiscount(String user, String app) throws Exception {
        return get(user, "/api/applications/" + app + "/approved-discount");
    }

    // ---------- bodies and fixtures ----------

    public static String q(long version) { return "\"" + version + "\""; }

    public static String newKey() { return "key-" + UUID.randomUUID(); }

    public static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\' || c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** Create body; {@code discount} is raw JSON text or a number, {@code reason} is plain text. */
    public static String createBody(String app, Object discount, String reason) {
        return "{\"applicationId\":" + json(app) + ",\"discountBps\":" + discount + ",\"reason\":" + json(reason) + "}";
    }

    public static String createBody(String app, Object discount) { return createBody(app, discount, "Customer has other offers"); }

    public static String supBody(Object discount, String reason) {
        return "{\"discountBps\":" + discount + ",\"reason\":" + json(reason) + "}";
    }

    public static String supBody(Object discount) { return supBody(discount, "Lower discount after review"); }

    /** Inserts a fresh OPEN application owned by rm-anna with a unique id. Leftovers are harmless. */
    public static String newApp(JdbcTemplate jdbc) {
        String id = newAppId();
        jdbc.update("INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id)"
                + " VALUES (?, ?, 450, 'OPEN', 'rm-anna')", id, "CUST-" + id);
        return id;
    }

    /** A unique application id that is not in the database. */
    public static String newAppId() {
        return "MA-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---------- parallel ----------

    /** Runs the task n times at the same moment (latch); results come back in submit order. */
    public static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        return parallel(Collections.nCopies(n, task));
    }

    /** Task i runs as tasks.get(i); results come back in the same order. */
    public static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
