package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.PasswordResetToken;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.repository.PasswordResetTokenRepository;
import com.lordibe.stocksync.iam.web.dto.CompletePasswordResetRequest;
import com.lordibe.stocksync.iam.web.dto.PasswordResetRequest;
import com.lordibe.stocksync.platform.email.service.EmailOutbox;
import com.lordibe.stocksync.platform.email.service.EmailTemplates;
import com.lordibe.stocksync.shared.security.SecureTokens;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Forgotten passwords.
 *
 * <p>Two properties matter more than the mechanics. First, <strong>the response
 * never reveals whether the address exists</strong> — requesting a reset always
 * looks the same, or the form becomes a way to enumerate which emails have
 * accounts in which workspace. Second, <strong>completing a reset signs the user
 * out everywhere</strong>: if the reset was prompted by a compromise, leaving the
 * attacker's sessions alive defeats the point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    /** Short: this is a live credential sitting in an inbox. */
    private static final Duration TOKEN_VALIDITY = Duration.ofMinutes(30);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;

    /**
     * Issues a reset link if the account exists, and says nothing either way.
     *
     * <p>Any outstanding token is invalidated first, so a second request makes the
     * first link dead — otherwise every request would leave another live
     * credential in the mailbox.
     */
    @Transactional
    public void requestReset(PasswordResetRequest request) {
        Optional<Tenant> tenant = tenants.findBySlug(request.tenantSlug())
                .filter(t -> t.getStatus() != TenantStatus.CLOSED);
        Optional<AppUser> user = tenant.flatMap(t ->
                users.findByTenantIdAndEmailIgnoreCase(t.getId(), request.email()));

        if (user.isEmpty() || !user.get().canAuthenticate()) {
            // Deliberately silent. The caller gets the same 202 either way.
            log.debug("Password reset requested for an address with no usable account");
            return;
        }

        AppUser account = user.get();
        resetTokens.invalidateOutstanding(account.getId(), OffsetDateTime.now());

        String rawToken = SecureTokens.generate();
        resetTokens.save(PasswordResetToken.builder()
                .tenantId(account.getTenantId())
                .userId(account.getId())
                .tokenHash(SecureTokens.hash(rawToken))
                .expiresAt(OffsetDateTime.now().plus(TOKEN_VALIDITY))
                .build());

        outbox.enqueue(account.getTenantId(), account.getEmail(), EmailTemplates.PASSWORD_RESET,
                templates.passwordReset(rawToken, TOKEN_VALIDITY));
    }

    /** Redeems a reset token and sets the new password. */
    @Transactional
    public void completeReset(CompletePasswordResetRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        PasswordResetToken token = resetTokens.findByTokenHash(SecureTokens.hash(request.token()))
                .filter(candidate -> candidate.isRedeemable(now))
                // Unknown, expired and already-used all answer identically.
                .orElseThrow(() -> new BadCredentialsException("This reset link is not valid"));

        AppUser user = users.findById(token.getUserId())
                .orElseThrow(() -> new BadCredentialsException("This reset link is not valid"));

        // changePassword also bumps the token version, ending every session.
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        token.markUsed(now);
        resetTokens.invalidateOutstanding(user.getId(), now);

        outbox.enqueue(user.getTenantId(), user.getEmail(), EmailTemplates.PASSWORD_CHANGED,
                templates.passwordChanged());
        log.info("Password reset completed for user {}", user.getId());
    }
}
