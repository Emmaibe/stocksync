package com.lordibe.stocksync.tenant.repository;

import com.lordibe.stocksync.tenant.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** The one repository that is not tenant-scoped: it is how a tenant is found. */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
