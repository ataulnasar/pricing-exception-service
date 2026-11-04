package se.ataulnasar.pricingexception.api;

import se.ataulnasar.pricingexception.domain.DomainException;

/** The request cannot be read: bad JSON, a non-UUID id, an unparsable header. Maps to 400. */
public class MalformedRequestException extends DomainException {

    public static final String CODE = "MALFORMED_REQUEST";

    public MalformedRequestException(String message) {
        super(CODE, message);
    }
}
