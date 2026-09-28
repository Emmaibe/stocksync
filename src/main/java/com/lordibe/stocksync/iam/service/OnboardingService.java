package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.Invitation;
import com.lordibe.stocksync.iam.domain.InvitationStatus;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.repository.InvitationRepository;
import com.lordibe.stocksync.iam.web.dto.AcceptInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.CreateInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.RegisterTenantRequest;
import com.lordibe.stocksync.platform.email.service.EmailOutbox;
import com.lordibe.stocksync.platform.email.service.EmailTemplates;
import com.lordibe.stocksync.shared.security.SecureTokens;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * How tenants and users come into existence.
 *
 * <p>Two doors, and only two: registering a new business (which creates its owner)
 * and redeeming an invitation. There is deliberately no "create user" endpoint
 * that takes a password — an administrator should never know a colleague's
 * credential, so the invitee always sets their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final InvitationRepository invitations;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;

    /** Creates a business and its first user, who is necessarily the OWNER. */
    @Transactional
    public TokenPair registerTenant(RegisterTenantRequest request) {
        String slug = request.tenantSlug().toLowerCase(Locale.ROOT);
        if (tenants.existsBySlug(slug)) {
            // Safe to disclose: slugs are public identifiers, and a caller would
            // discover the collision on the login page anyway.
            throw new ConflictException("The identifier '" + slug + "' is already taken");
        }

        Tenant tenant = tenants.save(Tenant.builder()
                .slug(slug)
                .name(request.tenantName())
                .status(TenantStatus.ACTIVE)
                .plan(TenantPlan.FREE)
                .build());

        AppUser owner = users.save(AppUser.builder()
                .tenantId(tenant.getId())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(UserRole.OWNER)
                .status(UserStatus.ACTIVE)
                .lastLoginAt(OffsetDateTime.now())
                .build());

        log.info("Registered tenant {} with owner {}", tenant.getSlug(), owner.getId());
        return tokenService.issueFor(owner, tenant.getSlug());
    }

    /**
     * Issues an invitation and returns the raw token exactly once.
     *
     * <p>A caller may not invite above their own level: letting a manager mint an
     * owner would make every role a formality.
     */
    @PreAuthorize("hasAuthority('user.invite')")
    @Transactional
    public InvitationWithToken invite(CreateInvitationRequest request, UserRole inviterRole,
                                      UUID inviterId) {
        UUID tenantId = TenantContext.require();

        if (request.role().ordinal() < inviterRole.ordinal()) {
            // A permission failure, not a conflict: 403 rather than 409.
            throw new AccessDeniedException("You cannot invite someone with more access than you have");
        }
        if (users.existsByTenantIdAndEmailIgnoreCase(tenantId, request.email())) {
            throw new ConflictException("That email already has an account in this workspace");
        }

        String rawToken = SecureTokens.generate();
        Invitation invitation = invitations.save(Invitation.builder()
                .tenantId(tenantId)
                .email(request.email())
                .role(request.role())
                .tokenHash(SecureTokens.hash(rawToken))
                .status(InvitationStatus.PENDING)
                .invitedBy(inviterId)
                .expiresAt(OffsetDateTime.now().plus(INVITATION_VALIDITY))
                .build());

        // Enqueued in this transaction: the invitation and its email commit or
        // roll back together.
        sendInvitationEmail(invitation, rawToken);
        return new InvitationWithToken(invitation, rawToken);
    }

    /**
     * Redeems an invitation, creating the user with the password they choose.
     *
     * <p>Runs with no tenant bound — the redeemer has no token yet — so the
     * invitation's own tenant is the only source of scope here.
     */
    @Transactional
    public TokenPair acceptInvitation(AcceptInvitationRequest request) {
        Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(request.token()))
                // Same answer for unknown, expired, revoked and already-used, so a
                // token cannot be probed for its state.
                .filter(candidate -> candidate.isRedeemable(OffsetDateTime.now()))
                .orElseThrow(() -> new BadCredentialsException("This invitation is not valid"));

        Tenant tenant = tenants.findById(invitation.getTenantId())
                .filter(t -> t.getStatus() != TenantStatus.CLOSED)
                .orElseThrow(() -> new BadCredentialsException("This invitation is not valid"));

        AppUser user = users.save(AppUser.builder()
                .tenantId(invitation.getTenantId())
                .email(invitation.getEmail())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(invitation.getRole())
                .status(UserStatus.ACTIVE)
                .lastLoginAt(OffsetDateTime.now())
                .build());

        invitation.accept(user.getId(), OffsetDateTime.now());
        log.info("Invitation {} accepted by user {}", invitation.getId(), user.getId());
        return tokenService.issueFor(user, tenant.getSlug());
    }

    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public List<Invitation> pendingInvitations() {
        return invitations.findByTenantIdAndStatusOrderByCreatedAtDesc(
                TenantContext.require(), InvitationStatus.PENDING);
    }

    @PreAuthorize("hasAuthority('user.invite')")
    @Transactional
    public void revokeInvitation(UUID invitationId) {
        Invitation invitation = invitations
                .findByTenantIdAndId(TenantContext.require(), invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId));
        invitation.revoke();
    }

    /**
     * Issues a fresh token for an existing invitation and emails it again.
     *
     * <p>A new token, not the old one: the original was never stored, only its
     * digest. Reissuing also means a link that leaked in a forwarded email stops
     * working.
     */
    @PreAuthorize("hasAuthority('user.invite')")
    @Transactional
    public Invitation resendInvitation(UUID invitationId) {
        Invitation invitation = invitations
                .findByTenantIdAndId(TenantContext.require(), invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId));
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ConflictException("Only a pending invitation can be resent");
        }

        String rawToken = SecureTokens.generate();
        invitation.setTokenHash(SecureTokens.hash(rawToken));
        invitation.setExpiresAt(OffsetDateTime.now().plus(INVITATION_VALIDITY));
        sendInvitationEmail(invitation, rawToken);
        return invitation;
    }

    private void sendInvitationEmail(Invitation invitation, String rawToken) {
        Tenant tenant = tenants.findById(invitation.getTenantId()).orElseThrow();
        String inviterName = invitation.getInvitedBy() == null ? "Someone"
                : users.findById(invitation.getInvitedBy())
                        .map(AppUser::getFullName)
                        .orElse("Someone");
        outbox.enqueue(invitation.getTenantId(), invitation.getEmail(), EmailTemplates.INVITATION,
                templates.invitation(tenant.getName(), inviterName, rawToken, INVITATION_VALIDITY));
    }

    /** An invitation together with the token that will never be recoverable again. */
    public record InvitationWithToken(Invitation invitation, String rawToken) {
    }
}
