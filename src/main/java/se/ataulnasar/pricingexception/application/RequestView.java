package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;

/** A request as returned by the service: the aggregate plus the stored version (ETag / If-Match). */
public record RequestView(PricingExceptionRequest request, long version) {
}
