package se.ataulnasar.pricingexception.exception;

/** Base for business-rule errors (INV-01 to INV-06, docs/invariants.yaml). Every one carries a stable {@code code} that the API maps to a ProblemDetail. */
public abstract class BusinessRuleException extends RuntimeException {

    private final String code;

    protected BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
