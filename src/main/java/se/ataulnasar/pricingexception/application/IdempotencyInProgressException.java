package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.DomainException;

/** The first request with this key is still being processed. Maps to 409. */
public class IdempotencyInProgressException extends DomainException {

    public static final String CODE = "IDEMPOTENCY_IN_PROGRESS";

    public IdempotencyInProgressException(String key) {
        super(CODE, "a request with Idempotency-Key " + key + " is still in progress; retry shortly");
    }
}
