package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.DomainException;

/** The caller's If-Match version is not the stored version (INV-05). Maps to 412. */
public class VersionMismatchException extends DomainException {

    public static final String CODE = "VERSION_MISMATCH";

    private final long expected;
    private final long actual;

    public VersionMismatchException(long expected, long actual) {
        super(CODE, "If-Match version " + expected + " does not match the current version " + actual
                + "; reload the request and try again");
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
