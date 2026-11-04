package se.ataulnasar.pricingexception.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Row in {@code idempotency_record}: the stored response for one (caller, Idempotency-Key).
 * {@code responseStatus} 0 means "claimed, create in progress" inside the creating transaction.
 *
 * <p>Implements {@link Persistable} so that saving a new record is an INSERT that fails on a
 * duplicate key. With an assigned id Spring Data would otherwise merge: Hibernate selects first,
 * and if a concurrent caller has just committed the same key the "insert" silently turns into an
 * UPDATE of the winner's row, and the claim is not detected.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity implements Persistable<IdempotencyRecordId> {

    /** Marker status while the creating transaction is still running. */
    public static final int IN_PROGRESS = 0;

    @EmbeddedId
    private IdempotencyRecordId id;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", length = 16000, nullable = false)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyRecordEntity() {
        // JPA
    }

    public IdempotencyRecordEntity(IdempotencyRecordId id, String requestHash, int responseStatus,
                                   String responseBody, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    @Override
    public IdempotencyRecordId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Called once, when the create inside the same transaction has produced its response. */
    public void complete(int responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }
}
