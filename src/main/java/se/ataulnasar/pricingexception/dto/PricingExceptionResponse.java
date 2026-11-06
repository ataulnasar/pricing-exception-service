package se.ataulnasar.pricingexception.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;

/**
 * A request with its decision and full history, as returned by every endpoint. {@code version} backs
 * the ETag. {@code reason} and comments are plain text.
 */
public record PricingExceptionResponse(
        UUID id,
        String applicationId,
        String requestedBy,
        int discountBps,
        String reason,
        RequestStatus status,
        UUID supersedesId,
        UUID supersededById,
        String decidedBy,
        Instant decidedAt,
        String decisionComment,
        Integer approvedDiscountBps,
        Instant createdAt,
        long version,
        List<EventResponse> events) {

    /**
     * @param supersededById the request that replaced this one (the reverse of {@code supersedesId}), or {@code null}
     */
    public static PricingExceptionResponse from(PricingExceptionRequestEntity r, UUID supersededById) {
        return new PricingExceptionResponse(
                r.getId(),
                r.getApplicationId(),
                r.getRequestedBy(),
                r.getDiscountBps(),
                r.getReason(),
                r.getStatus(),
                r.getSupersedesId(),
                supersededById,
                r.getDecidedBy(),
                r.getDecidedAt(),
                r.getDecisionComment(),
                r.getApprovedDiscountBps(),
                r.getCreatedAt(),
                r.getVersion(),
                r.getEvents().stream().map(EventResponse::from).toList());
    }
}
