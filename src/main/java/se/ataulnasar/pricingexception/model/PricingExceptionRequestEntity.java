package se.ataulnasar.pricingexception.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Row in {@code pricing_exception_request} plus its {@code request_event} rows.
 * {@code version} backs optimistic locking (If-Match); {@code activeApplicationId} backs INV-04.
 */
@Entity
@Table(name = "pricing_exception_request")
public class PricingExceptionRequestEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "application_id", length = 64, nullable = false)
    private String applicationId;

    @Column(name = "requested_by", length = 64, nullable = false)
    private String requestedBy;

    @Column(name = "discount_bps", nullable = false)
    private int discountBps;

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "status", length = 32, nullable = false)
    private RequestStatus status;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", length = 500)
    private String decisionComment;

    @Column(name = "approved_discount_bps")
    private Integer approvedDiscountBps;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "active_application_id", length = 64)
    private String activeApplicationId;

    // Insert only: no REMOVE cascade and no orphan removal, so nothing here can delete history rows.
    // Ordered by the identity id, which is the insertion order even if a clock steps backwards.
    @OneToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    @OrderBy("id ASC")
    private List<RequestEventEntity> events = new ArrayList<>();

    public PricingExceptionRequestEntity() {
        // JPA and the service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public int getDiscountBps() {
        return discountBps;
    }

    public void setDiscountBps(int discountBps) {
        this.discountBps = discountBps;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public UUID getSupersedesId() {
        return supersedesId;
    }

    public void setSupersedesId(UUID supersedesId) {
        this.supersedesId = supersedesId;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    public Integer getApprovedDiscountBps() {
        return approvedDiscountBps;
    }

    public void setApprovedDiscountBps(Integer approvedDiscountBps) {
        this.approvedDiscountBps = approvedDiscountBps;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public String getActiveApplicationId() {
        return activeApplicationId;
    }

    public void setActiveApplicationId(String activeApplicationId) {
        this.activeApplicationId = activeApplicationId;
    }

    /** The history rows, oldest first. Mutable so the service can append; removing from it has no effect on rows. */
    public List<RequestEventEntity> getEvents() {
        return events;
    }
}
