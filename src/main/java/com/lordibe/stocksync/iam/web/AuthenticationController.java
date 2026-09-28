package com.lordibe.stocksync.iam.web;

import com.lordibe.stocksync.iam.service.AuthenticationService;
import com.lordibe.stocksync.iam.service.OnboardingService;
import com.lordibe.stocksync.iam.service.PasswordResetService;
import com.lordibe.stocksync.iam.web.dto.CompletePasswordResetRequest;
import com.lordibe.stocksync.iam.web.dto.PasswordResetRequest;
import com.lordibe.stocksync.shared.ratelimit.RateLimitPolicy;
import com.lordibe.stocksync.shared.ratelimit.RateLimiter;
import com.lordibe.stocksync.shared.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import com.lordibe.stocksync.iam.web.dto.AcceptInvitationRequest;
import com.lordibe.stocksync.iam.web.dto.RegisterTenantRequest;
import com.lordibe.stocksync.iam.web.dto.LoginRequest;
import com.lordibe.stocksync.iam.web.dto.RefreshRequest;
import com.lordibe.stocksync.iam.web.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The only endpoints reachable without a token. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final OnboardingService onboarding;
    private final PasswordResetService passwordReset;
    private final RateLimiter rateLimiter;

    /**
     * Creates a business and its owner, and signs that owner straight in.
     *
     * <p>Rate limited by client address: a tenant is cheap to create and permanent
     * for us, so this is the strictest limit in the system.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterTenantRequest request,
                                  HttpServletRequest httpRequest) {
        rateLimiter.checkAndRecord(RateLimitPolicy.REGISTRATION, ClientAddress.of(httpRequest));
        return TokenResponse.from(onboarding.registerTenant(request));
    }

    /**
     * Asks for a password reset link.
     *
     * <p>Always 202, whether or not the address has an account: answering
     * differently would turn this into a way to discover which emails exist in
     * which workspace. Limited per address, because the cost of abuse is mail
     * arriving at someone who never asked for it.
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                     HttpServletRequest httpRequest) {
        rateLimiter.checkAndRecord(RateLimitPolicy.PASSWORD_RESET_REQUEST,
                ClientAddress.of(httpRequest));
        passwordReset.requestReset(request);
    }

    /** Redeems a reset link, sets the new password and ends every session. */
    @PostMapping("/password-reset/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completePasswordReset(@Valid @RequestBody CompletePasswordResetRequest request) {
        passwordReset.completeReset(request);
    }

    /** Redeems an invitation. Public: the redeemer has no token yet. */
    @PostMapping("/invitations/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return TokenResponse.from(onboarding.acceptInvitation(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.from(authentication.login(request));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(authentication.refresh(request.refreshToken()));
    }

    /**
     * Ends the caller's sessions. Requires a valid access token — the subject comes
     * from the token, so one user cannot sign another out.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        authentication.logout(UUID.fromString(jwt.getSubject()));
    }
}
