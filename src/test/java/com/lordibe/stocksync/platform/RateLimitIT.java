package com.lordibe.stocksync.platform;

import com.lordibe.stocksync.iam.service.TooManyLoginAttemptsException;
import com.lordibe.stocksync.shared.ratelimit.RateLimitPolicy;
import com.lordibe.stocksync.shared.ratelimit.RateLimiter;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RateLimitIT extends AbstractPostgresIntegrationTest {

    @Autowired RateLimiter rateLimiter;

    @Test
    @DisplayName("registration allows three from one address, then refuses with a wait")
    void registrationIsCapped() {
        String address = "203.0.113." + (int) (Math.random() * 250);

        for (int attempt = 0; attempt < RateLimitPolicy.REGISTRATION.maxAttempts(); attempt++) {
            assertThatNoException().isThrownBy(() ->
                    rateLimiter.checkAndRecord(RateLimitPolicy.REGISTRATION, address));
        }

        TooManyLoginAttemptsException refused = org.junit.jupiter.api.Assertions.assertThrows(
                TooManyLoginAttemptsException.class,
                () -> rateLimiter.checkAndRecord(RateLimitPolicy.REGISTRATION, address));
        // The client is told how long to wait rather than being left to guess.
        assertThat(refused.getRetryAfter().toMinutes()).isPositive();
    }

    @Test
    @DisplayName("policies count separately, so one endpoint cannot exhaust another")
    void bucketsAreIndependent() {
        String key = "shared-key-" + UUID.randomUUID();

        for (int attempt = 0; attempt < RateLimitPolicy.REGISTRATION.maxAttempts(); attempt++) {
            rateLimiter.checkAndRecord(RateLimitPolicy.REGISTRATION, key);
        }
        assertThatExceptionOfType(TooManyLoginAttemptsException.class).isThrownBy(() ->
                rateLimiter.checkAndRecord(RateLimitPolicy.REGISTRATION, key));

        // Same key, different policy: untouched.
        assertThatNoException().isThrownBy(() ->
                rateLimiter.checkAndRecord(RateLimitPolicy.PASSWORD_RESET_REQUEST, key));
    }

    @Test
    @DisplayName("a reset clears the count")
    void resetForgivesAKey() {
        String key = "forgiven-" + UUID.randomUUID();
        for (int attempt = 0; attempt < RateLimitPolicy.LOGIN.maxAttempts(); attempt++) {
            rateLimiter.checkAndRecord(RateLimitPolicy.LOGIN, key);
        }

        rateLimiter.reset(RateLimitPolicy.LOGIN, key);

        assertThatNoException().isThrownBy(() ->
                rateLimiter.checkAndRecord(RateLimitPolicy.LOGIN, key));
    }
}
