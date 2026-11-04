package se.ataulnasar.pricingexception.api;

import se.ataulnasar.pricingexception.domain.DomainException;

/** Missing or unknown {@code X-User-Id}. Maps to 401. */
public class UnauthenticatedException extends DomainException {

    public static final String CODE = "UNAUTHENTICATED";

    public UnauthenticatedException(String message) {
        super(CODE, message);
    }
}
