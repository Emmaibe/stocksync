package com.lordibe.stocksync.tenant.domain;

import com.lordibe.stocksync.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A business using the platform. Root of every tenant_id chain, and the one
 * entity that is not itself tenant-scoped.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class Tenant extends BaseEntity {

    /** URL-safe identifier, unique platform-wide. Immutable once issued. */
    @Column(name = "slug", nullable = false, length = 80, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 30)
    private TenantPlan plan;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean acceptsWrites() {
        return status == TenantStatus.ACTIVE;
    }
}
