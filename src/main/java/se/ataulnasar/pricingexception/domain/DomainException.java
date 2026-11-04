package se.ataulnasar.pricingexception.domain;

/** Base for domain errors. Every one carries a stable {@code code} that the API maps to a ProblemDetail. */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
