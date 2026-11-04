package se.ataulnasar.pricingexception.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import se.ataulnasar.pricingexception.application.RequestView;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.RequestStatus;

/** A request with its decision and full history. {@code reason} and comments are plain text. */
public record PricingExceptionResponse(
        UUID id,
        String applicationId,
        String requestedBy,
        int discountBps,
        String reason,
        RequestStatus status,
        UUID supersedesId,
        String decidedBy,
        Instant decidedAt,
        String decisionComment,
        Integer approvedDiscountBps,
        Instant createdAt,
        long version,
        List<EventResponse> events) {

    static PricingExceptionResponse from(RequestView view) {
        PricingExceptionRequest r = view.request();
        return new PricingExceptionResponse(
                r.id(),
                r.applicationId(),
                r.requestedBy(),
                r.discount().value(),
                r.reason().value(),
                r.status(),
                r.supersedesId(),
                r.decidedBy(),
                r.decidedAt(),
                r.decisionComment(),
                r.approvedDiscount() == null ? null : r.approvedDiscount().value(),
                r.createdAt(),
                view.version(),
                r.events().stream().map(EventResponse::from).toList());
    }
}
