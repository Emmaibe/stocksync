package com.lordibe.stocksync.iam.repository;

import com.lordibe.stocksync.iam.domain.Invitation;
import com.lordibe.stocksync.iam.domain.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /**
     * Redemption lookup. Deliberately not tenant-scoped: the person redeeming has
     * no token yet, so no tenant is bound — the hash itself identifies the tenant,
     * which is why it is unique platform-wide.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, InvitationStatus status);

    Optional<Invitation> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Marks pending invitations past their expiry. Used by the housekeeping job. */
    @Modifying
    @Query("""
            update Invitation i
               set i.status = com.lordibe.stocksync.iam.domain.InvitationStatus.EXPIRED
             where i.status = com.lordibe.stocksync.iam.domain.InvitationStatus.PENDING
               and i.expiresAt < :now
            """)
    int expireStale(@Param("now") OffsetDateTime now);
}
