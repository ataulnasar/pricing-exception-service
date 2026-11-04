package se.ataulnasar.pricingexception.domain;

/** The actor may not run this command. Codes: WRONG_ROLE, SELF_REVIEW, OVER_APPROVAL_LIMIT, NOT_OWNER. */
public class ForbiddenActionException extends DomainException {

    public static final String WRONG_ROLE = "WRONG_ROLE";
    public static final String SELF_REVIEW = "SELF_REVIEW";
    public static final String OVER_APPROVAL_LIMIT = "OVER_APPROVAL_LIMIT";
    public static final String NOT_OWNER = "NOT_OWNER";

    public ForbiddenActionException(String code, String message) {
        super(code, message);
    }
}
