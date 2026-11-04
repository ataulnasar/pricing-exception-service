package se.ataulnasar.pricingexception.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row in {@code mortgage_application} (seed data). */
@Entity
@Table(name = "mortgage_application")
public class MortgageApplicationEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "customer_ref", length = 100, nullable = false)
    private String customerRef;

    @Column(name = "standard_rate_bps", nullable = false)
    private int standardRateBps;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "status", length = 32, nullable = false)
    private ApplicationStatus status;

    @Column(name = "owner_rm_id", length = 64, nullable = false)
    private String ownerRmId;

    public MortgageApplicationEntity() {
        // JPA and tests
    }

    public MortgageApplicationEntity(String id, String customerRef, int standardRateBps,
                                     ApplicationStatus status, String ownerRmId) {
        this.id = id;
        this.customerRef = customerRef;
        this.standardRateBps = standardRateBps;
        this.status = status;
        this.ownerRmId = ownerRmId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public int getStandardRateBps() {
        return standardRateBps;
    }

    public void setStandardRateBps(int standardRateBps) {
        this.standardRateBps = standardRateBps;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public String getOwnerRmId() {
        return ownerRmId;
    }

    public void setOwnerRmId(String ownerRmId) {
        this.ownerRmId = ownerRmId;
    }
}
