package se.ataulnasar.pricingexception.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * An error the API reports as a ProblemDetail with a stable {@code code} (table in docs/design.md
 * "API error codes"). One class for every "status + code" error that is not a business rule; the
 * business-rule exceptions ({@link BusinessRuleException}) keep their own types. Messages are plain text.
 */
public class ApiProblem extends RuntimeException {

    public static final String REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND";
    public static final String APPLICATION_NOT_FOUND = "APPLICATION_NOT_FOUND";
    public static final String NO_APPROVED_DISCOUNT = "NO_APPROVED_DISCOUNT";
    public static final String ACTIVE_REQUEST_EXISTS = "ACTIVE_REQUEST_EXISTS";
    public static final String APPLICATION_NOT_OPEN = "APPLICATION_NOT_OPEN";
    public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String IF_MATCH_REQUIRED = "IF_MATCH_REQUIRED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    private final HttpStatus status;
    private final String code;

    public ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    // ---------- 404 ----------

    public static ApiProblem requestNotFound(UUID id) {
        return new ApiProblem(HttpStatus.NOT_FOUND, REQUEST_NOT_FOUND, "pricing exception request " + id + " not found");
    }

    public static ApiProblem applicationNotFound(String applicationId) {
        return new ApiProblem(HttpStatus.NOT_FOUND, APPLICATION_NOT_FOUND,
                "mortgage application " + applicationId + " not found");
    }

    public static ApiProblem noApprovedDiscount(String applicationId) {
        return new ApiProblem(HttpStatus.NOT_FOUND, NO_APPROVED_DISCOUNT,
                "mortgage application " + applicationId + " has no approved pricing exception");
    }

    // ---------- 409 (INV-04, INV-06, idempotency) ----------

    public static ApiProblem activeRequestExists(String applicationId, UUID activeRequestId) {
        return new ApiProblem(HttpStatus.CONFLICT, ACTIVE_REQUEST_EXISTS,
                "application " + applicationId + " already has an active request " + activeRequestId);
    }

    public static ApiProblem applicationNotOpen(String applicationId, Object status) {
        return new ApiProblem(HttpStatus.CONFLICT, APPLICATION_NOT_OPEN,
                "mortgage application " + applicationId + " is " + status);
    }

    /** The first request with this key is still being processed. */
    public static ApiProblem idempotencyInProgress(String key) {
        return new ApiProblem(HttpStatus.CONFLICT, IDEMPOTENCY_IN_PROGRESS,
                "a request with Idempotency-Key " + key + " is still in progress; retry shortly");
    }

    // ---------- 412 / 422 / 428 (INV-05, design decisions 2 and 4) ----------

    /** The caller's If-Match version is not the stored version. */
    public static ApiProblem versionMismatch(long expected, long actual) {
        return new ApiProblem(HttpStatus.PRECONDITION_FAILED, VERSION_MISMATCH, "If-Match version " + expected
                + " does not match the current version " + actual + "; reload the request and try again");
    }

    /** Same caller and Idempotency-Key, different body. */
    public static ApiProblem idempotencyKeyReused(String key) {
        return new ApiProblem(HttpStatus.UNPROCESSABLE_CONTENT, IDEMPOTENCY_KEY_REUSED,
                "Idempotency-Key " + key + " was already used with a different request body");
    }

    public static ApiProblem ifMatchRequired() {
        return new ApiProblem(HttpStatus.PRECONDITION_REQUIRED, IF_MATCH_REQUIRED,
                "If-Match header with the request's current version (the ETag) is required");
    }

    // ---------- 400 / 401 ----------

    /** The request cannot be read: bad JSON, a non-UUID id, an unparsable header. */
    public static ApiProblem malformedRequest(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST, message);
    }

    /** Missing or unknown {@code X-User-Id}. */
    public static ApiProblem unauthenticated(String message) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED, message);
    }
}
