package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.web.dto.LoginRequest;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Optional;

/**
 * Signs users in and refreshes their tokens.
 *
 * <p>Every failure — unknown tenant, unknown email, wrong password, disabled
 * account, closed tenant — surfaces as the same {@link BadCredentialsException}.
 * Distinguishing them would let anyone enumerate which tenants exist and which
 * emails have accounts in them.
 *
 * <p>A dummy hash comparison runs when the user is not found, so a missing account
 * takes roughly as long as a wrong password and timing does not leak existence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    /** BCrypt hash of a value no one can supply; used only to burn equivalent time. */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.Ovv7VfXWCyBn0oWXqbTOJmk9ZLJk1Zu";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final JwtDecoder jwtDecoder;
    private final LoginAttemptService loginAttempts;

    @Transactional
    public TokenPair login(LoginRequest request) {
        String attemptKey = LoginAttemptService.keyFor(request.tenantSlug(), request.email());
        loginAttempts.assertNotBlocked(attemptKey);

        Tenant tenant = tenants.findBySlug(request.tenantSlug())
                .filter(t -> t.getStatus() != TenantStatus.CLOSED)
                .orElse(null);

        Optional<AppUser> found = tenant == null
                ? Optional.empty()
                : users.findByTenantIdAndEmailIgnoreCase(tenant.getId(), request.email());

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            loginAttempts.recordFailure(attemptKey);
            throw new BadCredentialsException("Invalid credentials");
        }

        AppUser user = found.get();
        if (!user.canAuthenticate()
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttempts.recordFailure(attemptKey);
            throw new BadCredentialsException("Invalid credentials");
        }

        loginAttempts.recordSuccess(attemptKey);
        user.setLastLoginAt(OffsetDateTime.now());
        log.debug("User {} signed in to tenant {}", user.getId(), tenant.getSlug());
        return tokenService.issueFor(user, tenant.getSlug());
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>Role and account status are re-read here rather than copied from the old
     * token, so a demotion or a disabled account takes effect at refresh time.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(refreshToken);
        } catch (JwtException invalid) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        if (!tokenService.isRefreshToken(jwt)) {
            // An access token must not be usable to extend a session.
            throw new BadCredentialsException("Invalid refresh token");
        }

        AppUser user = users.findById(tokenService.subjectOf(jwt))
                .filter(AppUser::canAuthenticate)
                .filter(u -> u.getTenantId().equals(tokenService.tenantOf(jwt)))
                // Revocation check: a token stamped with an older version was
                // issued before a logout, password change or forced sign-out.
                .filter(u -> u.getTokenVersion() == tokenService.tokenVersionOf(jwt))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        Tenant tenant = tenants.findById(user.getTenantId())
                .filter(t -> t.getStatus() != TenantStatus.CLOSED)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        return tokenService.issueFor(user, tenant.getSlug());
    }

    /**
     * Ends every session for a user by bumping their token version.
     *
     * <p>Outstanding refresh tokens stop working immediately. Access tokens already
     * issued stay valid until they expire — at most the access-token TTL — because
     * checking revocation on every request would mean a database read per call.
     * Shorten {@code stocksync.security.jwt.access-token-ttl} to narrow that window.
     */
    @Transactional
    public void logout(UUID userId) {
        users.findById(userId).ifPresent(user -> {
            user.revokeTokens();
            log.debug("Revoked tokens for user {}", userId);
        });
    }
}
