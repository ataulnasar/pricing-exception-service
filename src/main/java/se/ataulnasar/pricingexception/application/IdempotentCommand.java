package se.ataulnasar.pricingexception.application;

/**
 * A parsed request body that can run under an Idempotency-Key. {@link #canonicalForm()} is the exact
 * text that is hashed, so it must be stable across releases and must not depend on JSON libraries.
 */
public interface IdempotentCommand {

    /** Stable text for the hash: the operation name, then each field length-prefixed. */
    String canonicalForm();

    /** Builds {@code operation\nlen:field\nlen:field...}; length prefixes keep fields from running together. */
    static String canonical(String operation, String... fields) {
        StringBuilder sb = new StringBuilder(operation);
        for (String field : fields) {
            sb.append('\n').append(field.length()).append(':').append(field);
        }
        return sb.toString();
    }
}
