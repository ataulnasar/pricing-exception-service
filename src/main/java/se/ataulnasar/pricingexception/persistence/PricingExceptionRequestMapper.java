package se.ataulnasar.pricingexception.persistence;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.RequestEvent;

/**
 * Hand-written mapping between the {@link PricingExceptionRequest} aggregate and its JPA rows.
 * The aggregate is the source of truth for status, decision fields, {@code activeApplicationId}
 * and history; this class only copies.
 */
@Component
public class PricingExceptionRequestMapper {

    /** Rebuilds the aggregate from stored rows without running any command. */
    public PricingExceptionRequest toDomain(PricingExceptionRequestEntity entity) {
        List<RequestEvent> events = entity.getEvents().stream()
                .map(e -> new RequestEvent(entity.getId(), e.getType(), e.getActorId(), e.getOccurredAt(),
                        e.getFromStatus(), e.getToStatus(), e.getComment()))
                .toList();
        PricingExceptionRequest request = PricingExceptionRequest.reconstitute(
                entity.getId(),
                entity.getApplicationId(),
                entity.getRequestedBy(),
                new DiscountBps(entity.getDiscountBps()),
                new Reason(entity.getReason()),
                entity.getCreatedAt(),
                entity.getSupersedesId(),
                entity.getStatus(),
                entity.getDecidedBy(),
                entity.getDecidedAt(),
                entity.getDecisionComment(),
                entity.getApprovedDiscountBps() == null ? null : new DiscountBps(entity.getApprovedDiscountBps()),
                events);
        if (!Objects.equals(entity.getActiveApplicationId(), request.activeApplicationId())) {
            throw new IllegalStateException("request " + entity.getId() + " in status " + entity.getStatus()
                    + " has active_application_id " + entity.getActiveApplicationId()
                    + ", expected " + request.activeApplicationId() + " (INV-04)");
        }
        return request;
    }

    /** Creates the rows for a freshly submitted request, including its SUBMITTED event. */
    public PricingExceptionRequestEntity toNewEntity(PricingExceptionRequest request) {
        PricingExceptionRequestEntity entity = new PricingExceptionRequestEntity();
        entity.setId(request.id());
        entity.setApplicationId(request.applicationId());
        entity.setRequestedBy(request.requestedBy());
        entity.setDiscountBps(request.discount().value());
        entity.setReason(request.reason().value());
        entity.setSupersedesId(request.supersedesId());
        entity.setCreatedAt(request.createdAt());
        applyTo(request, entity);
        return entity;
    }

    /**
     * Copies the mutable state of the aggregate onto the same managed entity after a command, and
     * appends the events the entity does not have yet (INV-02: one command, one new event).
     */
    public void applyTo(PricingExceptionRequest request, PricingExceptionRequestEntity entity) {
        if (!request.id().equals(entity.getId())) {
            throw new IllegalArgumentException("aggregate " + request.id() + " does not belong to row " + entity.getId());
        }
        List<RequestEvent> events = request.events();
        List<RequestEventEntity> stored = entity.getEvents();
        if (stored.size() > events.size()) {
            throw new IllegalStateException("aggregate " + request.id() + " is stale: it has " + events.size()
                    + " events but the row already has " + stored.size());
        }
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).getType() != events.get(i).type()) {
                throw new IllegalStateException("aggregate " + request.id() + " does not match the stored history at event " + i);
            }
        }

        entity.setStatus(request.status());
        entity.setDecidedBy(request.decidedBy());
        entity.setDecidedAt(request.decidedAt());
        entity.setDecisionComment(request.decisionComment());
        entity.setApprovedDiscountBps(request.approvedDiscount() == null ? null : request.approvedDiscount().value());
        entity.setActiveApplicationId(request.activeApplicationId());

        for (int i = stored.size(); i < events.size(); i++) {
            RequestEvent e = events.get(i);
            stored.add(new RequestEventEntity(e.type(), e.actorId(), e.occurredAt(),
                    e.fromStatus(), e.toStatus(), e.comment()));
        }
    }
}
