package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.DomainException;

/** Same caller and Idempotency-Key, different body (design decision 2). Maps to 422. */
public class IdempotencyKeyReusedException extends DomainException {

    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException(String key) {
        super(CODE, "Idempotency-Key " + key + " was already used with a different request body");
    }
}
