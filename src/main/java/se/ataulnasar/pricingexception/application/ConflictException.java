package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.DomainException;

/** Maps to 409. Codes: ACTIVE_REQUEST_EXISTS (INV-04), APPLICATION_NOT_OPEN (INV-06). */
public class ConflictException extends DomainException {

    public static final String ACTIVE_REQUEST_EXISTS = "ACTIVE_REQUEST_EXISTS";
    public static final String APPLICATION_NOT_OPEN = "APPLICATION_NOT_OPEN";

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
