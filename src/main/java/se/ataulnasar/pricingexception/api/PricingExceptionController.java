package se.ataulnasar.pricingexception.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ataulnasar.pricingexception.application.CreateRequestCommand;
import se.ataulnasar.pricingexception.application.IdempotentResult;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.RequestView;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.RequestStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pricing exception requests. The caller comes from {@code X-User-Id} (see {@link CallerArgumentResolver});
 * commands that change a request need {@code If-Match} with the version from the ETag.
 */
@RestController
@RequestMapping("/api/pricing-exceptions")
public class PricingExceptionController {

    public static final String BASE_PATH = "/api/pricing-exceptions";

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    static final int MAX_KEY_LENGTH = 128;

    private final PricingExceptionService service;
    private final JsonMapper json;

    public PricingExceptionController(PricingExceptionService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /**
     * Create. With an {@code Idempotency-Key} the first response (status, Location, ETag, body) is
     * stored and replayed for the same key and body, with {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<?> create(Actor caller,
                                    @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                    @Valid @RequestBody CreateRequestBody body) {
        if (idempotencyKey == null) {
            RequestView view = service.create(caller, body.applicationId(), body.discountBps(), body.reason());
            return ResponseEntity.created(location(view.request().id()))
                    .eTag(etag(view))
                    .body(PricingExceptionResponse.from(view));
        }
        String key = idempotencyKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new MalformedRequestException(IDEMPOTENCY_KEY + " must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        IdempotentResult result = service.createIdempotently(caller, key,
                new CreateRequestCommand(body.applicationId(), body.discountBps(), body.reason()),
                view -> json.writeValueAsString(PricingExceptionResponse.from(view)));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .location(location(result.requestId()))
                .eTag("\"" + result.version() + "\"")
                .contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PricingExceptionResponse> get(Actor caller, @PathVariable UUID id) {
        return withEtag(service.get(id));
    }

    @GetMapping
    public List<PricingExceptionResponse> list(Actor caller,
                                               @RequestParam(required = false) RequestStatus status,
                                               @RequestParam(required = false) String applicationId) {
        return service.list(status, applicationId).stream().map(PricingExceptionResponse::from).toList();
    }

    @PostMapping("/{id}/decision")
    public ResponseEntity<PricingExceptionResponse> decide(Actor caller,
                                                           @PathVariable UUID id,
                                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                           @Valid @RequestBody DecisionBody body) {
        long expectedVersion = IfMatch.requiredVersion(ifMatch);
        return withEtag(service.decide(caller, id, expectedVersion, body.outcome(), body.comment()));
    }

    @PostMapping("/{id}/withdrawal")
    public ResponseEntity<PricingExceptionResponse> withdraw(Actor caller,
                                                             @PathVariable UUID id,
                                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        long expectedVersion = IfMatch.requiredVersion(ifMatch);
        return withEtag(service.withdraw(caller, id, expectedVersion));
    }

    private static ResponseEntity<PricingExceptionResponse> withEtag(RequestView view) {
        return ResponseEntity.ok().eTag(etag(view)).body(PricingExceptionResponse.from(view));
    }

    private static URI location(UUID id) {
        return URI.create(BASE_PATH + "/" + id);
    }

    private static String etag(RequestView view) {
        return "\"" + view.version() + "\"";
    }
}
