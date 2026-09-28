package com.lordibe.stocksync.iam.repository;

import com.lordibe.stocksync.iam.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /** Redemption lookup, by hash alone — no tenant is bound at that point. */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Invalidates a user's outstanding tokens.
     *
     * <p>Called when a new one is issued and again when one is redeemed, so a
     * second reset link cannot be used after the first has changed the password.
     */
    @Modifying
    @Query("""
            update PasswordResetToken t
               set t.usedAt = :now
             where t.userId = :userId
               and t.usedAt is null
            """)
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** Housekeeping: expired-and-unused rows are of no further use. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
