package se.ataulnasar.pricingexception.exception;

import se.ataulnasar.pricingexception.model.RequestStatus;
/** The request is not PENDING, so its status cannot change (INV-05). Code: INVALID_TRANSITION. */
public class InvalidTransitionException extends BusinessRuleException {

    public static final String CODE = "INVALID_TRANSITION";

    public InvalidTransitionException(RequestStatus current, String command) {
        super(CODE, "cannot " + command + " a request in status " + current);
    }
}
