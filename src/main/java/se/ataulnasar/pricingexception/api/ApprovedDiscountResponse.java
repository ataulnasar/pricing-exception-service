package se.ataulnasar.pricingexception.api;

import java.time.Instant;
import java.util.UUID;
import se.ataulnasar.pricingexception.application.ApprovedDiscount;

public record ApprovedDiscountResponse(String applicationId, int discountBps, UUID requestId, String decidedBy, Instant decidedAt) {

    static ApprovedDiscountResponse from(ApprovedDiscount d) {
        return new ApprovedDiscountResponse(d.applicationId(), d.discountBps(), d.requestId(), d.decidedBy(), d.decidedAt());
    }
}
