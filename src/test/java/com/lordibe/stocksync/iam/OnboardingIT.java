package com.lordibe.stocksync.iam;

import com.lordibe.stocksync.iam.domain.InvitationStatus;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.repository.InvitationRepository;
import com.lordibe.stocksync.iam.service.OnboardingService;
import com.lordibe.stocksync.platform.housekeeping.HousekeepingJob;
import com.lordibe.stocksync.iam.service.TokenPair;
import com.lordibe.stocksync.iam.web.dto.AcceptInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.CreateInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.RegisterTenantRequest;
import com.lordibe.stocksync.shared.security.JwtClaims;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.support.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** The two doors into the system: registering a business, and being invited to one. */
class OnboardingIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired OnboardingService onboarding;
    @Autowired InvitationRepository invitations;
    @Autowired AppUserRepository users;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired HousekeepingJob housekeeping;

    private UUID ownerId;

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("registering creates the tenant and signs the owner in")
    void registrationCreatesTenantAndOwner() {
        String slug = "acme-" + UUID.randomUUID();

        TokenPair tokens = onboarding.registerTenant(new RegisterTenantRequest(
                "Acme Ltd", slug, "Ann Owner", "ann@acme.io", PASSWORD));

        var token = jwtDecoder.decode(tokens.accessToken());
        assertThat(token.getClaimAsString(JwtClaims.ROLE)).isEqualTo(UserRole.OWNER.name());
        assertThat(token.getClaimAsStringList(JwtClaims.PERMISSIONS)).contains("tenant.close");
    }

    @Test
    @DisplayName("a taken slug is refused")
    void slugsAreUnique() {
        String slug = "acme-" + UUID.randomUUID();
        onboarding.registerTenant(new RegisterTenantRequest(
                "Acme", slug, "Ann", "ann@acme.io", PASSWORD));

        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                onboarding.registerTenant(new RegisterTenantRequest(
                        "Impostor", slug, "Bob", "bob@other.io", PASSWORD)));
    }

    @Test
    @DisplayName("an invited user sets their own password and lands in the right tenant and role")
    void invitationRoundTrip() {
        UUID tenantId = registerAndBind();

        var created = onboarding.invite(
                new CreateInvitationRequest("staff@acme.io", UserRole.STAFF),
                UserRole.OWNER, ownerId);
        assertThat(created.rawToken()).isNotBlank();
        // Only the digest is stored, so the database holds nothing redeemable.
        assertThat(invitations.findById(created.invitation().getId()).orElseThrow().getTokenHash())
                .isNotEqualTo(created.rawToken());

        TokenPair tokens = onboarding.acceptInvitation(new AcceptInvitationRequest(
                created.rawToken(), "Sam Staff", PASSWORD));

        var token = jwtDecoder.decode(tokens.accessToken());
        assertThat(token.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
        assertThat(token.getClaimAsString(JwtClaims.ROLE)).isEqualTo(UserRole.STAFF.name());
        assertThat(users.findByTenantIdAndEmailIgnoreCase(tenantId, "staff@acme.io")).isPresent();
    }

    @Test
    @DisplayName("a token cannot be used twice, and an unknown one is refused the same way")
    void invitationsAreSingleUse() {
        registerAndBind();
        var created = onboarding.invite(
                new CreateInvitationRequest("staff@acme.io", UserRole.STAFF),
                UserRole.OWNER, ownerId);
        onboarding.acceptInvitation(
                new AcceptInvitationRequest(created.rawToken(), "Sam", PASSWORD));

        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                onboarding.acceptInvitation(
                        new AcceptInvitationRequest(created.rawToken(), "Sam Again", PASSWORD)));
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                onboarding.acceptInvitation(
                        new AcceptInvitationRequest("not-a-real-token", "Nobody", PASSWORD)));
    }

    @Test
    @DisplayName("an admin who may invite still cannot mint an owner")
    void privilegeCannotBeEscalatedByInvitation() {
        UUID tenantId = registerAndBind();
        TestAuthentication.as(UserRole.ADMIN, tenantId);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                onboarding.invite(new CreateInvitationRequest("boss@acme.io", UserRole.OWNER),
                        UserRole.ADMIN, ownerId));
    }

    @Test
    @DisplayName("a manager cannot issue invitations at all")
    void invitingNeedsThePermission() {
        UUID tenantId = registerAndBind();
        TestAuthentication.as(UserRole.MANAGER, tenantId);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                onboarding.invite(new CreateInvitationRequest("staff@acme.io", UserRole.STAFF),
                        UserRole.MANAGER, ownerId));
    }

    @Test
    @DisplayName("a revoked invitation stops working")
    void revokedInvitationsAreRefused() {
        registerAndBind();
        var created = onboarding.invite(
                new CreateInvitationRequest("staff@acme.io", UserRole.STAFF),
                UserRole.OWNER, ownerId);

        onboarding.revokeInvitation(created.invitation().getId());

        assertThat(onboarding.pendingInvitations()).isEmpty();
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                onboarding.acceptInvitation(
                        new AcceptInvitationRequest(created.rawToken(), "Sam", PASSWORD)));
        assertThat(invitations.findById(created.invitation().getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    @DisplayName("an expired invitation is swept and no longer redeemable")
    void expiredInvitationsAreSwept() {
        registerAndBind();
        var created = onboarding.invite(
                new CreateInvitationRequest("late@acme.io", UserRole.STAFF),
                UserRole.OWNER, ownerId);

        var invitation = invitations.findById(created.invitation().getId()).orElseThrow();
        invitation.setExpiresAt(OffsetDateTime.now().minusDays(1));
        invitations.saveAndFlush(invitation);

        // Driven through the job rather than the repository: a @Modifying query
        // needs a transaction, and this also exercises the path that really runs.
        housekeeping.sweep();

        assertThat(invitations.findById(created.invitation().getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.EXPIRED);
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                onboarding.acceptInvitation(
                        new AcceptInvitationRequest(created.rawToken(), "Late", PASSWORD)));
    }

    /**
     * Registers a tenant, binds it as the acting context, and remembers the owner.
     *
     * <p>The owner's real id matters: {@code fk_invitation_inviter} is a composite
     * key over (tenant_id, invited_by), so an invented inviter is rejected by the
     * database — which is the constraint doing its job.
     */
    private UUID registerAndBind() {
        TokenPair tokens = onboarding.registerTenant(new RegisterTenantRequest(
                "Acme", "acme-" + UUID.randomUUID(), "Ann", "ann@acme.io", PASSWORD));
        var token = jwtDecoder.decode(tokens.accessToken());
        UUID tenantId = UUID.fromString(token.getClaimAsString(JwtClaims.TENANT_ID));
        ownerId = UUID.fromString(token.getSubject());
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId);
        return tenantId;
    }
}
