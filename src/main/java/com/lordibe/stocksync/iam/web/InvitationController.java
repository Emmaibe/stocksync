package com.lordibe.stocksync.iam.web;

import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.service.OnboardingService;
import com.lordibe.stocksync.iam.web.dto.CreateInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.InvitationResponse;
import com.lordibe.stocksync.shared.ratelimit.RateLimitPolicy;
import com.lordibe.stocksync.shared.ratelimit.RateLimiter;
import com.lordibe.stocksync.shared.security.JwtClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Managing invitations. Redeeming one lives on the public auth controller. */
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final OnboardingService onboarding;
    private final RateLimiter rateLimiter;

    /**
     * The response carries the raw token once. Until email is wired, the inviter
     * passes it on themselves; after that, remove it from this response so the
     * secret travels one channel only.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@Valid @RequestBody CreateInvitationRequest request,
                                     @AuthenticationPrincipal Jwt caller) {
        OnboardingService.InvitationWithToken created = onboarding.invite(
                request,
                UserRole.valueOf(caller.getClaimAsString(JwtClaims.ROLE)),
                UUID.fromString(caller.getSubject()));
        // The token is not returned: it goes to the invitee by email only.
        return InvitationResponse.from(created.invitation());
    }

    @GetMapping
    public List<InvitationResponse> pending() {
        return onboarding.pendingInvitations().stream()
                .map(InvitationResponse::from)
                .toList();
    }

    /**
     * Sends the invitation again with a fresh token, invalidating the old link.
     *
     * <p>Throttled per invitation: resending is a way to have mail delivered to
     * someone repeatedly.
     */
    @PostMapping("/{invitationId}/resend")
    public InvitationResponse resend(@PathVariable UUID invitationId) {
        rateLimiter.checkAndRecord(RateLimitPolicy.INVITATION_RESEND, invitationId.toString());
        return InvitationResponse.from(onboarding.resendInvitation(invitationId));
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID invitationId) {
        onboarding.revokeInvitation(invitationId);
    }
}
