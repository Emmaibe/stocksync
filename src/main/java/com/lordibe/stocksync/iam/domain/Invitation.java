package com.lordibe.stocksync.iam.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An offer to join a tenant, redeemable once.
 *
 * <p>Only the token's SHA-256 is stored: the raw value is shown to the inviter
 * once and never again, so a copy of this table cannot be used to join anything.
 *
 * <p>Note the deliberate absence of a {@code @Filter}-free path — like every
 * tenant-scoped entity it carries the tenant filter, but redemption looks the
 * invitation up by token hash <em>before</em> any tenant is known, which is why
 * that one query is deliberately unscoped and the hash is unique platform-wide.
 */
@Entity
@Table(name = "invitation")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class Invitation extends AuditedTenantEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvitationStatus status;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public boolean isRedeemable(OffsetDateTime now) {
        return status == InvitationStatus.PENDING && expiresAt.isAfter(now);
    }

    public void accept(UUID userId, OffsetDateTime now) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedUserId = userId;
        this.acceptedAt = now;
    }

    public void revoke() {
        this.status = InvitationStatus.REVOKED;
    }
}
