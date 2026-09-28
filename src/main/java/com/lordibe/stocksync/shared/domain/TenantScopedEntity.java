package com.lordibe.stocksync.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.util.UUID;

/**
 * Base type for rows owned by exactly one tenant.
 *
 * <p>Subclasses inherit the {@code tenantFilter} declaration, but Hibernate only
 * applies a filter to entities that carry {@link Filter} themselves, so every
 * concrete entity repeats the annotation. The filter is defined once in
 * {@code com.lordibe.stocksync.shared.tenancy} and enabled per session by
 * {@code TenantFilterActivator}.
 *
 * <p>{@code tenantId} is not updatable: a row never moves between tenants.
 */
@MappedSuperclass
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
}
