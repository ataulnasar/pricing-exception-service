package se.ataulnasar.pricingexception.domain;

/** Why the RM asks for the discount. Not blank, at most 500 characters. Stored as plain text. */
public record Reason(String value) {

    public static final int MAX_LENGTH = 500;

    public Reason {
        if (value == null || isBlank(value)) {
            throw new ValidationException(ValidationException.INVALID_REASON, "reason must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException(ValidationException.INVALID_REASON,
                    "reason must be at most " + MAX_LENGTH + " characters, was " + value.length());
        }
    }

    /**
     * Like {@link String#isBlank()} but also treats non-breaking spaces and invisible format characters
     * (zero-width space, byte order mark) as blank.
     */
    private static boolean isBlank(String s) {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || Character.isSpaceChar(cp)
                || Character.getType(cp) == Character.FORMAT);
    }
}
