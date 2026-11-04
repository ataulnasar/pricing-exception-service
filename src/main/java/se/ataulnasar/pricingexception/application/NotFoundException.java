package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.DomainException;

/** Maps to 404. Codes: REQUEST_NOT_FOUND, APPLICATION_NOT_FOUND. */
public class NotFoundException extends DomainException {

    public static final String REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND";
    public static final String APPLICATION_NOT_FOUND = "APPLICATION_NOT_FOUND";

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
