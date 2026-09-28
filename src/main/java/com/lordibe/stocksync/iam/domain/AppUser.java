package com.lordibe.stocksync.iam.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

/**
 * A person's account within one tenant.
 *
 * <p>The same email may exist in several tenants; identity is
 * {@code (tenant_id, lower(email))}, so a login carries the tenant with it.
 */
@Entity
@Table(name = "app_user")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class AppUser extends AuditedTenantEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** BCrypt hash. Never leaves the persistence layer. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * Bumped to end every existing session for this user. Tokens carry the value
     * they were issued with, and a refresh presenting an older one is refused.
     */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * Invalidates outstanding sessions at their next refresh. Called on logout,
     * on password change, and when an account is disabled.
     */
    public void revokeTokens() {
        this.tokenVersion++;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        // A password change must not leave old sessions alive.
        revokeTokens();
    }
}
