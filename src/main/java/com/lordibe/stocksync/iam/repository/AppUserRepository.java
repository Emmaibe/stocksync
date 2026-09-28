package com.lordibe.stocksync.iam.repository;

import com.lordibe.stocksync.iam.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Login lookup. Case-insensitive to match {@code uk_app_user_tenant_email},
     * and tenant-scoped explicitly because authentication runs before any tenant
     * is bound to the context.
     */
    Optional<AppUser> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    boolean existsByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
}
