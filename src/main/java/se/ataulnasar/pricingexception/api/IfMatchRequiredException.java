package se.ataulnasar.pricingexception.api;

import se.ataulnasar.pricingexception.domain.DomainException;

/** A decision or withdrawal without {@code If-Match}. Maps to 428 (design decision 4). */
public class IfMatchRequiredException extends DomainException {

    public static final String CODE = "IF_MATCH_REQUIRED";

    public IfMatchRequiredException() {
        super(CODE, "If-Match header with the request's current version (the ETag) is required");
    }
}
