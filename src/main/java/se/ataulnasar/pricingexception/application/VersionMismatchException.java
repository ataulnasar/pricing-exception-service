package se.ataulnasar.pricingexception.application;

/** Kept only because tests still construct it; production code uses {@link ApiProblem#versionMismatch}. */
public class VersionMismatchException extends ApiProblem {

    public VersionMismatchException(long expected, long actual) {
        super(versionMismatch(expected, actual).status(), VERSION_MISMATCH, versionMismatch(expected, actual).getMessage());
    }
}
