package se.ataulnasar.pricingexception.application;

import java.time.Instant;
import java.util.UUID;

/** What the mortgage process reads: the approved discount of an application and who approved it. */
public record ApprovedDiscount(String applicationId, int discountBps, UUID requestId, String decidedBy, Instant decidedAt) {
}
