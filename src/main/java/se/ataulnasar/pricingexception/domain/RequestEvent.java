package se.ataulnasar.pricingexception.domain;

import java.time.Instant;
import java.util.UUID;

/** One history entry. Exactly one is written per status change (INV-02). */
public record RequestEvent(
        UUID requestId,
        Type type,
        String actorId,
        Instant occurredAt,
        RequestStatus fromStatus,
        RequestStatus toStatus,
        String comment) {

    public enum Type {
        SUBMITTED,
        APPROVED,
        DECLINED,
        WITHDRAWN,
        SUPERSEDED
    }
}
