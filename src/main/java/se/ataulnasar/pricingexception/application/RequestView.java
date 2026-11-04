package se.ataulnasar.pricingexception.application;

import java.util.UUID;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;

/**
 * A request as returned by the service: the aggregate, the stored version (ETag / If-Match) and, for a
 * SUPERSEDED request, the id of the request that replaced it (the other direction of the link).
 */
public record RequestView(PricingExceptionRequest request, long version, UUID supersededById) {

    public RequestView(PricingExceptionRequest request, long version) {
        this(request, version, null);
    }
}
