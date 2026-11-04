package se.ataulnasar.pricingexception.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.RequestView;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.RequestStatus;

/**
 * Pricing exception requests. The caller comes from {@code X-User-Id} (see {@link CallerArgumentResolver});
 * commands that change a request need {@code If-Match} with the version from the ETag.
 */
@RestController
@RequestMapping("/api/pricing-exceptions")
public class PricingExceptionController {

    public static final String BASE_PATH = "/api/pricing-exceptions";

    private final PricingExceptionService service;

    public PricingExceptionController(PricingExceptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PricingExceptionResponse> create(Actor caller, @Valid @RequestBody CreateRequestBody body) {
        RequestView view = service.create(caller, body.applicationId(), body.discountBps(), body.reason());
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + view.request().id()))
                .eTag(etag(view))
                .body(PricingExceptionResponse.from(view));
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

    private static String etag(RequestView view) {
        return "\"" + view.version() + "\"";
    }
}
