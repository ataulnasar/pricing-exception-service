package se.ataulnasar.pricingexception.domain;

/** The request is not PENDING, so its status cannot change (INV-05). Code: INVALID_TRANSITION. */
public class InvalidTransitionException extends DomainException {

    public static final String CODE = "INVALID_TRANSITION";

    public InvalidTransitionException(RequestStatus current, String command) {
        super(CODE, "cannot " + command + " a request in status " + current);
    }
}
