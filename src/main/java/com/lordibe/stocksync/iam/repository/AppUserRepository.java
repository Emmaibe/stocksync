package com.lordibe.stocksync.iam.repository;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * The tenant's users, filtered by status and an optional name or email search.
     *
     * <p>Tenant-scoped by the Hibernate filter rather than by a predicate here,
     * like every other query that runs with a caller in context.
     *
     * <p>Statuses arrive as a non-empty collection instead of a nullable enum:
     * "all statuses" is expressed as all of them, which keeps one nullable
     * parameter in the query rather than two and avoids typed-null trouble.
     */
    @Query("""
            select u from AppUser u
            where u.status in :statuses
              and (:term is null
                   or lower(u.fullName) like :term escape '!'
                   or lower(u.email) like :term escape '!')
            """)
    Page<AppUser> search(@Param("statuses") Collection<UserStatus> statuses,
                         @Param("term") String term,
                         Pageable pageable);

    /**
     * Used to refuse the edit that would leave a workspace with no owner who can
     * sign in — a state nothing inside the application could then repair.
     */
    long countByRoleAndStatus(UserRole role, UserStatus status);
}
