package com.lordibe.stocksync.iam;

import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.repository.PasswordResetTokenRepository;
import com.lordibe.stocksync.iam.service.AuthenticationService;
import com.lordibe.stocksync.iam.service.OnboardingService;
import com.lordibe.stocksync.iam.service.PasswordResetService;
import com.lordibe.stocksync.iam.service.TokenPair;
import com.lordibe.stocksync.iam.web.dto.CompletePasswordResetRequest;
import com.lordibe.stocksync.iam.web.dto.LoginRequest;
import com.lordibe.stocksync.iam.web.dto.PasswordResetRequest;
import com.lordibe.stocksync.iam.web.dto.RegisterTenantRequest;
import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.domain.EmailStatus;
import com.lordibe.stocksync.platform.email.repository.EmailMessageRepository;
import com.lordibe.stocksync.platform.email.service.EmailTemplates;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.support.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Forgotten passwords, end to end through the outbox.
 *
 * <p>The token is read back out of the queued email, which is the only place it
 * exists — the same route a real user takes, and proof that nothing else in the
 * system can recover it.
 */
class PasswordResetIT extends AbstractPostgresIntegrationTest {

    private static final String OLD_PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "a-completely-different-one";
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

    @Autowired PasswordResetService passwordReset;
    @Autowired AuthenticationService authentication;
    @Autowired OnboardingService onboarding;
    @Autowired EmailMessageRepository emails;
    @Autowired AppUserRepository users;
    @Autowired PasswordResetTokenRepository resetTokens;

    private String slug;
    private String email;

    @BeforeEach
    void registerTenant() {
        slug = "acme-" + UUID.randomUUID();
        email = "ann-" + UUID.randomUUID() + "@acme.io";
        onboarding.registerTenant(new RegisterTenantRequest(
                "Acme", slug, "Ann Owner", email, OLD_PASSWORD));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("a reset changes the password, and the old one stops working")
    void resetChangesThePassword() {
        passwordReset.requestReset(new PasswordResetRequest(slug, email));

        passwordReset.completeReset(new CompletePasswordResetRequest(tokenFromEmail(), NEW_PASSWORD));

        assertThatNoException().isThrownBy(() ->
                authentication.login(new LoginRequest(slug, email, NEW_PASSWORD)));
        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                authentication.login(new LoginRequest(slug, email, OLD_PASSWORD)));
    }

    @Test
    @DisplayName("completing a reset signs the user out everywhere")
    void resetRevokesExistingSessions() {
        TokenPair before = authentication.login(new LoginRequest(slug, email, OLD_PASSWORD));
        passwordReset.requestReset(new PasswordResetRequest(slug, email));

        passwordReset.completeReset(new CompletePasswordResetRequest(tokenFromEmail(), NEW_PASSWORD));

        // The session that existed before the reset is dead — which is the point
        // when the reset was prompted by someone else having the password.
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.refresh(before.refreshToken()));
    }

    @Test
    @DisplayName("a reset link works once")
    void resetTokensAreSingleUse() {
        passwordReset.requestReset(new PasswordResetRequest(slug, email));
        String token = tokenFromEmail();
        passwordReset.completeReset(new CompletePasswordResetRequest(token, NEW_PASSWORD));

        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                passwordReset.completeReset(
                        new CompletePasswordResetRequest(token, "yet-another-password")));
    }

    @Test
    @DisplayName("asking again kills the previous link")
    void requestingAgainInvalidatesTheOldLink() {
        passwordReset.requestReset(new PasswordResetRequest(slug, email));
        String firstToken = tokenFromEmail();

        passwordReset.requestReset(new PasswordResetRequest(slug, email));

        assertThatExceptionOfType(BadCredentialsException.class).isThrownBy(() ->
                passwordReset.completeReset(
                        new CompletePasswordResetRequest(firstToken, NEW_PASSWORD)));
    }

    @Test
    @DisplayName("an unknown address is silently ignored and sends nothing")
    void unknownAddressesRevealNothing() {
        // No exception: answering differently would let anyone discover which
        // emails have accounts here.
        assertThatNoException().isThrownBy(() ->
                passwordReset.requestReset(new PasswordResetRequest(slug, "nobody@acme.io")));

        assertThat(emails.findByToAddressAndStatusOrderByCreatedAtDesc(
                "nobody@acme.io", EmailStatus.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("a completed reset warns the account owner")
    void completingSendsANotification() {
        passwordReset.requestReset(new PasswordResetRequest(slug, email));
        passwordReset.completeReset(new CompletePasswordResetRequest(tokenFromEmail(), NEW_PASSWORD));

        assertThat(emails.countByTemplateAndStatus(
                EmailTemplates.PASSWORD_CHANGED, EmailStatus.PENDING)).isPositive();
    }

    /** Pulls the token out of the queued email — the only place it exists. */
    private String tokenFromEmail() {
        List<EmailMessage> queued =
                emails.findByToAddressAndStatusOrderByCreatedAtDesc(email, EmailStatus.PENDING);
        EmailMessage reset = queued.stream()
                .filter(message -> EmailTemplates.PASSWORD_RESET.equals(message.getTemplate()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No reset email was queued"));
        Matcher matcher = TOKEN_IN_LINK.matcher(reset.getBodyText());
        assertThat(matcher.find()).as("reset email contains a token link").isTrue();
        return matcher.group(1);
    }
}
