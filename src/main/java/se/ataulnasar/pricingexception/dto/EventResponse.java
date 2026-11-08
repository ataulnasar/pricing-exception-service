package se.ataulnasar.pricingexception.dto;

import java.time.Instant;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.RequestEventEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;

/** One history entry of a request. */
public record EventResponse(
        EventType type,
        String actorId,
        Instant occurredAt,
        RequestStatus fromStatus,
        RequestStatus toStatus,
        String comment) {

    public static EventResponse from(RequestEventEntity e) {
        return new EventResponse(e.getType(), e.getActorId(), e.getOccurredAt(), e.getFromStatus(), e.getToStatus(), e.getComment());
    }
}
