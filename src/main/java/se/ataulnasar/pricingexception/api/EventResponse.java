package se.ataulnasar.pricingexception.api;

import java.time.Instant;
import se.ataulnasar.pricingexception.domain.RequestEvent;
import se.ataulnasar.pricingexception.domain.RequestStatus;

public record EventResponse(
        RequestEvent.Type type,
        String actorId,
        Instant occurredAt,
        RequestStatus fromStatus,
        RequestStatus toStatus,
        String comment) {

    static EventResponse from(RequestEvent e) {
        return new EventResponse(e.type(), e.actorId(), e.occurredAt(), e.fromStatus(), e.toStatus(), e.comment());
    }
}
