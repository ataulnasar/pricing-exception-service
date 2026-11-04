package se.ataulnasar.pricingexception.domain;

/** A discount in whole basis points. Always at least 1; the upper bound is configuration. */
public record DiscountBps(int value) {

    public DiscountBps {
        if (value < 1) {
            throw new ValidationException(ValidationException.INVALID_DISCOUNT, "discountBps must be at least 1, was " + value);
        }
    }

    /** Creates a discount checked against the configured maximum ({@code pricing.max-discount-bps}). */
    public static DiscountBps of(int value, int maxBps) {
        if (value < 1 || value > maxBps) {
            throw new ValidationException(ValidationException.INVALID_DISCOUNT,
                    "discountBps must be between 1 and " + maxBps + ", was " + value);
        }
        return new DiscountBps(value);
    }
}
