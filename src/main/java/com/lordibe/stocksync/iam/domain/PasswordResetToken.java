package com.lordibe.stocksync.iam.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single-use password reset, stored as a digest.
 *
 * <p>No {@code @Filter}: redemption happens with no tenant bound, so this is
 * looked up by token hash alone — which is why the hash is unique platform-wide.
 * Queries that list a user's tokens pass the tenant explicitly.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class PasswordResetToken extends AuditedTenantEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public boolean isRedeemable(OffsetDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(OffsetDateTime now) {
        this.usedAt = now;
    }
}
