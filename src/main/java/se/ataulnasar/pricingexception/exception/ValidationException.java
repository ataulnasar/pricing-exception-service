package se.ataulnasar.pricingexception.exception;

/** Input is not acceptable. Codes: INVALID_DISCOUNT, INVALID_REASON, INVALID_COMMENT. */
public class ValidationException extends BusinessRuleException {

    public static final String INVALID_DISCOUNT = "INVALID_DISCOUNT";
    public static final String INVALID_REASON = "INVALID_REASON";
    public static final String INVALID_COMMENT = "INVALID_COMMENT";

    public ValidationException(String code, String message) {
        super(code, message);
    }
}
