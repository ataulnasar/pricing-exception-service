package se.ataulnasar.pricingexception.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import se.ataulnasar.pricingexception.model.Role;

/** Row in {@code app_user}: a seeded user, identified by the {@code X-User-Id} header value. */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // keep VARCHAR: H2 fails the CHECK constraints when Hibernate binds its native ENUM type
    @Column(name = "role", length = 32, nullable = false)
    private Role role;

    @Column(name = "approval_limit_bps")
    private Integer approvalLimitBps;

    protected AppUserEntity() {
        // JPA
    }

    public AppUserEntity(String id, String name, Role role, Integer approvalLimitBps) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.approvalLimitBps = approvalLimitBps;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public Integer getApprovalLimitBps() {
        return approvalLimitBps;
    }
}
