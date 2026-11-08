package se.ataulnasar.pricingexception.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Row in {@code request_event}. Insert only; owned by {@link PricingExceptionRequestEntity#getEvents()}. */
@Entity
@Table(name = "request_event")
public class RequestEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "type", length = 32, nullable = false)
    private EventType type;

    @Column(name = "actor_id", length = 64, nullable = false)
    private String actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "from_status", length = 32)
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "to_status", length = 32, nullable = false)
    private RequestStatus toStatus;

    @Column(name = "comment", length = 500)
    private String comment;

    public RequestEventEntity() {
        // JPA and tests
    }

    public RequestEventEntity(EventType type, String actorId, Instant occurredAt,
                              RequestStatus fromStatus, RequestStatus toStatus, String comment) {
        this.type = type;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public RequestStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(RequestStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public RequestStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(RequestStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getComment() {
        return comment;
    }
}
