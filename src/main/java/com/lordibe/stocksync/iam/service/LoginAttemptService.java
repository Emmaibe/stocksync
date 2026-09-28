package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.shared.ratelimit.RateLimitPolicy;
import com.lordibe.stocksync.shared.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Login throttling.
 *
 * <p>A thin face over the shared {@link RateLimiter}, kept as its own type
 * because the key it builds is a decision worth naming: attempts are counted per
 * tenant + email, not per IP. An attacker spraying one password across many
 * accounts comes from many addresses, and an IP key would lock out a whole office
 * behind one NAT. IP-level limiting is a different control, applied to
 * registration and password reset where there is no credential to key on.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptService {

    private final RateLimiter rateLimiter;

    public static String keyFor(String tenantSlug, String email) {
        return tenantSlug.toLowerCase(Locale.ROOT) + "|" + email.toLowerCase(Locale.ROOT);
    }

    /**
     * Counts this attempt and refuses once the allowance is spent.
     *
     * <p>Called before the password is checked, so knowing the correct password
     * does not get an attacker past a lockout.
     */
    public void assertNotBlocked(String key) {
        rateLimiter.checkAndRecord(RateLimitPolicy.LOGIN, key);
    }

    /**
     * Kept for the call site's readability. Attempts are counted on the way in, so
     * a failure needs no further recording.
     */
    public void recordFailure(String key) {
        // Intentionally empty: checkAndRecord already counted this attempt.
    }

    /** Clears the count, so a user who mistypes twice then succeeds is not penalised. */
    public void recordSuccess(String key) {
        rateLimiter.reset(RateLimitPolicy.LOGIN, key);
    }
}
